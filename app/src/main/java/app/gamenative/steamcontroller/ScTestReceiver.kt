package app.gamenative.steamcontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb-triggerable hook for the Steam Controller self-tests / trace capture — runs them with NO UI tap, for
 * the automation harness (docs/AUTOMATION-PLAN.md). Declared only in debug builds
 * (src/debug/AndroidManifest.xml), so it never ships in release.
 *
 * Launch the app first (so the process is alive), then:
 *   adb shell am broadcast -a app.gamenative.SC_SELFTEST -p app.gamenative --es mode ble
 *   adb shell am broadcast -a app.gamenative.SC_SELFTEST -p app.gamenative --es mode bleengine
 *   adb shell am broadcast -a app.gamenative.SC_SELFTEST -p app.gamenative --es mode dump --ei secs 12
 *
 * Per-game config store round-trip (verifies the live-path config source on hardware):
 *   adb shell am broadcast -a app.gamenative.SC_SELFTEST -p app.gamenative.debug --es mode installsmoke --es key demo
 *   adb shell am broadcast -a app.gamenative.SC_SELFTEST -p app.gamenative.debug --es mode bleengine --es key demo
 *
 * Results go to logcat (tags TritonBleSelfTest / TritonBleCapture / TritonBleEngineTest / ScConfigStore).
 */
class ScTestReceiver : BroadcastReceiver() {
    companion object { private const val TAG = "ScTestReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        val mode = intent.getStringExtra("mode") ?: "ble"
        Log.i(TAG, "triggered mode=$mode")
        val app = context.applicationContext
        val key = intent.getStringExtra("key")
        when (mode) {
            "ble" -> TritonBleSelfTest.run(app) { Log.i(TAG, "ble result: $it") }
            "bleengine" -> TritonBleEngineSelfTest.run(app, key) { Log.i(TAG, "bleengine result: $it") }
            "installsmoke" -> {
                val k = key ?: "demo"
                val ok = ScConfigStore.saveVdf(app, k, TritonBleEngineSelfTest.SMOKE_CONFIG)
                val readback = ScConfigStore.forKey(app, k)
                Log.i(TAG, "installsmoke key='$k' saved=$ok -> ${ScConfigStore.fileFor(app, k)} ; " +
                    "readback sets=${readback?.sets?.keys} default=${readback?.defaultSetId}")
            }
            "peek" -> {
                val k = key ?: "demo"
                val cfg = ScConfigStore.forKey(app, k)
                val aOut = cfg?.defaultProfile()?.buttons?.get(ScSource.A.bit)?.output
                Log.i(TAG, "peek key='$k' -> '${cfg?.defaultProfile()?.name}' sets=${cfg?.sets?.keys} A=$aOut")
            }
            "dump" -> {
                val secs = intent.getIntExtra("secs", 8)
                TritonBleCapture.run(app, secs * 1000L) { Log.i(TAG, "dump result: $it") }
            }
            "tune" -> {
                // Live touchpad-feel dial-in: push deadzone/smoothing into the running driver (no relaunch).
                //   adb shell am broadcast -a app.gamenative.SC_SELFTEST -p app.gamenative --es mode tune \
                //       --ei deadzone 40 --ei smoothing 30
                // Omit an extra to leave that knob unchanged. With no game running it just persists for next launch.
                val dz = if (intent.hasExtra("deadzone")) intent.getIntExtra("deadzone", ScTuningStore.DEFAULT_DEADZONE) else null
                val sm = if (intent.hasExtra("smoothing")) intent.getIntExtra("smoothing", ScTuningStore.DEFAULT_SMOOTHING) else null
                val mapper = TritonMapper.live
                if (mapper != null) {
                    mapper.setTuning(dz, sm)
                } else {
                    dz?.let { ScTuningStore.setDeadzone(app, it) }
                    sm?.let { ScTuningStore.setSmoothing(app, it) }
                }
                Log.i(TAG, "tune deadzone=$dz smoothing=$sm live=${mapper != null} -> " +
                    "now deadzone=${ScTuningStore.deadzone(app)} smoothing=${ScTuningStore.smoothing(app)}")
            }
            "reload" -> {
                // Verify live config/tuning reload (Phase 1 of in-game live editing): re-resolves forKey + tuning
                // into the running interpreter with no relaunch.  adb ... --es mode reload
                val mapper = TritonMapper.live
                mapper?.reload()
                Log.i(TAG, "reload requested; live=${mapper != null} transportReady=${mapper?.transportReady}")
            }
            "probe" -> {
                // Rest-jitter measurement: log decoded right-pad X/Y + per-report delta for N reports (default 150),
                // while the user rests a still finger on the right pad. Tag: ScPadProbe (under TritonBle).
                //   adb shell am broadcast -a app.gamenative.SC_SELFTEST -p app.gamenative --es mode probe --ei n 150
                val n = intent.getIntExtra("n", 150)
                val mapper = TritonMapper.live
                mapper?.armPadProbe(n)
                Log.i(TAG, "probe armed n=$n live=${mapper != null}")
            }
            else -> Log.w(TAG, "unknown mode=$mode (use ble|bleengine|installsmoke|dump|tune|probe|reload)")
        }
    }
}
