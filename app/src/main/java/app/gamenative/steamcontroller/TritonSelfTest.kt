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

/**
 * Standalone USB-layer self-test for the Steam Controller Puck, independent of any Wine container.
 *
 * It isolates the one real Android-side unknown (debugging playbook #1: classify the layer): can we win
 * the controller interface away from the kernel HID driver via claimInterface(force), and then stream
 * decoded 0x45 reports? Runs the real [TritonUsb] transport for a few seconds and reports a SINGLE,
 * unambiguous outcome. Every failure mode and "nothing happened" produce a DISTINCT message (playbook #3:
 * confound-free), so a silent no-op can never masquerade as success.
 *
 * Wired to a button in Settings → Debug; no XServer / injection involved.
 */
object TritonSelfTest {
    private const val TAG = "TritonSelfTest"
    private const val ACTION_PERMISSION = "app.gamenative.USB_PERMISSION"
    private const val PROBE_MS = 6000L
    private const val HEARTBEAT_MS = 2000L

    @Volatile private var running = false

    fun run(context: Context, onResult: (String) -> Unit) {
        if (running) { onResult("Puck self-test already running"); return }
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == TritonProtocol.VID &&
                (it.productId == TritonProtocol.PID_PUCK || it.productId == TritonProtocol.PID_PUCK_NEREID ||
                    it.productId == TritonProtocol.PID_WIRED)
        }
        if (device == null) {
            onResult("No Puck on USB (looked for VID 0x28DE). Plug the dongle and retry."); return
        }
        Log.i(TAG, "found Puck: ${device.deviceName} VID=${device.vendorId} PID=${device.productId} ifaces=${device.interfaceCount}")
        if (usbManager.hasPermission(device)) {
            probe(context, usbManager, device, onResult)
        } else {
            requestPermission(context, usbManager, device, onResult)
        }
    }

    private fun requestPermission(context: Context, usbManager: UsbManager, device: UsbDevice, onResult: (String) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_PERMISSION) return
                runCatching { context.unregisterReceiver(this) }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    probe(context, usbManager, device, onResult)
                } else {
                    onResult("USB permission denied — grant it to run the self-test.")
                }
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

    private fun probe(context: Context, usbManager: UsbManager, device: UsbDevice, onResult: (String) -> Unit) {
        running = true
        Thread({
            val result = try {
                runProbe(usbManager, device)
            } catch (e: Exception) {
                "Puck self-test EXCEPTION: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                running = false
            }
            Log.i(TAG, "RESULT: $result")
            Handler(context.mainLooper).post { onResult(result) }
        }, "TritonSelfTest").start()
    }

    private fun runProbe(usbManager: UsbManager, device: UsbDevice): String {
        val usb = TritonUsb(usbManager, device)
        if (!usb.open()) {
            return "open()/claimInterface FAILED — kernel won't release the controller interface (no input possible without root)."
        }
        usb.initAllSlots()
        val haptics = TritonHaptics { report -> usb.writeOutputReport(report) }
        val hapticCfg = HapticSettings()
        val buf = ByteArray(64)
        var reports = 0
        var seenButtons = 0
        var liveType = -1
        var prevButtons = 0
        var sentinelFired = false
        var hapticWriteOk = 0       // count of haptic bulk-OUT writes the USB stack accepted (>= 0 bytes)
        var lastHeartbeat = 0L
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < PROBE_MS) {
            val now = System.currentTimeMillis()
            if (now - lastHeartbeat >= HEARTBEAT_MS) { usb.heartbeat(); lastHeartbeat = now }
            val n = usb.read(buf, 8)
            if (n <= 0) continue
            val type = buf[0].toInt() and 0xFF
            val state = TritonProtocol.decodeState(buf, n) ?: continue
            reports++
            seenButtons = seenButtons or state.buttons
            liveType = type
            // SENTINEL (playbook #4): once a slot is live, fire unmistakable click pulses on both pads so
            // the bulk-OUT haptic path is proven even if the user never slides a finger. Confirm the USB
            // stack accepted the write (>= 0) rather than inferring from feel alone.
            if (!sentinelFired) {
                sentinelFired = true
                val rl = usb.writeOutputReport(byteArrayOf(0x82.toByte(), 0, TritonHaptics.CMD_CLICK.toByte(), 0xFE.toByte()))
                val rr = usb.writeOutputReport(byteArrayOf(0x82.toByte(), 1, TritonHaptics.CMD_CLICK.toByte(), 0xFE.toByte()))
                if (rl >= 0) hapticWriteOk++
                if (rr >= 0) hapticWriteOk++
                Log.i(TAG, "sentinel haptic clicks: leftWrite=$rl rightWrite=$rr")
            }
            // Regenerate the live trackpad feel (clicks on press, detents on slide).
            haptics.update(state, prevButtons, hapticCfg)
            prevButtons = state.buttons
            if (reports == 1 || reports % 100 == 0) {
                Log.i(TAG, "report#$reports type=0x${type.toString(16)} buttons=0x${state.buttons.toString(16)} " +
                    "LX=${state.leftStickX} LY=${state.leftStickY} RX=${state.rightStickX} RY=${state.rightStickY} " +
                    "tL=${state.triggerLeft} tR=${state.triggerRight} gyroZ=${state.gyroZ} accelZ=${state.accelZ}")
            }
        }
        usb.close()
        return if (reports == 0) {
            "Claimed interface(s) OK, but NO controller-state reports in ${PROBE_MS / 1000}s — lizard-off / stream not flowing."
        } else {
            "LIVE ✓ type=0x${liveType.toString(16)}, $reports reports in ${PROBE_MS / 1000}s, " +
                "buttons-seen=0x${Integer.toHexString(seenButtons)}, haptic-writes-ok=$hapticWriteOk/2. " +
                "(You should feel 2 click pulses at start + detents while sliding the pads.)"
        }
    }
}
