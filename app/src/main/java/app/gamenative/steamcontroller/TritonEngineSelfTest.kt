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
import app.gamenative.utils.SteamControllerProfileImporter
import com.winlator.inputcontrols.GamepadState
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode

/**
 * On-hardware smoke test for the **step-3 mapping engine** (action sets / layers / mode-shift), over USB and
 * independent of any Wine container. It loads [SMOKE_CONFIG] via [SteamControllerProfileImporter.importConfig],
 * drives a real [ProfileInterpreter] from the live controller stream, and logs every context transition + output
 * to logcat (tag `TritonEngineTest`) so a human can press buttons and confirm switching works end-to-end.
 *
 * Press map ([SMOKE_CONFIG]):  A/B → keys 1/2 in the base set · hold LB → Overlay layer (A → 8) · tap RB →
 * Combat set (A → 3); tap RB again → back · hold the lower-left back paddle → mode-shift (A → 9).
 *
 * Triggered via `adb shell am broadcast -a app.gamenative.SC_SELFTEST -p app.gamenative.debug --es mode engine`.
 */
object TritonEngineSelfTest {
    private const val TAG = "TritonEngineTest"
    private const val ACTION_PERMISSION = "app.gamenative.USB_PERMISSION"
    private const val RUN_MS = 30000L
    private const val HEARTBEAT_MS = 2000L

    @Volatile private var running = false

    /**
     * A self-contained config exercising all three step-3 context mechanisms (ids: set 0 Default, set 1 Combat,
     * layer 2 Overlay; CHANGE_PRESET/hold_layer are 1-based, mode_shift group id is raw).
     */
    const val SMOKE_CONFIG = """
"controller_mappings"
{
	"version"		"3"
	"title"		"Engine Smoke Test"
	"controller_type"		"controller_triton"
	"actions" { "Default" { "title" "Default" "legacy_set" "1" } "Combat" { "title" "Combat" "legacy_set" "1" } }
	"action_layers" { "Overlay" { "title" "Overlay" "legacy_set" "1" "set_layer" "1" "parent_set_name" "Default" } }

	"group" { "id" "0" "mode" "four_buttons" "inputs" {
		"button_a" { "activators" { "Full_Press" { "bindings" { "binding" "key_press 1" } } } }
		"button_b" { "activators" { "Full_Press" { "bindings" { "binding" "key_press 2" } } } } } }
	"group" { "id" "1" "mode" "four_buttons" "inputs" {
		"button_a" { "activators" { "Full_Press" { "bindings" { "binding" "key_press 9" } } } } } }
	"group" { "id" "2" "mode" "switches" "inputs" {
		"left_bumper"      { "activators" { "Full_Press" { "bindings" { "binding" "controller_action hold_layer 3 0 0" } } } }
		"right_bumper"     { "activators" { "Full_Press" { "bindings" { "binding" "controller_action CHANGE_PRESET 2 0 0" } } } }
		"button_back_left" { "activators" { "Full_Press" { "bindings" { "binding" "mode_shift button_diamond 1" } } } } } }
	"group" { "id" "3" "mode" "four_buttons" "inputs" {
		"button_a" { "activators" { "Full_Press" { "bindings" { "binding" "key_press 3" } } } } } }
	"group" { "id" "4" "mode" "switches" "inputs" {
		"right_bumper" { "activators" { "Full_Press" { "bindings" { "binding" "controller_action CHANGE_PRESET 1 0 0" } } } } } }
	"group" { "id" "5" "mode" "four_buttons" "inputs" {
		"button_a" { "activators" { "Full_Press" { "bindings" { "binding" "key_press 8" } } } } } }

	"preset" { "id" "0" "name" "Default" "group_source_bindings" {
		"0" "button_diamond active" "2" "switch active" "1" "button_diamond active modeshift" } }
	"preset" { "id" "1" "name" "Combat" "group_source_bindings" {
		"3" "button_diamond active" "4" "switch active" } }
	"preset" { "id" "2" "name" "Overlay" "group_source_bindings" {
		"5" "button_diamond active" } }
}
"""

