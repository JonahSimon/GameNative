package app.gamenative.steamcontroller

import android.content.Context
import android.os.Handler
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone BLE self-test for the Steam Controller — the Track-T Phase-A spike (no container, no Puck).
 * It answers the one question that decides the BLE pivot: does Android connect to the controller's GATT
 * service and stream the full report, and **at what rate / jitter**? Reports ONE confound-free outcome
 * (every failure mode + "nothing happened" is a distinct message). Caller must already hold BLE perms.
 *
 * Wired to Settings → Debug → "Test Steam Controller (BLE)".
 */
object TritonBleSelfTest {
    private const val TAG = "TritonBleSelfTest"
    private const val MEASURE_MS = 6000L   // streaming window once notifications are live
    private const val OVERALL_TIMEOUT_MS = 14000L

    @Volatile private var running = false

    fun run(context: Context, onResult: (String) -> Unit) {
        if (running) { onResult("BLE self-test already running"); return }
        running = true
        val main = Handler(context.mainLooper)
        val concluded = AtomicBoolean(false)
        val ble = TritonBle(context)

        var reports = 0
        var seenButtons = 0
        var lastState: TritonState? = null
        var firstReportNanos = 0L
        var lastReportNanos = 0L
        var maxGapMs = 0.0

        fun conclude(msg: String) {
            if (!concluded.compareAndSet(false, true)) return
            running = false
            runCatching { ble.close() }
            Log.i(TAG, "RESULT: $msg")
            main.post { onResult(msg) }
        }

        // Hard timeout so we never hang silently.
        main.postDelayed({
            conclude("BLE self-test timed out with no result (no connect/notify within ${OVERALL_TIMEOUT_MS / 1000}s).")
        }, OVERALL_TIMEOUT_MS)

        ble.start(
            onState = { s ->
                val now = System.nanoTime()
                if (reports == 0) firstReportNanos = now
                else {
                    val gapMs = (now - lastReportNanos) / 1_000_000.0
                    if (gapMs > maxGapMs) maxGapMs = gapMs
                }
                lastReportNanos = now
                reports++
                seenButtons = seenButtons or s.buttons
                lastState = s
                if (reports == 1 || reports % 100 == 0) {
                    Log.i(TAG, "report#$reports buttons=0x${Integer.toHexString(s.buttons)} " +
                        "LX=${s.leftStickX} RX=${s.rightStickX} tR=${s.triggerRight} gyroZ=${s.gyroZ} accelZ=${s.accelZ}")
                }
            },
            onReady = {
                Log.i(TAG, "notifications live — measuring for ${MEASURE_MS / 1000}s")
                main.postDelayed({
                    val sample = lastState
                    if (reports == 0) {
                        conclude("Connected + notifications enabled, but NO reports arrived in ${MEASURE_MS / 1000}s.")
                    } else {
                        val spanMs = (lastReportNanos - firstReportNanos) / 1_000_000.0
                        val hz = if (spanMs > 0) (reports - 1) * 1000.0 / spanMs else 0.0
                        val imu = sample?.let { "accelZ=${it.accelZ} gyroZ=${it.gyroZ}" } ?: "n/a"
                        conclude(
                            "LIVE ✓ BLE — ${"%.0f".format(hz)} Hz over $reports reports, " +
                                "max gap ${"%.0f".format(maxGapMs)} ms, buttons-seen=0x${Integer.toHexString(seenButtons)}, " +
                                "IMU $imu. (Press buttons during the test to light up bits.)",
                        )
                    }
                }, MEASURE_MS)
            },
            onError = { reason -> conclude("BLE: $reason") },
        )
    }
}
