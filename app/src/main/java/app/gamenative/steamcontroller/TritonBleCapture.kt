package app.gamenative.steamcontroller

import android.content.Context
import android.os.Handler
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures a "golden trace" of raw BLE input reports to a file, so the mapping engine can be developed and
 * unit-tested on the PC by replaying real controller data (docs/AUTOMATION-PLAN.md). Connects over BLE,
 * records every raw report off the input characteristic for [durationMs], and writes a length-prefixed
 * binary file: for each report, one byte length then the report bytes.
 *
 * Trigger via adb (no UI): see [ScTestReceiver]. Pull with `adb pull <path>`.
 */
object TritonBleCapture {
    private const val TAG = "TritonBleCapture"

    @Volatile private var running = false

    fun run(context: Context, durationMs: Long, onDone: (String) -> Unit) {
        if (running) { onDone("capture already running"); return }
        running = true
        val app = context.applicationContext
        val main = Handler(app.mainLooper)
        val done = AtomicBoolean(false)
        val ble = TritonBle(app)
        val frames = ArrayList<ByteArray>()

        fun conclude(msg: String) {
            if (!done.compareAndSet(false, true)) return
            running = false
            runCatching { ble.close() }
            Log.i(TAG, "RESULT: $msg")
            main.post { onDone(msg) }
        }

        ble.onRaw = { bytes -> synchronized(frames) { frames.add(bytes.copyOf()) } }
        ble.start(
            onState = { },
            onReady = {
                Log.i(TAG, "capturing for ${durationMs / 1000}s — exercise every control now")
                main.postDelayed({
                    val file = File(app.getExternalFilesDir(null), "sc_trace_${System.currentTimeMillis()}.bin")
                    val copy = synchronized(frames) { ArrayList(frames) }
                    runCatching {
                        file.outputStream().buffered().use { out ->
                            for (f in copy) {
                                out.write(f.size and 0xFF)
                                out.write(f)
                            }
                        }
                    }.onFailure { conclude("capture write failed: ${it.message}"); return@postDelayed }
                    conclude("captured ${copy.size} reports -> ${file.absolutePath}")
                }, durationMs)
            },
            onError = { reason -> conclude("capture BLE error: $reason") },
        )
    }
}