    /**
     * Run the engine smoke test. With [configKey] null it uses the embedded [SMOKE_CONFIG]; with a key it loads
     * that key's config from [ScConfigStore] (round-trips the real per-game live-path source on hardware).
     */
    fun run(context: Context, configKey: String? = null, onResult: (String) -> Unit) {
        if (running) { onResult("Engine self-test already running"); return }
        val cfg: ScConfig = if (configKey != null) {
            ScConfigStore.forKey(context, configKey)
                ?: run { onResult("No stored config for key '$configKey' (install one first)."); return }
        } else {
            SteamControllerProfileImporter.importConfig(SMOKE_CONFIG)
        }
        val src = if (configKey != null) "store key '$configKey'" else "embedded SMOKE_CONFIG"
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == TritonProtocol.VID &&
                (it.productId == TritonProtocol.PID_PUCK || it.productId == TritonProtocol.PID_PUCK_NEREID ||
                    it.productId == TritonProtocol.PID_WIRED)
        }
        if (device == null) { onResult("No Puck on USB (VID 0x28DE). Plug the controller and retry."); return }
        if (usbManager.hasPermission(device)) probe(context, usbManager, device, cfg, src, onResult)
        else requestPermission(context, usbManager, device, cfg, src, onResult)
    }

    private fun requestPermission(context: Context, usbManager: UsbManager, device: UsbDevice, cfg: ScConfig, src: String, onResult: (String) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_PERMISSION) return
                runCatching { context.unregisterReceiver(this) }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) probe(context, usbManager, device, cfg, src, onResult)
                else onResult("USB permission denied — grant it to run the engine test.")
            }
        }
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

    private fun probe(context: Context, usbManager: UsbManager, device: UsbDevice, cfg: ScConfig, src: String, onResult: (String) -> Unit) {
        running = true
        Thread({
            val result = try {
                runEngine(usbManager, device, cfg, src)
            } catch (e: Exception) {
                "Engine self-test EXCEPTION: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                running = false
            }
            Log.i(TAG, "RESULT: $result")
            Handler(context.mainLooper).post { onResult(result) }
        }, "TritonEngineTest").start()
    }

    private fun runEngine(usbManager: UsbManager, device: UsbDevice, cfg: ScConfig, src: String): String {
        val usb = TritonUsb(usbManager, device)
        if (!usb.open()) return "open()/claimInterface FAILED — can't reach the controller (no root path)."
        usb.initAllSlots()
        val haptics = TritonHaptics { report -> usb.writeOutputReport(report) }
        val sink = LoggingSink()
        val interp = ProfileInterpreter(sink, cfg.defaultProfile(), haptics)
        interp.setConfig(cfg)
        Log.i(TAG, "config from $src: sets=${cfg.sets.keys} default=${cfg.defaultSetId} " +
            "shiftOverlays=${cfg.shiftOverlays.keys}; press A/B, hold LB, tap RB, hold lower-left paddle.")

        val buf = ByteArray(64)
        var reports = 0
        var lastSet = interp.activeSetId
        var lastHeartbeat = 0L
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < RUN_MS) {
            val now = System.currentTimeMillis()
            if (now - lastHeartbeat >= HEARTBEAT_MS) { usb.heartbeat(); lastHeartbeat = now }
            val n = usb.read(buf, 8)
            if (n <= 0) continue
            val state = TritonProtocol.decodeState(buf, n) ?: continue
            reports++
            interp.apply(state)
            if (interp.activeSetId != lastSet) {
                Log.i(TAG, "==> ACTIVE SET -> ${interp.activeSetId}")
                lastSet = interp.activeSetId
            }
        }
        usb.close()
        return if (reports == 0) "Claimed OK but NO reports in ${RUN_MS / 1000}s — stream not flowing."
        else "Engine test done: $reports reports, sink saw ${sink.summary()}. Check the KEY/SET log lines above."
    }

    /** Logs interpreter outputs to logcat (no injection) so on-hardware switching is observable. */
    private class LoggingSink : ScOutputSink {
        private var lastPad = 0
        private val keysSeen = LinkedHashSet<String>()
        override fun gamepad(state: GamepadState) {
            val b = state.buttons.toInt() and 0xFFFF
            if (b != lastPad) { Log.i(TAG, "PAD buttons=0x${Integer.toHexString(b)}"); lastPad = b }
        }
        override fun mouseMove(dx: Int, dy: Int) {}
        override fun mouseMoveAbs(nx: Float, ny: Float) {}
        override fun mouseButton(button: Pointer.Button, pressed: Boolean) {
            if (pressed) Log.i(TAG, "MOUSE $button down")
        }
        override fun key(key: XKeycode, pressed: Boolean) {
            if (pressed) { Log.i(TAG, "KEY $key down"); keysSeen.add(key.name) }
        }
        fun summary() = "keys=$keysSeen"
    }
}
