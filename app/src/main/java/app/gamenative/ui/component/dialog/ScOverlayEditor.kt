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
import app.gamenative.steamcontroller.ScConfigStore
import app.gamenative.steamcontroller.ScKeyboardOverlayView
import app.gamenative.steamcontroller.ScKeyboardSpec
import app.gamenative.steamcontroller.ScMenuLabelTool
import app.gamenative.steamcontroller.ScMenuLocation
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
    // MENU only: the menus actually present in this game's config (one entry per host surface). Lets the user
    // place each menu independently; null selection = the whole-HUD placement (the pre-per-menu behavior).
    val presentMenus = remember(storeKey) {
        if (keyboard || isShared) emptyList()
        else ScConfigStore.rawConfig(context, storeKey)?.let { ScMenuLabelTool.enumerate(it) }?.map { it.location }?.distinct() ?: emptyList()
    }
    var selectedMenu by remember { mutableStateOf<ScMenuLocation?>(null) } // null = All menus (whole HUD)
    fun stored(sel: ScMenuLocation?): ScOverlayLayout = when {
        keyboard -> ScOverlayStore.forKeyboard(context, storeKey)
        sel != null -> ScOverlayStore.forMenu(context, storeKey, sel.name)
        else -> ScOverlayStore.forKey(context, storeKey)
    }
    var layout by remember { mutableStateOf(stored(null)) }
    // Reload the layout when the selected menu changes (so each menu edits its own stored placement).
    LaunchedEffect(selectedMenu) { layout = stored(selectedMenu) }
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
                text = "Drag / d-pad to move • pinch / ◀ ▶ to resize",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            )

            // Controller controls: d-pad up/down picks a row, left/right nudges position/size; the button row is
            // navigable too. (Touch drag/pinch + the SC pad-cursor still drive the overlay directly above.)
            val nav = remember { ScNavState() }
            val nudgeX: (Int) -> Unit = { d -> layout = layout.copy(cx = layout.cx + d * 0.02f).clamped() }
            val nudgeY: (Int) -> Unit = { d -> layout = layout.copy(cy = layout.cy + d * 0.02f).clamped() }
            val nudgeScale: (Int) -> Unit = { d -> layout = layout.copy(scale = layout.scale * if (d > 0) 1.05f else 1f / 1.05f).clamped() }
            ScNavDialogColumn(
                nav,
                onBack = onDismiss,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f)).padding(12.dp),
            ) {
                // Menu selector (per-menu placement): cycle All menus ↔ each menu present in this game's config.
                if (presentMenus.isNotEmpty()) {
                    val options = listOf<ScMenuLocation?>(null) + presentMenus
                    val cycleMenu: (Int) -> Unit = { d ->
                        val i = options.indexOf(selectedMenu).coerceAtLeast(0)
                        selectedMenu = options[((i + d) % options.size + options.size) % options.size]
                    }
                    ScNavItem(nav, line = 0, modifier = Modifier.fillMaxWidth(), onHorizontal = cycleMenu, onActivate = {}) {
                        Text(
                            "Placing:  ${selectedMenu?.label ?: "All menus"}   ◀ ▶",
                            color = Color.White,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                ScNavItem(nav, line = 1, modifier = Modifier.fillMaxWidth(), onHorizontal = nudgeX, onActivate = {}) {
                    Text("Horizontal position   ◀ ▶", color = Color.White, modifier = Modifier.padding(vertical = 4.dp))
                }
                ScNavItem(nav, line = 2, modifier = Modifier.fillMaxWidth(), onHorizontal = nudgeY, onActivate = {}) {
                    Text("Vertical position   ◀ up  ▶ down", color = Color.White, modifier = Modifier.padding(vertical = 4.dp))
                }
                ScNavItem(nav, line = 3, modifier = Modifier.fillMaxWidth(), onHorizontal = nudgeScale, onActivate = {}) {
                    Text("Size   ◀ smaller  ▶ bigger", color = Color.White, modifier = Modifier.padding(vertical = 4.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    var col = 0
                    if (!keyboard) {
                        val togglePreview = { radialPreview = !radialPreview }
                        ScNavItem(nav, line = 10, col = col++, onActivate = togglePreview) {
                            OutlinedButton(onClick = togglePreview) {
                                Text(if (radialPreview) "Preview: Radial" else "Preview: Grid")
                            }
                        }
                    }
                    val doReset = { layout = if (keyboard) ScOverlayStore.KEYBOARD_DEFAULT else ScOverlayLayout() }
                    ScNavItem(nav, line = 10, col = col++, onActivate = doReset) {
                        OutlinedButton(onClick = doReset) { Text("Reset") }
                    }
                    if (!isShared) {
                        // Revert this scope to its fallback: a per-menu selection → the whole-HUD placement; the
                        // whole HUD → the global default. Keyboard → the global keyboard default.
                        val perMenu = selectedMenu
                        val useGlobal = {
                            when {
                                keyboard -> ScOverlayStore.clearKeyboard(context, storeKey)
                                perMenu != null -> ScOverlayStore.clearMenu(context, storeKey, perMenu.name)
                                else -> ScOverlayStore.clear(context, storeKey)
                            }
                            SnackbarManager.show(if (perMenu != null) "Using the HUD default" else "Using global default")
                            onDismiss()
                        }
                        ScNavItem(nav, line = 10, col = col++, onActivate = useGlobal) {
                            OutlinedButton(onClick = useGlobal) { Text(if (perMenu != null) "Use HUD default" else "Use global") }
                        }
                    }
                    ScNavItem(nav, line = 10, col = col++, onActivate = onDismiss) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                    val perMenu = selectedMenu
                    val doSave = {
                        when {
                            keyboard -> ScOverlayStore.saveKeyboard(context, storeKey, layout)
                            perMenu != null -> ScOverlayStore.saveMenu(context, storeKey, perMenu.name, layout)
                            else -> ScOverlayStore.save(context, storeKey, layout)
                        }
                        SnackbarManager.show("Overlay layout saved")
                        onDismiss()
                    }
                    ScNavItem(nav, line = 10, col = col++, onActivate = doSave) {
                        Button(onClick = doSave) { Text("Save") }
                    }
                }
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
