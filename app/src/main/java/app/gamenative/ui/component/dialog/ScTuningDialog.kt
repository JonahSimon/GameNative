package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.steamcontroller.ScTuningStore
import kotlin.math.roundToInt

/**
 * In-game touchpad/menu tuning: deadzone, smoothing, and touch-menu commit style. Persists to [ScTuningStore]
 * and calls [onApply] after each change so the running driver ([app.gamenative.steamcontroller.TritonMapper])
 * re-reads them live — no relaunch. Used from the QuickMenu CONTROLLER tab when a Steam Controller is connected.
 */
@Composable
fun ScTuningDialog(onApply: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var deadzone by remember { mutableIntStateOf(ScTuningStore.deadzone(context)) }
    var smoothing by remember { mutableIntStateOf(ScTuningStore.smoothing(context)) }
    var menuCommit by remember { mutableIntStateOf(ScTuningStore.menuCommit(context)) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).padding(8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            val nav = remember { ScNavState() }
            // D-pad LEFT/RIGHT nudge each slider 1 point at a time (hold to auto-repeat), persisting + applying live.
            val nudgeDeadzone: (Int) -> Unit = { d ->
                deadzone = (deadzone + d).coerceIn(ScTuningStore.MIN_DEADZONE, ScTuningStore.MAX_DEADZONE)
                ScTuningStore.setDeadzone(context, deadzone); onApply()
            }
            val nudgeSmoothing: (Int) -> Unit = { d ->
                smoothing = (smoothing + d).coerceIn(ScTuningStore.MIN_SMOOTHING, ScTuningStore.MAX_SMOOTHING)
                ScTuningStore.setSmoothing(context, smoothing); onApply()
            }
            ScNavDialogColumn(nav, onBack = onDismiss, modifier = Modifier.padding(16.dp)) {
                Text("Touchpad & menus", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Changes apply to the running game immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                HorizontalDivider()

                ScNavItem(nav, line = 0, modifier = Modifier.fillMaxWidth(), onHorizontal = nudgeDeadzone, onActivate = {}) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text("Touchpad deadzone: $deadzone   ◀ ▶")
                        Slider(
                            value = deadzone.toFloat(),
                            onValueChange = { deadzone = it.roundToInt().coerceIn(ScTuningStore.MIN_DEADZONE, ScTuningStore.MAX_DEADZONE) },
                            onValueChangeFinished = { ScTuningStore.setDeadzone(context, deadzone); onApply() },
                            valueRange = ScTuningStore.MIN_DEADZONE.toFloat()..ScTuningStore.MAX_DEADZONE.toFloat(),
                        )
                        Text("Higher = steadier cursor at rest.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                ScNavItem(nav, line = 1, modifier = Modifier.fillMaxWidth(), onHorizontal = nudgeSmoothing, onActivate = {}) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text("Touchpad smoothing: $smoothing%   ◀ ▶")
                        Slider(
                            value = smoothing.toFloat(),
                            onValueChange = { smoothing = it.roundToInt().coerceIn(ScTuningStore.MIN_SMOOTHING, ScTuningStore.MAX_SMOOTHING) },
                            onValueChangeFinished = { ScTuningStore.setSmoothing(context, smoothing); onApply() },
                            valueRange = ScTuningStore.MIN_SMOOTHING.toFloat()..ScTuningStore.MAX_SMOOTHING.toFloat(),
                        )
                        Text("Higher = smoother motion + keyboard cursor, but more lag.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Touch-menu commit")
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val options = listOf(
                            ScTuningStore.MENU_COMMIT_IMPORTED to "Default",
                            ScTuningStore.MENU_COMMIT_CLICK to "Click",
                            ScTuningStore.MENU_COMMIT_RELEASE to "Release",
                        )
                        options.forEachIndexed { i, (value, label) ->
                            val pick = { menuCommit = value; ScTuningStore.setMenuCommit(context, value); onApply() }
                            ScNavItem(nav, line = 2, col = i, onActivate = pick) {
                                FilterChip(selected = menuCommit == value, onClick = pick, label = { Text(label) })
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    ScNavItem(nav, line = 3, onActivate = onDismiss) {
                        Button(onClick = onDismiss) { Text("Done") }
                    }
                }
            }
        }
    }
}
