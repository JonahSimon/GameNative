package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.steamcontroller.ScKeyboardOverlayView
import app.gamenative.steamcontroller.ScKeyboardSpec
import app.gamenative.steamcontroller.ScMenuOverlayView
import app.gamenative.steamcontroller.ScMenuSpec
import app.gamenative.steamcontroller.ScOverlayLayout
import app.gamenative.steamcontroller.ScOverlayStore
import app.gamenative.ui.util.SnackbarManager

/** Which overlay the editor is placing/sizing. */
enum class ScOverlayTarget { MENU, KEYBOARD }

/**
 * Drag + pinch live editor for an overlay's placement/size ([ScOverlayLayout], persisted by [ScOverlayStore]).
 * Shows the real overlay view over a neutral backdrop so the user sees exactly what they'll get in-game:
 * one-finger drag repositions, pinch scales. [target] picks the menu HUD ([ScMenuOverlayView]) or the split
 * keyboard ([ScKeyboardOverlayView]) — each has its own store namespace. [storeKey] is the game's container/appId
 * for a per-game override, or [ScOverlayStore.DEFAULT_KEY] when [isShared] (the global default).
 */
@Composable
fun ScOverlayEditorDialog(
    storeKey: String,
    isShared: Boolean,
    target: ScOverlayTarget,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val keyboard = target == ScOverlayTarget.KEYBOARD
    var layout by remember {
        mutableStateOf(
            if (keyboard) ScOverlayStore.forKeyboard(context, storeKey)
            else ScOverlayStore.forKey(context, storeKey),
        )
    }
    var radialPreview by remember { mutableStateOf(true) } // MENU only: radial vs grid sample
    val menuView = remember { if (keyboard) null else ScMenuOverlayView(context) }
    val kbView = remember { if (keyboard) ScKeyboardOverlayView(context) else null }

    // Push the current layout + a sample into the real overlay view whenever either changes.
    LaunchedEffect(layout, radialPreview) {
        if (keyboard) {
            kbView!!.setLayout(layout)
            kbView.show(ScKeyboardSpec(leftCursor = 7, rightCursor = 2, shift = false))
        } else {
            menuView!!.setLayout(layout)
            menuView.showMenu(sampleSpec(radialPreview))
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f))) {
            ScNavDialogCapture(onBack = onDismiss)  // (drag/pinch is touch-only; this lets the controller close it)
            AndroidView(
                factory = { (menuView ?: kbView)!! },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            layout = layout.copy(
                                scale = layout.scale * zoom,
                                cx = layout.cx + if (w > 0f) pan.x / w else 0f,
                                cy = layout.cy + if (h > 0f) pan.y / h else 0f,
                            ).clamped()
                        }
                    },
            )

            Text(
                text = "Drag to move • pinch to resize",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            )

            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                if (!keyboard) {
                    OutlinedButton(onClick = { radialPreview = !radialPreview }) {
                        Text(if (radialPreview) "Preview: Radial" else "Preview: Grid")
                    }
                }
                OutlinedButton(onClick = {
                    layout = if (keyboard) ScOverlayStore.KEYBOARD_DEFAULT else ScOverlayLayout()
                }) { Text("Reset") }
                if (!isShared) {
                    OutlinedButton(onClick = {
                        if (keyboard) ScOverlayStore.clearKeyboard(context, storeKey)
                        else ScOverlayStore.clear(context, storeKey)
                        SnackbarManager.show("Using global default")
                        onDismiss()
                    }) { Text("Use global") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = {
                    if (keyboard) ScOverlayStore.saveKeyboard(context, storeKey, layout)
                    else ScOverlayStore.save(context, storeKey, layout)
                    SnackbarManager.show("Overlay layout saved")
                    onDismiss()
                }) { Text("Save") }
            }
        }
    }
}

/** A representative 8-slot menu so the user can judge size/position for both HUD kinds. */
private fun sampleSpec(radial: Boolean): ScMenuSpec =
    if (radial) {
        ScMenuSpec(ScMenuSpec.Kind.RADIAL, listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖"), 0, 0, 0)
    } else {
        ScMenuSpec(ScMenuSpec.Kind.GRID, listOf("1", "2", "3", "4", "5", "6", "7", "8"), 4, 2, 0)
    }
