package app.gamenative.steamcontroller

import android.content.Context
import android.os.Handler
import android.util.Log
import app.gamenative.utils.SteamControllerProfileImporter
import com.winlator.inputcontrols.GamepadState
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-hardware smoke test for the **step-3 mapping engine over BLE**. It loads a config, drives a real [ProfileInterpreter] from the live
 * [TritonBle] stream, and logs every context transition + output to logcat (tag `TritonBleEngineTest`), with
 * NO Wine container and NO injection. That isolates the **BLE→interpreter bridge** from the in-game
 * injection/overlay layer: if KEY/SET lines log here, the BLE engine path works and any in-game failure is
 * downstream (injection/overlay), not transport. Caller must already hold BLE perms.
 *
 * Press map (embedded [SMOKE_CONFIG]): A/B → keys 1/2 · hold LB → Overlay layer (A → 8) ·
 * tap RB → Combat set (A → 3); tap RB again → back · hold the lower-left back paddle → mode-shift (A → 9).
 *
 * Triggered via `adb shell am broadcast -a app.gamenative.SC_SELFTEST -p app.gamenative --es mode bleengine`
 * (optionally `--es key <storedConfigKey>` to drive a stored per-game config instead).
 */
object TritonBleEngineSelfTest {
    private const val TAG = "TritonBleEngineTest"
    private const val RUN_MS = 30000L

    @Volatile private var running = false

    /** Mode-shift/layer/preset config used by this self-test + by importer/config-store unit tests. Press map:
     *  A/B → keys 1/2 in the base set · hold LB → Overlay layer (A → 8) · tap RB → Combat preset (A → 3). */
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

    fun run(context: Context, configKey: String? = null, onResult: (String) -> Unit) {
        if (running) { onResult("BLE engine self-test already running"); return }
        val cfg: ScConfig = if (configKey != null) {
            ScConfigStore.forKey(context, configKey)
                ?: run { onResult("No stored config for key '$configKey' (install one first)."); return }
        } else {
            SteamControllerProfileImporter.importConfig(SMOKE_CONFIG)
        }
        val src = if (configKey != null) "store key '$configKey'" else "embedded SMOKE_CONFIG"
        running = true
        val main = Handler(context.mainLooper)
        val concluded = AtomicBoolean(false)
        val ble = TritonBle(context)

        val sink = LoggingSink()
        // No haptic output path over BLE yet → null haptics (the engine logic under test is unaffected).
        val interp = ProfileInterpreter(sink, cfg.defaultProfile(), null)
        interp.setConfig(cfg)
        var reports = 0
        var lastSet = interp.activeSetId

        fun conclude(msg: String) {
            if (!concluded.compareAndSet(false, true)) return
            running = false
            runCatching { ble.close() }
            Log.i(TAG, "RESULT: $msg")
            main.post { onResult(msg) }
        }

        Log.i(TAG, "config from $src: sets=${cfg.sets.keys} default=${cfg.defaultSetId} " +
            "shiftOverlays=${cfg.shiftOverlays.keys}; press A/B, hold LB, tap RB, hold lower-left paddle.")

        main.postDelayed({
            conclude(
                if (reports == 0) "Connected? but NO reports in ${RUN_MS / 1000}s — BLE stream not flowing."
                else "BLE engine test done: $reports reports, sink saw ${sink.summary()}. " +
                    "Check the KEY/SET log lines above.",
            )
        }, RUN_MS)

        ble.start(
            onState = { s ->
                reports++
                interp.apply(s)
                if (interp.activeSetId != lastSet) {
                    Log.i(TAG, "==> ACTIVE SET -> ${interp.activeSetId}")
                    lastSet = interp.activeSetId
                }
            },
            onReady = { Log.i(TAG, "BLE notifications live — press buttons now (${RUN_MS / 1000}s window)") },
            onError = { reason -> conclude("BLE: $reason") },
        )
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
            // Log BOTH edges (logcat -v time gives the timing) so press/release patterns are visible — e.g.
            // diagnosing a d-pad direction that chatters or never releases vs a clean single tap.
            Log.i(TAG, "KEY $key ${if (pressed) "DOWN" else "up"}")
            if (pressed) keysSeen.add(key.name)
        }
        fun summary() = "keys=$keysSeen"
    }
}
