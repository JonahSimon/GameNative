package app.gamenative.steamcontroller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.util.Log
import com.winlator.xserver.XServer

/**
 * Drives a Steam Controller (Puck over USB-C, no root) into GameNative. Reads the raw Triton report
 * stream, runs it through a [ProfileInterpreter] (which applies the active [ScProfile] and feeds
 * GameNative's injection seams: virtual XInput pad via WinHandler + mouse/keys via XServer), and
 * regenerates the trackpad haptics (TritonHaptics) since lizard-off disables the firmware's.
 *
 * When a per-game [config] is supplied (from [ScConfigStore], keyed by container/game), the interpreter loads
 * it and runs config-driven action-set switching / layers / mode-shift live in the game. With no config it
 * falls back to the hardcoded [ScProfile.default]; swap [ProfileInterpreter.profile] to change bindings.
 * The USB transport ([TritonUsb]) is one source; a future TritonBle can feed the same interpreter (the
 * decoder + interpreter are transport-agnostic — see docs/PLAN-2026-06-18-BLE-PIVOT.md).
 */
class TritonMapper(
    private val context: Context,
    private val xServer: XServer,
    private val config: ScConfig? = null,
    /** Step-6 menu HUD; defaults to no-op so the driver runs without an attached overlay view. */
    private val menuOverlay: ScMenuOverlay = NoOpScMenuOverlay,
    /** Split-trackpad keyboard HUD; defaults to no-op. */
    private val keyboardOverlay: ScKeyboardOverlay = NoOpScKeyboardOverlay,
    /** [ScConfigStore] key (container/appId) this session was launched for. Enables live [reload] after an
     *  in-game edit re-resolves the config; null = use the passed [config] / built-in default with no reload. */
    private val configKey: String? = null,
    /** App-UI seam so the controller can open + navigate the QuickMenu / in-game editors. No-op by default. */
    private val uiBridge: ScUiBridge = NoOpScUiBridge,
) {
    companion object {
        private const val TAG = "TritonMapper"
        private const val ACTION_PERMISSION = "app.gamenative.USB_PERMISSION"
        private const val HEARTBEAT_MS = 2000L
        private const val MAX_BLE_RETRIES = 4
        private const val BLE_RETRY_MS = 1500L

        /** The currently-running driver, for the debug live-tune hook ([ScTestReceiver] "tune" mode) to reach the
         *  active interpreter. Set in [start], cleared in [stop]. Null when no game is running. */
        @Volatile var live: TritonMapper? = null
            private set
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usb: TritonUsb? = null
    private var ble: TritonBle? = null
    private var haptics: TritonHaptics? = null
    private var interpreter: ProfileInterpreter? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    private var lastHeartbeat = 0L
    private var permissionReceiver: BroadcastReceiver? = null
    private val bleHandler = Handler(context.mainLooper)
    private var bleRetries = 0

    /** True once a transport is live (USB opened / BLE onReady). The in-game UI gates the rich Steam-Controller
     *  editing section on this — GameNative's generic controller detector can't see the BLE Triton. */
    @Volatile var transportReady = false
        private set

    /**
     * Pick a transport: USB Puck if one is enumerated (the validated path, includes haptics), else fall back
     * to direct BLE (the mobile path — no dongle/no root; haptic output not yet wired). Both feed the same
     * [ProfileInterpreter], so action sets / layers / mode-shift / overlays / keyboard run identically.
     */
    fun start() {
        live = this
        val device = findPuck()
        if (device != null) {
            if (usbManager.hasPermission(device)) openAndRun(device) else requestPermission(device)
        } else {
            Log.i(TAG, "no Puck on USB; trying BLE transport")
            startBle()
        }
    }

    private fun findPuck(): UsbDevice? = usbManager.deviceList.values.firstOrNull {
        it.vendorId == TritonProtocol.VID &&
            (it.productId == TritonProtocol.PID_PUCK || it.productId == TritonProtocol.PID_PUCK_NEREID ||
                it.productId == TritonProtocol.PID_WIRED)
    }

    private fun requestPermission(device: UsbDevice) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_PERMISSION) return
                context.unregisterReceiver(this)
                permissionReceiver = null
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    openAndRun(device)
                } else {
                    Log.w(TAG, "USB permission denied")
                }
            }
        }
        permissionReceiver = receiver
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        }
        usbManager.requestPermission(device, pi)
    }

    private fun openAndRun(device: UsbDevice) {
        val u = TritonUsb(usbManager, device)
        if (!u.open()) return
        u.initAllSlots()
        usb = u
        val h = TritonHaptics { report -> u.writeOutputReport(report) }
        haptics = h
        interpreter = buildInterpreter(h)
        running = true
        transportReady = true
        thread = Thread({ loop() }, "TritonMapper").also { it.start() }
        Log.i(TAG, "started (USB)")
    }

    /**
     * BLE transport: [TritonBle] connects/un-lizards/decodes and pushes [TritonState]s via [onState] (it runs
     * its own lizard heartbeat, so no loop thread here). Haptics route back out over BLE via
     * [TritonBle.writeOutputReport]. BLE direct-connects are flaky (a first attempt can time out with no GATT
     * link), and the link can drop mid-game, so [onError] auto-retries with a fresh [TritonBle] — a successful
     * [onReady] resets the budget.
     */
    private fun startBle() {
        // Route haptics to whichever TritonBle is current (survives reconnects, which swap the [ble] instance).
        val h = TritonHaptics { report -> ble?.writeOutputReport(report) }
        haptics = h
        val interp = buildInterpreter(h)
        interpreter = interp
        running = true
        bleRetries = 0
        connectBle(interp)
    }

    private fun connectBle(interp: ProfileInterpreter) {
        val b = TritonBle(context)
        ble = b
        b.start(
            onState = { state -> if (running) interp.apply(state) },
            onReady = { bleRetries = 0; transportReady = true; Log.i(TAG, "started (BLE) — transport live") },
            onError = { reason ->
                Log.w(TAG, "BLE transport: $reason")
                transportReady = false
                runCatching { b.close() }
                if (ble === b) ble = null
                if (running && bleRetries < MAX_BLE_RETRIES) {
                    bleRetries++
                    Log.i(TAG, "BLE reconnect $bleRetries/$MAX_BLE_RETRIES in ${BLE_RETRY_MS}ms")
                    bleHandler.postDelayed({ if (running && ble == null) connectBle(interp) }, BLE_RETRY_MS)
                } else if (running) {
                    Log.w(TAG, "BLE giving up after $MAX_BLE_RETRIES retries")
                }
            },
        )
    }

    /** Build the interpreter for the active [config] (or [ScProfile.default]); shared by USB + BLE. */
    private fun buildInterpreter(haptics: TritonHaptics?): ProfileInterpreter {
        val cfg = config
        return ProfileInterpreter(
            XServerOutputSink(xServer),
            cfg?.defaultProfile() ?: ScProfile.default(),
            haptics,
            menuOverlay = menuOverlay,
            keyboardOverlay = keyboardOverlay,
            padDeadzone = ScTuningStore.deadzone(context),
            padSmoothing = ScTuningStore.smoothing(context),
            menuCommit = ScTuningStore.menuCommit(context),
            uiBridge = uiBridge,
        ).also {
            if (cfg != null) {
                it.setConfig(cfg)
                Log.i(TAG, "loaded per-game ScConfig: sets=${cfg.sets.keys} default=${cfg.defaultSetId}")
            } else {
                Log.i(TAG, "no per-game config; using ScProfile.default()")
            }
        }
    }

    private fun loop() {
        val u = usb ?: return
        val buf = ByteArray(64)
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastHeartbeat >= HEARTBEAT_MS) { u.heartbeat(); lastHeartbeat = now }
            val n = u.read(buf, 8)
            if (n <= 0) continue
            val state = TritonProtocol.decodeState(buf, n) ?: continue
            interpreter?.apply(state)
        }
    }

    /**
     * Live dial-in of the touchpad-feel knobs (debug only, via [ScTestReceiver]). Persists to [ScTuningStore] so
     * the values stick for the next launch, AND pushes them into the running [interpreter] so the change is felt
     * immediately on the pads — no game relaunch. A null arg leaves that knob at its stored value.
     */
    fun setTuning(deadzone: Int?, smoothing: Int?) {
        deadzone?.let { ScTuningStore.setDeadzone(context, it) }
        smoothing?.let { ScTuningStore.setSmoothing(context, it) }
        val dz = ScTuningStore.deadzone(context)
        val sm = ScTuningStore.smoothing(context)
        interpreter?.setPadTuning(dz, sm)
        Log.i(TAG, "tuning applied: deadzone=$dz smoothing=$sm")
    }

    /** Debug rest-jitter probe: log decoded right-pad X/Y + per-report delta for the next [n] reports (BLE only). */
    fun armPadProbe(n: Int) { ble?.armPadProbe(n) }

    /**
     * Re-resolve this session's config + tuning from the stores and push them into the running interpreter — so
     * an in-game edit (bindings / labels / menu commit / deadzone / smoothing) applies LIVE with no relaunch.
     * Safe to call from any thread (interpreter mutators are @Volatile / atomic field swaps).
     */
    fun reload() {
        val interp = interpreter ?: return
        val cfg = configKey?.let { ScConfigStore.forKey(context, it) }
        if (cfg != null) {
            interp.setConfig(cfg)  // recompute() inside sets the active profile
        } else {
            interp.setConfig(null)
            interp.profile = ScProfile.default()
        }
        interp.setPadTuning(ScTuningStore.deadzone(context), ScTuningStore.smoothing(context))
        interp.setMenuCommit(ScTuningStore.menuCommit(context))
        (menuOverlay as? ScMenuOverlayView)?.refreshLayouts() // pick up in-game per-menu overlay placement edits
        Log.i(TAG, "reloaded (key=$configKey, cfg=${cfg != null}, sets=${cfg?.sets?.keys})")
    }

    fun stop() {
        if (live === this) live = null
        transportReady = false
        running = false
        bleHandler.removeCallbacksAndMessages(null)
        thread?.join(500)
        thread = null
        usb?.close(); usb = null
        ble?.close(); ble = null
        haptics = null
        interpreter = null
        permissionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        permissionReceiver = null
        Log.i(TAG, "stopped")
    }
}
