package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import app.gamenative.steamcontroller.AnalogMode
import app.gamenative.steamcontroller.EditActivator
import app.gamenative.steamcontroller.EditAnalog
import app.gamenative.steamcontroller.EditBinding
import app.gamenative.steamcontroller.EditCurve
import app.gamenative.steamcontroller.EditGyro
import app.gamenative.steamcontroller.EditHaptics
import app.gamenative.steamcontroller.EditMenuSlot
import app.gamenative.steamcontroller.EditTrigger
import app.gamenative.steamcontroller.GyroEditMode
import app.gamenative.steamcontroller.OutputKind
import app.gamenative.steamcontroller.TriggerEditMode
import app.gamenative.steamcontroller.ScConfigStore
import app.gamenative.steamcontroller.ScMenuLabelTool
import app.gamenative.steamcontroller.ScMenuLocation
import app.gamenative.steamcontroller.ScEditableConfig
import app.gamenative.steamcontroller.ScEditableProfile
import app.gamenative.steamcontroller.ScEditableSet
import app.gamenative.steamcontroller.ScProfile
import app.gamenative.steamcontroller.ScSource
import app.gamenative.ui.util.SnackbarManager
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode

/**
 * Built-in Steam Controller binding editor (newprompt "Next focus" #1b). A purpose-built editor for the SC's
 * digital sources — distinct from GameNative's touch/XInput controls editor, which doesn't model the SC's
 * paddles/grips/pad-clicks or per-binding activators. Loads/saves an [ScEditableProfile] via [ScConfigStore]
 * keyed by the game's container id; the live driver picks it up on next launch.
 *
 * Scope (MVP): per-source output (key / pad button / pad d-pad / mouse button / unbound) + activator. Analog
 * sources (sticks, trackpad motion, triggers, gyro) inherit the built-in default for now; action sets / layers
 * / mode-shift come from importing a `.vdf`.
 */
@Composable
fun SteamControllerBindingEditorDialog(containerId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Phase 5d: the editor authors a whole multi-action-set config. `profile` below is the currently-edited set.
    var config by remember {
        mutableStateOf(
            ScConfigStore.loadEditableConfig(context, containerId)
                ?: ScEditableConfig.fromSingle(ScEditableProfile.from(ScProfile.default())),
        )
    }
    var activeSetId by remember { mutableStateOf(config.defaultSetId) }
    val activeIdx = config.sets.indexOfFirst { it.id == activeSetId }.let { if (it < 0) 0 else it }
    val profile = config.sets[activeIdx].profile
    fun setProfile(p: ScEditableProfile) {
        config = config.copy(sets = config.sets.toMutableList().also { it[activeIdx] = it[activeIdx].copy(profile = p) })
    }
    var editing by remember { mutableStateOf<ScSource?>(null) }
    var editingSurface by remember { mutableStateOf<AnalogSurface?>(null) }
    var editingTrigger by remember { mutableStateOf<TriggerSide?>(null) }
    var editingGyro by remember { mutableStateOf(false) }
    var editingHaptics by remember { mutableStateOf(false) }
    // Menus from an imported .vdf / the built-in default aren't authored in this editor (they resolve as INHERIT),
    // so their slot labels can't be renamed inline — enumerate them and offer a scoped "Rename slots…" affordance
    // on the matching surface, keyed by (setId, location) and backed by the label overlay (ScMenuLabels).
    val rawMenus = remember(containerId) {
        runCatching { ScConfigStore.rawConfig(context, containerId)?.let { ScMenuLabelTool.enumerate(it) } }.getOrNull() ?: emptyList()
    }
    var renamingMenu by remember { mutableStateOf<Pair<String, ScMenuLocation>?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 640.dp).padding(8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            // Controller d-pad here SCROLLS the (long) content list: Compose's focus traversal (moveFocus) proved
            // unreliable inside this Dialog's FlowRow+scroll layout — the key was consumed but focus never moved — so
            // d-pad up/down drives the hoisted [scrollState] directly (guaranteed + visible), and the pad cursor does
            // the pointing/clicking. onPreviewKeyEvent only fires while the root holds focus, so we hammer requestFocus
            // until something in the dialog is focused (a freshly-opened Dialog window doesn't focus on the first frame).
            val scrollState = rememberScrollState()
            val nav = remember { ScNavState() }
            val rootFocus = remember { FocusRequester() }
            var rootHasFocus by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // onPreviewKeyEvent only fires while the root holds focus; a freshly-opened Dialog window doesn't
                // focus on the first frame, so hammer requestFocus until something here is focused.
                repeat(80) { if (rootHasFocus) return@LaunchedEffect; runCatching { rootFocus.requestFocus() }; delay(25) }
            }
            // LB/RB cycle the active action set (sets are tab-like), mirroring the picker's bumper-tab pattern.
            fun cycleSet(d: Int) {
                if (config.sets.size <= 1) return
                val i = config.sets.indexOfFirst { it.id == activeSetId }.coerceAtLeast(0)
                activeSetId = config.sets[((i + d) % config.sets.size + config.sets.size) % config.sets.size].id
            }
            Column(
                modifier = Modifier.padding(16.dp)
                    .focusRequester(rootFocus)
                    .onFocusChanged { rootHasFocus = it.hasFocus }
                    .focusable()
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionDown -> { nav.moveVertical(1); true }
                            Key.DirectionUp -> { nav.moveVertical(-1); true }
                            Key.DirectionRight -> { nav.moveHorizontal(1); true }
                            Key.DirectionLeft -> { nav.moveHorizontal(-1); true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA -> { nav.activate(); true }
                            Key.ButtonL1 -> { cycleSet(-1); true }
                            Key.ButtonR1 -> { cycleSet(1); true }
                            else -> false
                        }
                    },
            ) {
                ScNavDialogCapture(onBack = onDismiss)  // let the controller navigate this dialog window
                Text("Steam Controller bindings", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Tap a button to rebind it; tap a stick/trackpad, trigger, gyro, or haptics row to change its " +
                        "behavior. Inherited menus (from the built-in default / imported .vdf) show a “Rename slots…” " +
                        "action; authored menus rename their slots inside the surface's behavior editor.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                // ── Action sets (Phase 5d): pick which set to edit, add/rename/delete, choose the launch default ──
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Action sets", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    BumperHint("LB")
                    BumperHint("RB")
                    Text("switch set", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CmdFlow {
                    config.sets.forEachIndexed { i, s ->
                        val isDef = s.id == config.defaultSetId
                        val selectSet = { activeSetId = s.id }
                        ScNavItem(nav, line = 0, col = i, onActivate = selectSet) {
                            CmdChip((s.name.ifBlank { "Set ${i + 1}" }) + if (isDef) "  ★" else "", selected = s.id == activeSetId, onClick = selectSet)
                        }
                    }
                    val addSet = {
                        val id = config.nextSetId()
                        config = config.copy(sets = config.sets + ScEditableSet(id = id, name = "Set ${config.sets.size + 1}"))
                        activeSetId = id
                    }
                    ScNavItem(nav, line = 0, col = config.sets.size, onActivate = addSet) {
                        CmdChip("+ Add", selected = false, onClick = addSet)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    var editingName by remember { mutableStateOf(false) }
                    ScNavItem(nav, line = 1, col = 0, modifier = Modifier.weight(1f), onActivate = { editingName = true }) {
                        ScTextEditField(
                            label = "Set name",
                            value = config.sets[activeIdx].name,
                            onValueChange = { newName ->
                                config = config.copy(
                                    sets = config.sets.toMutableList().also { it[activeIdx] = it[activeIdx].copy(name = newName) },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            editing = editingName,
                            onEditingChange = { editingName = it },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // Mark this set as an action LAYER (a partial overlay pushed by a "hold/add layer" binding) vs a
                    // base action set. A layer overrides only the surfaces it defines + its buttons (see LayerOp).
                    val isLayer = config.sets[activeIdx].isLayer
                    val toggleLayer = {
                        config = config.copy(sets = config.sets.toMutableList().also { it[activeIdx] = it[activeIdx].copy(isLayer = !isLayer) })
                    }
                    val makeDefault = { config = config.copy(defaultSetId = activeSetId) }
                    val deleteSet = {
                        if (config.sets.size > 1) {
                            val removedId = config.sets[activeIdx].id
                            val newSets = config.sets.filterIndexed { idx, _ -> idx != activeIdx }
                            val newDefault = if (config.defaultSetId == removedId) newSets.first().id else config.defaultSetId
                            config = config.copy(sets = newSets, defaultSetId = newDefault)
                            activeSetId = newSets.first().id
                        }
                    }
                    ScNavItem(nav, line = 1, col = 1, onActivate = toggleLayer) {
                        TextButton(onClick = toggleLayer) { Text(if (isLayer) "Layer ✓" else "Layer") }
                    }
                    ScNavItem(nav, line = 1, col = 2, onActivate = makeDefault) {
                        TextButton(onClick = makeDefault, enabled = config.defaultSetId != activeSetId) { Text("Default") }
                    }
                    ScNavItem(nav, line = 1, col = 3, onActivate = deleteSet) {
                        TextButton(onClick = deleteSet, enabled = config.sets.size > 1) { Text("Delete") }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp))

                Column(
                    modifier = Modifier.weight(1f, fill = false)
                        .verticalScroll(scrollState)
                        .verticalScrollbar(
                            scrollState,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                        ),
                ) {
                    // Inset the rows from the right so the scrollbar (drawn on the outer edge) doesn't overlap them.
                    Column(modifier = Modifier.fillMaxWidth().padding(end = 12.dp)) {
                        var navLine = 2 // lines 0=set chips, 1=Layer/Default/Delete; the scroll list starts at 2
                        var lastGroup = ""
                        for (src in ScSource.entries) {
                            if (src.group != lastGroup) {
                                lastGroup = src.group
                                Text(
                                    src.group,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                                )
                            }
                            val open = { editing = src }
                            ScNavItem(nav, line = navLine, modifier = Modifier.fillMaxWidth(), onActivate = open) {
                                SourceRow(src, profile.buttons[src.name] ?: EditBinding(), onClick = open)
                            }
                            navLine++
                        }

                        Text(
                            "Analog sources",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                        )
                        for (surface in AnalogSurface.ALL) {
                            val open = { editingSurface = surface }
                            // An inherited menu here (base .vdf/default has one, but the editor doesn't author it) →
                            // its slots aren't inline-editable, so surface a scoped "Rename slots…" affordance.
                            val inheritedMenu = surface.get(profile) == null &&
                                rawMenus.any { it.setId == activeSetId && it.location == surface.location }
                            ScNavItem(nav, line = navLine, col = 0, modifier = Modifier.fillMaxWidth(), onActivate = open) {
                                AnalogSurfaceRow(surface, surface.get(profile), onClick = open)
                            }
                            if (inheritedMenu) {
                                val rename = { renamingMenu = activeSetId to surface.location }
                                ScNavItem(nav, line = navLine, col = 1, modifier = Modifier.fillMaxWidth().padding(start = 16.dp), onActivate = rename) {
                                    TextButton(onClick = rename) { Text("Rename slots…") }
                                }
                            }
                            navLine++
                        }

                        SectionHeader("Triggers")
                        for (side in TriggerSide.entries) {
                            val open = { editingTrigger = side }
                            ScNavItem(nav, line = navLine, modifier = Modifier.fillMaxWidth(), onActivate = open) {
                                DetailRow(side.label, summarizeTrigger(side.get(profile)), onClick = open)
                            }
                            navLine++
                        }

                        SectionHeader("Gyro")
                        val openGyro = { editingGyro = true }
                        ScNavItem(nav, line = navLine, modifier = Modifier.fillMaxWidth(), onActivate = openGyro) {
                            DetailRow("Gyro", summarizeGyro(profile.gyro), onClick = openGyro)
                        }
                        navLine++

                        SectionHeader("Haptics")
                        val openHaptics = { editingHaptics = true }
                        ScNavItem(nav, line = navLine, modifier = Modifier.fillMaxWidth(), onActivate = openHaptics) {
                            DetailRow("Haptics", summarizeHaptics(profile.haptics), onClick = openHaptics)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                // Bottom action row: the last nav line (after the set chips=0, Layer/Default/Delete=1, and every scroll row).
                val bottomLine = 2 + ScSource.entries.size + AnalogSurface.ALL.size + TriggerSide.entries.size + 2
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val reset = { setProfile(ScEditableProfile.from(ScProfile.default())) }
                    val save = {
                        if (ScConfigStore.saveEditableConfig(context, containerId, config)) {
                            SnackbarManager.show("Bindings saved"); onDismiss()
                        } else {
                            SnackbarManager.show("Could not save bindings")
                        }
                    }
                    ScNavItem(nav, line = bottomLine, col = 0, onActivate = reset) {
                        TextButton(onClick = reset) { Text("Reset set") }
                    }
                    Spacer(Modifier.width(8.dp))
                    ScNavItem(nav, line = bottomLine, col = 1, onActivate = onDismiss) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                    Spacer(Modifier.width(8.dp))
                    ScNavItem(nav, line = bottomLine, col = 2, onActivate = save) {
                        Button(onClick = save) { Text("Save") }
                    }
                }
            }
        }
    }

    editing?.let { src ->
        BindingPickerDialog(
            title = src.label,
            current = profile.buttons[src.name] ?: EditBinding(),
            actionSets = config.sets.map { it.id to it.name },
            onDismiss = { editing = null },
            onApply = { newBinding ->
                setProfile(profile.copy(buttons = profile.buttons + (src.name to newBinding)))
                editing = null
            },
        )
    }

    editingSurface?.let { surface ->
        AnalogPickerDialog(
            surface = surface,
            current = surface.get(profile),
            onDismiss = { editingSurface = null },
            onApply = { newAnalog ->
                setProfile(surface.set(profile, newAnalog))
                editingSurface = null
            },
        )
    }

    renamingMenu?.let { (setId, loc) ->
        ScMenuLabelEditorDialog(
            storeKey = containerId,
            filterSetId = setId,
            filterLocation = loc.name,
            onDismiss = { renamingMenu = null },
        )
    }

    editingTrigger?.let { side ->
        TriggerPickerDialog(
            side = side,
            current = side.get(profile) ?: EditTrigger(axis = side.defaultAxis),
            onDismiss = { editingTrigger = null },
            onApply = { t -> setProfile(side.set(profile, t)); editingTrigger = null },
        )
    }

    if (editingGyro) {
        GyroPickerDialog(
            current = profile.gyro ?: EditGyro(),
            onDismiss = { editingGyro = false },
            onApply = { g -> setProfile(profile.copy(gyro = g)); editingGyro = false },
        )
    }

    if (editingHaptics) {
        HapticsDialog(
            current = profile.haptics ?: EditHaptics(),
            onDismiss = { editingHaptics = false },
            onApply = { h -> setProfile(profile.copy(haptics = h)); editingHaptics = false },
        )
    }
}

// ---- Phase 5: triggers / gyro / haptics ----

/** A reusable label + summary row (used by triggers/gyro/haptics, mirroring [AnalogSurfaceRow]). */
@Composable
private fun DetailRow(label: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
    )
}

/** Which physical trigger an [EditTrigger] row authors, with get/set on [ScEditableProfile] + its default axis. */
private enum class TriggerSide(val label: String, val defaultAxis: String) {
    LEFT("Left Trigger", "GAMEPAD_L2"),
    RIGHT("Right Trigger", "GAMEPAD_R2");

    fun get(p: ScEditableProfile): EditTrigger? = if (this == LEFT) p.leftTrigger else p.rightTrigger
    fun set(p: ScEditableProfile, t: EditTrigger?): ScEditableProfile =
        if (this == LEFT) p.copy(leftTrigger = t) else p.copy(rightTrigger = t)
}

private fun summarizeTrigger(t: EditTrigger?): String = when (t?.mode) {
    null -> "Inherit (default)"
    TriggerEditMode.AXIS -> "Axis: ${t.axis.removePrefix("GAMEPAD_")}"
    TriggerEditMode.STAGED -> "Staged (soft/full)"
}

private fun summarizeGyro(g: EditGyro?): String {
    fun g2(s: String) = s.lowercase().replace('_', ' ')
    val act = g2(g?.activation ?: "ENABLE")
    return when (g?.mode) {
        null -> "Inherit (default)"
        GyroEditMode.OFF -> "Off"
        GyroEditMode.MOUSE -> "Mouse ($act, ${g2(g.gate)})"
        GyroEditMode.JOYSTICK -> "Joystick (${g.outputStick.lowercase()}, $act, ${g2(g.gate)})"
    }
}

private fun summarizeHaptics(h: EditHaptics?): String = when {
    h == null -> "Inherit (default)"
    !h.enabled -> "Off"
    else -> "On" + (if (!h.leftPadEnabled || !h.rightPadEnabled) " (partial)" else "")
}

@Composable
private fun TriggerPickerDialog(side: TriggerSide, current: EditTrigger, onDismiss: () -> Unit, onApply: (EditTrigger?) -> Unit) {
    val inherit = "INHERIT"
    val modeOptions = listOf(inherit to "Inherit / keep current") + TriggerEditMode.entries.map { it.name to it.uiLabel() }
    // Reflect the trigger's actual mode (Axis/Staged) so opening a configured trigger shows it, not "Inherit".
    var modeKey by remember { mutableStateOf(current.mode.name) }
    var axis by remember { mutableStateOf(current.axis) }
    var soft by remember { mutableIntStateOf(current.softThresholdPct) }
    var full by remember { mutableIntStateOf(current.fullThresholdPct) }
    // The command each stage fires at its threshold (Steam's soft/full-pull binds). Authored via the shared picker.
    var softCmd by remember { mutableStateOf(current.soft) }
    var fullCmd by remember { mutableStateOf(current.full) }
    var pickingStage by remember { mutableStateOf<String?>(null) }  // "SOFT" | "FULL" | null
    val mode = runCatching { TriggerEditMode.valueOf(modeKey) }.getOrNull()
    val nav = remember { ScNavState() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${side.label} behavior") },
        text = {
            val doApply = {
                if (modeKey == inherit) onApply(null)
                else onApply(
                    current.copy(
                        mode = mode ?: TriggerEditMode.AXIS, axis = axis,
                        soft = softCmd, full = fullCmd,
                        softThresholdPct = soft, fullThresholdPct = full,
                    ),
                )
            }
            ScNavDialogColumn(nav, onBack = onDismiss, modifier = Modifier.heightIn(max = 420.dp).verticalScrollWithBar()) {
                LabeledDropdown("Behavior", modeOptions, modeKey, nav = nav, navLine = 0) { modeKey = it }
                if (mode != null) {
                    Spacer(Modifier.height(8.dp))
                    LabeledDropdown(
                        "Analog axis",
                        listOf("NONE" to "None", "GAMEPAD_L2" to "Left trigger (LT)", "GAMEPAD_R2" to "Right trigger (RT)"),
                        axis, nav = nav, navLine = 1,
                    ) { axis = it }
                }
                if (mode == TriggerEditMode.STAGED) {
                    Spacer(Modifier.height(8.dp))
                    AnalogSlider("Soft-pull threshold", soft, 5, 95, "%", nav = nav, navLine = 2) { soft = it }
                    Spacer(Modifier.height(8.dp))
                    AnalogSlider("Full-pull threshold", full, 5, 100, "%", nav = nav, navLine = 3) { full = it }
                    Spacer(Modifier.height(8.dp))
                    // Each stage fires its own command at its threshold — tap to bind via the standard picker.
                    val openSoft = { pickingStage = "SOFT" }
                    val openFull = { pickingStage = "FULL" }
                    ScNavItem(nav, 4, modifier = Modifier.fillMaxWidth(), onActivate = openSoft) { DetailRow("Soft-pull command", summarize(softCmd), openSoft) }
                    ScNavItem(nav, 5, modifier = Modifier.fillMaxWidth(), onActivate = openFull) { DetailRow("Full-pull command", summarize(fullCmd), openFull) }
                }
                NavApplyCancelRow(nav, onApply = doApply, onCancel = onDismiss)
            }
        },
        confirmButton = {},
    )

    // Nested command picker for whichever stage is being bound (reuses the Phase-3 picker; no activator on a stage).
    pickingStage?.let { stage ->
        val isSoft = stage == "SOFT"
        BindingPickerDialog(
            title = if (isSoft) "Soft-pull command" else "Full-pull command",
            current = if (isSoft) softCmd else fullCmd,
            onDismiss = { pickingStage = null },
            onApply = { b -> if (isSoft) softCmd = b else fullCmd = b; pickingStage = null },
            showActivator = false,
        )
    }
}

@Composable
private fun GyroPickerDialog(current: EditGyro, onDismiss: () -> Unit, onApply: (EditGyro?) -> Unit) {
    val inherit = "INHERIT"
    val modeOptions = listOf(inherit to "Inherit / keep current") + GyroEditMode.entries.map { it.name to it.uiLabel() }
    // Reflect the gyro's actual mode so opening a configured gyro shows it, not "Inherit".
    var modeKey by remember { mutableStateOf(current.mode.name) }
    var sens by remember { mutableIntStateOf(current.sensitivityPct) }
    var gate by remember { mutableStateOf(current.gate) }
    var activation by remember { mutableStateOf(current.activation) }
    val mode = runCatching { GyroEditMode.valueOf(modeKey) }.getOrNull()
    val nav = remember { ScNavState() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gyro behavior") },
        text = {
            val doApply = {
                if (modeKey == inherit) onApply(null)
                else onApply(current.copy(mode = mode ?: GyroEditMode.MOUSE, sensitivityPct = sens, gate = gate, activation = activation))
            }
            ScNavDialogColumn(nav, onBack = onDismiss, modifier = Modifier.heightIn(max = 420.dp).verticalScrollWithBar()) {
                LabeledDropdown("Behavior", modeOptions, modeKey, nav = nav, navLine = 0) { modeKey = it }
                // Sensitivity + gate + activation apply to BOTH mouse and joystick gyro.
                if (mode == GyroEditMode.MOUSE || mode == GyroEditMode.JOYSTICK) {
                    Spacer(Modifier.height(8.dp))
                    AnalogSlider("Sensitivity", sens, 25, 400, "%", nav = nav, navLine = 1) { sens = it }
                    Spacer(Modifier.height(8.dp))
                    // "Gyro Enable/Suppress/Toggle" — what the gate button DOES.
                    LabeledDropdown(
                        "Gyro mode",
                        listOf(
                            "ENABLE" to "Hold to enable gyro", "SUPPRESS" to "Hold to suppress gyro",
                            "TOGGLE" to "Toggle gyro on/off",
                        ),
                        activation, nav = nav, navLine = 2,
                    ) { activation = it }
                    Spacer(Modifier.height(8.dp))
                    // "Choose Gyro Button(s)" — which button gates the gyro (any button, not just grips).
                    LabeledDropdown(
                        "Gyro button",
                        listOf(
                            "ALWAYS" to "Always on (no button)",
                            "EITHER_GRIP" to "Either grip", "LEFT_GRIP" to "Left grip", "RIGHT_GRIP" to "Right grip",
                            "L4" to "L4 paddle", "R4" to "R4 paddle", "L5" to "L5 paddle", "R5" to "R5 paddle",
                            "LEFT_BUMPER" to "Left bumper", "RIGHT_BUMPER" to "Right bumper",
                            "A" to "A", "B" to "B", "X" to "X", "Y" to "Y",
                            "L3" to "Left stick click", "R3" to "Right stick click",
                            "RIGHT_PAD_TOUCH" to "Right pad touch", "LEFT_PAD_TOUCH" to "Left pad touch",
                            "ANY_TOUCH" to "Any pad/stick touch", "ALL_TOUCH" to "All surfaces touched",
                        ),
                        gate, nav = nav, navLine = 3,
                    ) { gate = it }
                }
                NavApplyCancelRow(nav, onApply = doApply, onCancel = onDismiss)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun HapticsDialog(current: EditHaptics, onDismiss: () -> Unit, onApply: (EditHaptics?) -> Unit) {
    var enabled by remember { mutableStateOf(current.enabled) }
    var left by remember { mutableStateOf(current.leftPadEnabled) }
    var right by remember { mutableStateOf(current.rightPadEnabled) }
    var detent by remember { mutableIntStateOf(current.detentStep) }
    val nav = remember { ScNavState() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Haptics") },
        text = {
            val doApply = { onApply(current.copy(enabled = enabled, leftPadEnabled = left, rightPadEnabled = right, detentStep = detent)) }
            ScNavDialogColumn(nav, onBack = onDismiss, modifier = Modifier.heightIn(max = 420.dp).verticalScrollWithBar()) {
                AnalogToggle("Haptics enabled", enabled, nav = nav, navLine = 0) { enabled = it }
                if (enabled) {
                    AnalogToggle("Left pad", left, nav = nav, navLine = 1) { left = it }
                    AnalogToggle("Right pad", right, nav = nav, navLine = 2) { right = it }
                    Spacer(Modifier.height(8.dp))
                    AnalogSlider("Detent spacing", detent, 2000, 12000, "", nav = nav, navLine = 3) { detent = it }
                    Text(
                        "Detent spacing = pad travel between slide \"tick\" pulses; smaller = more frequent ticks.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                NavApplyCancelRow(nav, onApply = doApply, onCancel = onDismiss)
            }
        },
        confirmButton = {},
    )
}

private fun TriggerEditMode.uiLabel(): String = when (this) {
    TriggerEditMode.AXIS -> "Analog axis (trigger)"
    TriggerEditMode.STAGED -> "Soft / full pull (staged)"
}

private fun GyroEditMode.uiLabel(): String = when (this) {
    GyroEditMode.OFF -> "Off"
    GyroEditMode.MOUSE -> "As mouse (aim)"
    GyroEditMode.JOYSTICK -> "As joystick (camera)"
}

@Composable
private fun SourceRow(src: ScSource, binding: EditBinding, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(src.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            summarize(binding),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Steam-style **command picker** (editor Phase 3): tabbed by command category — Keyboard / Numpad / Mouse /
 * Gamepad — each showing a visual grid of selectable commands (a faithful clone of Steam Input's command picker,
 * minus the Steam-only SYSTEM/CAMERA tabs and the ACTION SETS tab, which needs multi-set authoring — Phase 5).
 * The chosen command + an [EditActivator] make up the [EditBinding]. A single binding selects exactly one command.
 */
@Composable
private fun BindingPickerDialog(
    title: String,
    current: EditBinding,
    onDismiss: () -> Unit,
    onApply: (EditBinding) -> Unit,
    /** Staged-trigger soft/full stages fire a plain output (no activator), so the activator picker is hidden there. */
    showActivator: Boolean = true,
    /** Available action sets (id→name) for the ACTION SETS tab. Empty hides that tab (e.g. staged-trigger pickers). */
    actionSets: List<Pair<String, String>> = emptyList(),
    /** Optional content rendered at the top of the picker (e.g. a menu-slot label field). */
    headerContent: (@Composable () -> Unit)? = null,
) {
    // Staged binding (the chosen command) — only the field matching [kind] is meaningful, mirroring EditBinding.
    var kind by remember { mutableStateOf(current.kind) }
    // A KEY binding is modifiers + a main key, held together (Steam's key combos). The main key is the last element;
    // any leading entries are modifiers. The editor builds `keys` = (selected modifiers, in canonical order) + main.
    var keyName by remember { mutableStateOf(current.keys.lastOrNull()) }
    var modifiers by remember { mutableStateOf(current.keys.dropLast(1).toSet()) }
    var gamepadIdx by remember { mutableStateOf(current.gamepadIdx) }
    var dpadIndex by remember { mutableStateOf(current.dpadIndex) }
    var mouseButton by remember { mutableStateOf(current.mouseButton) }
    var targetSetId by remember { mutableStateOf(current.targetSetId) }
    var layerId by remember { mutableStateOf(current.layerId) }
    var layerOp by remember { mutableStateOf(current.layerOp) }
    var activator by remember { mutableStateOf(current.activator) }
    var activatorMs by remember { mutableIntStateOf(current.activatorMs) }
    // ADVANCED is always available; ACTION_SETS only when the caller supplies sets.
    val tabs = CmdTab.entries.filter { it != CmdTab.ACTION_SETS || actionSets.isNotEmpty() }
    var tab by remember { mutableStateOf(initialTab(current)) }
    // Bumpers (LB/RB) flip between command-picker tabs (the bridge dispatches L1/R1 here). Needs the dialog focused,
    // so hammer requestFocus until it lands (a freshly-opened dialog window isn't focused on the first frame).
    val pickerFocus = remember { FocusRequester() }
    var pickerHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(80) { if (pickerHasFocus) return@LaunchedEffect; runCatching { pickerFocus.requestFocus() }; delay(25) }
    }
    fun cycleTab(d: Int) { tab = tabs[((tabs.indexOf(tab) + d) % tabs.size + tabs.size) % tabs.size] }
    // Item-by-item d-pad inside the picker (command chips / key grid / Apply-Cancel). The navigable cells change with
    // the tab, so reset the selection whenever the tab changes (ScNavState navigates only currently-registered cells).
    val pkNav = remember { ScNavState() }
    LaunchedEffect(tab) { pkNav.reset() }
    val applyLine = 99 // a fixed line after any tab's content (nav only visits existing lines, so the gap is harmless)

    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.95f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 500.dp)
                    .focusRequester(pickerFocus)
                    .onFocusChanged { pickerHasFocus = it.hasFocus }
                    .focusable()
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.ButtonL1 -> { cycleTab(-1); true }
                            Key.ButtonR1 -> { cycleTab(1); true }
                            Key.DirectionDown -> { pkNav.moveVertical(1); true }
                            Key.DirectionUp -> { pkNav.moveVertical(-1); true }
                            Key.DirectionRight -> { pkNav.moveHorizontal(1); true }
                            Key.DirectionLeft -> { pkNav.moveHorizontal(-1); true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA -> { pkNav.activate(); true }
                            else -> false
                        }
                    }
                    .verticalScrollWithBar(),
            ) {
                ScNavDialogCapture(onBack = onDismiss)
                headerContent?.invoke()
                // A kept binding the picker can't author (advanced INHERIT, or a mouse-nudge center): show what it is.
                val keptDesc = when (kind) {
                    OutputKind.INHERIT -> current.inheritDesc
                    OutputKind.MOUSE_NUDGE -> if (current.nudgeDx == 0 && current.nudgeDy == 0) "Center (no-op mouse nudge)" else "Mouse nudge (${current.nudgeDx},${current.nudgeDy})"
                    else -> ""
                }
                if (keptDesc.isNotBlank()) {
                    Text(
                        "Keeps: $keptDesc. Pick a command to replace it, or Cancel to keep it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                CmdTabRow(tabs, tab) { tab = it }
                Spacer(Modifier.height(10.dp))
                when (tab) {
                    CmdTab.KEYBOARD -> {
                        ModifierRow(modifiers, pkNav, 0) { m -> modifiers = if (m in modifiers) modifiers - m else modifiers + m }
                        Spacer(Modifier.height(6.dp))
                        KeyGrid(KB_ROWS, selected = keyName.takeIf { kind == OutputKind.KEY }, nav = pkNav, baseLine = 1) {
                            kind = OutputKind.KEY; keyName = it
                        }
                    }
                    CmdTab.NUMPAD -> {
                        ModifierRow(modifiers, pkNav, 0) { m -> modifiers = if (m in modifiers) modifiers - m else modifiers + m }
                        Spacer(Modifier.height(6.dp))
                        KeyGrid(NUMPAD_ROWS, selected = keyName.takeIf { kind == OutputKind.KEY }, nav = pkNav, baseLine = 1) {
                            kind = OutputKind.KEY; keyName = it
                        }
                    }
                    CmdTab.MOUSE -> CmdFlow {
                        MOUSE_CMDS.toList().forEachIndexed { ci, (name, label) ->
                            val pick = { kind = OutputKind.MOUSE_BUTTON; mouseButton = name }
                            ScNavItem(pkNav, 0, ci, onActivate = pick) {
                                CmdChip(label, selected = kind == OutputKind.MOUSE_BUTTON && mouseButton == name, onClick = pick)
                            }
                        }
                    }
                    CmdTab.GAMEPAD -> {
                        Text("Buttons", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CmdFlow {
                            ScEditableProfile.GAMEPAD_BUTTONS.toList().forEachIndexed { ci, (idx, label) ->
                                val pick = { kind = OutputKind.GAMEPAD_BUTTON; gamepadIdx = idx }
                                ScNavItem(pkNav, 0, ci, onActivate = pick) {
                                    CmdChip(label.removePrefix("Pad "), selected = kind == OutputKind.GAMEPAD_BUTTON && gamepadIdx == idx, onClick = pick)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("D-Pad", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CmdFlow {
                            ScEditableProfile.DPAD_DIRECTIONS.toList().forEachIndexed { ci, (i, label) ->
                                val pick = { kind = OutputKind.GAMEPAD_DPAD; dpadIndex = i }
                                ScNavItem(pkNav, 1, ci, onActivate = pick) {
                                    CmdChip(label.removePrefix("Pad D-Pad "), selected = kind == OutputKind.GAMEPAD_DPAD && dpadIndex == i, onClick = pick)
                                }
                            }
                        }
                    }
                    CmdTab.ACTION_SETS -> {
                        Text("Switch to action set", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CmdFlow {
                            actionSets.forEachIndexed { ci, (id, name) ->
                                val pick = { kind = OutputKind.SWITCH_ACTION_SET; targetSetId = id }
                                ScNavItem(pkNav, 0, ci, onActivate = pick) {
                                    CmdChip(name.ifBlank { "Set $id" }, selected = kind == OutputKind.SWITCH_ACTION_SET && targetSetId == id, onClick = pick)
                                }
                            }
                        }
                    }
                    CmdTab.ADVANCED -> {
                        Text("Controller actions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CmdFlow {
                            val showKb = { kind = OutputKind.SHOW_KEYBOARD }
                            val openQm = { kind = OutputKind.OPEN_QUICK_MENU }
                            ScNavItem(pkNav, 0, 0, onActivate = showKb) { CmdChip("Show keyboard", selected = kind == OutputKind.SHOW_KEYBOARD, onClick = showKb) }
                            ScNavItem(pkNav, 0, 1, onActivate = openQm) { CmdChip("Open QuickMenu", selected = kind == OutputKind.OPEN_QUICK_MENU, onClick = openQm) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Action layer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (actionSets.isEmpty()) {
                            Text("No layers available — mark a set as a layer in the editor first.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            LabeledDropdown(
                                "Operation",
                                listOf("HOLD" to "Hold while pressed", "ADD" to "Add (toggle on)", "REMOVE" to "Remove (toggle off)"),
                                layerOp,
                                nav = pkNav,
                                navLine = 1,
                            ) { layerOp = it }
                            Spacer(Modifier.height(4.dp))
                            CmdFlow {
                                actionSets.forEachIndexed { ci, (id, name) ->
                                    val pick = { kind = OutputKind.LAYER_OP; layerId = id }
                                    ScNavItem(pkNav, 2, ci, onActivate = pick) {
                                        CmdChip(name.ifBlank { "Set $id" }, selected = kind == OutputKind.LAYER_OP && layerId == id, onClick = pick)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Selected: ${summarize(stagedBinding(kind, keyName, modifiers, gamepadIdx, dpadIndex, mouseButton, targetSetId, layerId, layerOp, activator, activatorMs))}",
                    style = MaterialTheme.typography.bodyMedium)
                if (showActivator && kind != OutputKind.NONE) {
                    Spacer(Modifier.height(8.dp))
                    // Phase 4: activator picker — pick how the press fires, plus its timing where applicable.
                    LabeledDropdown(
                        label = "Activator",
                        options = EditActivator.entries.map { it to it.uiLabel() },
                        selected = activator,
                        nav = pkNav,
                        navLine = applyLine - 1,
                        onSelected = { activator = it; activatorMs = it.defaultMs() },
                    )
                    activator.timingLabel()?.let { tl ->
                        Spacer(Modifier.height(8.dp))
                        val ms = if (activatorMs > 0) activatorMs else activator.defaultMs()
                        AnalogSlider(tl, ms, 40, 1000, " ms") { activatorMs = it }
                    }
                }
                // Clear / Cancel / Apply live IN the scrollable column (not the AlertDialog button slot) so the d-pad
                // can reach them (the nav root + cells are all inside this column).
                val doClear = { kind = OutputKind.NONE }
                val doApply = {
                    // Kinds the picker can't author (advanced INHERIT / mouse-nudge) are preserved verbatim if untouched.
                    onApply(
                        if (kind == OutputKind.INHERIT || kind == OutputKind.MOUSE_NUDGE) current
                        else stagedBinding(kind, keyName, modifiers, gamepadIdx, dpadIndex, mouseButton, targetSetId, layerId, layerOp, activator, activatorMs),
                    )
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScNavItem(pkNav, applyLine, 0, onActivate = doClear) { TextButton(onClick = doClear) { Text("Clear") } }
                    Spacer(Modifier.width(8.dp))
                    ScNavItem(pkNav, applyLine, 1, onActivate = onDismiss) { TextButton(onClick = onDismiss) { Text("Cancel") } }
                    Spacer(Modifier.width(8.dp))
                    ScNavItem(pkNav, applyLine, 2, onActivate = doApply) { Button(onClick = doApply) { Text("Apply") } }
                }
            }
        },
        confirmButton = {},
    )
}

/** Build an [EditBinding] from the picker's staged fields (only the field matching [kind] is used). A KEY binding's
 *  [keys] is the selected [modifiers] (in canonical [MODIFIER_KEYS] order) followed by the main [keyName] — a held
 *  combo (e.g. Ctrl+Shift+A). */
private fun stagedBinding(
    kind: OutputKind, keyName: String?, modifiers: Set<String>, gamepadIdx: Int, dpadIndex: Int, mouseButton: String,
    targetSetId: String, layerId: String, layerOp: String, activator: EditActivator, activatorMs: Int,
): EditBinding = EditBinding(
    kind = kind,
    keys = if (kind == OutputKind.KEY && keyName != null) {
        MODIFIER_KEYS.map { it.first }.filter { it in modifiers && it != keyName } + keyName
    } else emptyList(),
    gamepadIdx = if (kind == OutputKind.GAMEPAD_BUTTON) gamepadIdx else -1,
    dpadIndex = if (kind == OutputKind.GAMEPAD_DPAD) dpadIndex else -1,
    mouseButton = if (kind == OutputKind.MOUSE_BUTTON) mouseButton else "",
    targetSetId = if (kind == OutputKind.SWITCH_ACTION_SET) targetSetId else "",
    layerId = if (kind == OutputKind.LAYER_OP) layerId else "",
    layerOp = layerOp,
    activator = activator,
    activatorMs = activatorMs,
)

/** Modifier keys offered as multi-selectable chips above the key grid (canonical press order). */
private val MODIFIER_KEYS: List<Pair<String, String>> = listOf(
    XKeycode.KEY_CTRL_L.name to "Ctrl",
    XKeycode.KEY_SHIFT_L.name to "Shift",
    XKeycode.KEY_ALT_L.name to "Alt",
)

/** A row of multi-selectable modifier chips; [selected] is the set of chosen modifier key names. */
@Composable
private fun ModifierRow(selected: Set<String>, nav: ScNavState? = null, baseLine: Int = 0, onToggle: (String) -> Unit) {
    Column {
        Text("Modifiers (held with the key)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CmdFlow {
            MODIFIER_KEYS.forEachIndexed { i, mk ->
                val name = mk.first
                val toggle = { onToggle(name) }
                if (nav != null) {
                    ScNavItem(nav, baseLine, i, onActivate = toggle) { CmdChip(mk.second, selected = name in selected, onClick = toggle) }
                } else {
                    CmdChip(mk.second, selected = name in selected, onClick = toggle)
                }
            }
        }
    }
}

/** The command-picker tabs (Steam's SYSTEM/CAMERA are Steam-runtime-only). ACTION_SETS only shown when sets exist. */
private enum class CmdTab(val label: String) {
    KEYBOARD("Keyboard"), NUMPAD("Numpad"), MOUSE("Mouse"), GAMEPAD("Gamepad"), ACTION_SETS("Action Sets"), ADVANCED("Advanced")
}

private fun initialTab(b: EditBinding): CmdTab = when (b.kind) {
    OutputKind.MOUSE_BUTTON -> CmdTab.MOUSE
    OutputKind.GAMEPAD_BUTTON, OutputKind.GAMEPAD_DPAD -> CmdTab.GAMEPAD
    OutputKind.SWITCH_ACTION_SET -> CmdTab.ACTION_SETS
    OutputKind.LAYER_OP, OutputKind.SHOW_KEYBOARD, OutputKind.OPEN_QUICK_MENU -> CmdTab.ADVANCED
    OutputKind.KEY -> if (b.keys.firstOrNull()?.let { it.startsWith("KEY_KP") || it == "KEY_NUM_LOCK" } == true) CmdTab.NUMPAD else CmdTab.KEYBOARD
    OutputKind.NONE, OutputKind.INHERIT, OutputKind.MOUSE_NUDGE -> CmdTab.KEYBOARD
}

@Composable
private fun CmdTabRow(tabs: List<CmdTab>, tab: CmdTab, onSelect: (CmdTab) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        BumperHint("LB")
        tabs.forEach { t ->
            val sel = t == tab
            Box(
                Modifier.weight(1f)
                    .background(
                        if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(6.dp),
                    )
                    .clickable { onSelect(t) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BumperHint("RB")
    }
}

/** A small bumper badge (LB/RB) flanking the tab row, signalling the bumpers switch tabs (controller-UI convention). */
@Composable
private fun BumperHint(label: String) {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** A wrapping container for command chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CmdFlow(content: @Composable () -> Unit) {
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

/** Render keyboard-style rows of key chips; [selected] is the highlighted XKeycode name (or null). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyGrid(rows: List<List<XKeycode>>, selected: String?, nav: ScNavState? = null, baseLine: Int = 0, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { r, row ->
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEachIndexed { c, k ->
                    val pick = { onPick(k.name) }
                    if (nav != null) {
                        ScNavItem(nav, baseLine + r, c, onActivate = pick) { CmdChip(keyLabel(k), selected = selected == k.name, onClick = pick) }
                    } else {
                        CmdChip(keyLabel(k), selected = selected == k.name, onClick = pick)
                    }
                }
            }
        }
    }
}

@Composable
private fun CmdChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Command-picker grid data ----

private val KB_ROWS: List<List<XKeycode>> = listOf(
    listOf(XKeycode.KEY_ESC, XKeycode.KEY_F1, XKeycode.KEY_F2, XKeycode.KEY_F3, XKeycode.KEY_F4, XKeycode.KEY_F5, XKeycode.KEY_F6, XKeycode.KEY_F7, XKeycode.KEY_F8, XKeycode.KEY_F9, XKeycode.KEY_F10, XKeycode.KEY_F11, XKeycode.KEY_F12),
    listOf(XKeycode.KEY_GRAVE, XKeycode.KEY_1, XKeycode.KEY_2, XKeycode.KEY_3, XKeycode.KEY_4, XKeycode.KEY_5, XKeycode.KEY_6, XKeycode.KEY_7, XKeycode.KEY_8, XKeycode.KEY_9, XKeycode.KEY_0, XKeycode.KEY_MINUS, XKeycode.KEY_EQUAL, XKeycode.KEY_BKSP),
    listOf(XKeycode.KEY_TAB, XKeycode.KEY_Q, XKeycode.KEY_W, XKeycode.KEY_E, XKeycode.KEY_R, XKeycode.KEY_T, XKeycode.KEY_Y, XKeycode.KEY_U, XKeycode.KEY_I, XKeycode.KEY_O, XKeycode.KEY_P, XKeycode.KEY_BRACKET_LEFT, XKeycode.KEY_BRACKET_RIGHT, XKeycode.KEY_BACKSLASH),
    listOf(XKeycode.KEY_CAPS_LOCK, XKeycode.KEY_A, XKeycode.KEY_S, XKeycode.KEY_D, XKeycode.KEY_F, XKeycode.KEY_G, XKeycode.KEY_H, XKeycode.KEY_J, XKeycode.KEY_K, XKeycode.KEY_L, XKeycode.KEY_SEMICOLON, XKeycode.KEY_APOSTROPHE, XKeycode.KEY_ENTER),
    listOf(XKeycode.KEY_SHIFT_L, XKeycode.KEY_Z, XKeycode.KEY_X, XKeycode.KEY_C, XKeycode.KEY_V, XKeycode.KEY_B, XKeycode.KEY_N, XKeycode.KEY_M, XKeycode.KEY_COMMA, XKeycode.KEY_PERIOD, XKeycode.KEY_SLASH, XKeycode.KEY_SHIFT_R),
    listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_ALT_L, XKeycode.KEY_SPACE, XKeycode.KEY_ALT_R, XKeycode.KEY_CTRL_R),
    listOf(XKeycode.KEY_INSERT, XKeycode.KEY_HOME, XKeycode.KEY_PRIOR, XKeycode.KEY_DEL, XKeycode.KEY_END, XKeycode.KEY_NEXT, XKeycode.KEY_UP, XKeycode.KEY_LEFT, XKeycode.KEY_DOWN, XKeycode.KEY_RIGHT, XKeycode.KEY_PRTSCN),
)

private val NUMPAD_ROWS: List<List<XKeycode>> = listOf(
    listOf(XKeycode.KEY_NUM_LOCK, XKeycode.KEY_KP_DIVIDE, XKeycode.KEY_KP_MULTIPLY, XKeycode.KEY_KP_SUBTRACT),
    listOf(XKeycode.KEY_KP_7, XKeycode.KEY_KP_8, XKeycode.KEY_KP_9, XKeycode.KEY_KP_ADD),
    listOf(XKeycode.KEY_KP_4, XKeycode.KEY_KP_5, XKeycode.KEY_KP_6),
    listOf(XKeycode.KEY_KP_1, XKeycode.KEY_KP_2, XKeycode.KEY_KP_3, XKeycode.KEY_KP_ENTER),
    listOf(XKeycode.KEY_KP_0, XKeycode.KEY_KP_DEL),
)

private val MOUSE_CMDS: List<Pair<String, String>> = listOf(
    Pointer.Button.BUTTON_LEFT.name to "Left Click",
    Pointer.Button.BUTTON_RIGHT.name to "Right Click",
    Pointer.Button.BUTTON_MIDDLE.name to "Middle Click",
    Pointer.Button.BUTTON_SCROLL_UP.name to "Scroll Up",
    Pointer.Button.BUTTON_SCROLL_DOWN.name to "Scroll Down",
)

/** Compact display label for a key chip (symbols/arrows instead of the raw enum name). */
private fun keyLabel(k: XKeycode): String = when (k) {
    XKeycode.KEY_GRAVE -> "`"; XKeycode.KEY_MINUS -> "-"; XKeycode.KEY_EQUAL -> "="; XKeycode.KEY_BKSP -> "⌫"
    XKeycode.KEY_TAB -> "Tab"; XKeycode.KEY_BRACKET_LEFT -> "["; XKeycode.KEY_BRACKET_RIGHT -> "]"; XKeycode.KEY_BACKSLASH -> "\\"
    XKeycode.KEY_CAPS_LOCK -> "Caps"; XKeycode.KEY_SEMICOLON -> ";"; XKeycode.KEY_APOSTROPHE -> "'"; XKeycode.KEY_ENTER -> "⏎ Enter"
    XKeycode.KEY_SHIFT_L -> "⇧ Shift L"; XKeycode.KEY_SHIFT_R -> "⇧ Shift R"; XKeycode.KEY_COMMA -> ","; XKeycode.KEY_PERIOD -> "."; XKeycode.KEY_SLASH -> "/"
    XKeycode.KEY_CTRL_L -> "Ctrl L"; XKeycode.KEY_CTRL_R -> "Ctrl R"; XKeycode.KEY_ALT_L -> "Alt L"; XKeycode.KEY_ALT_R -> "Alt R"; XKeycode.KEY_SPACE -> "Space"
    XKeycode.KEY_INSERT -> "Ins"; XKeycode.KEY_HOME -> "Home"; XKeycode.KEY_PRIOR -> "PgUp"; XKeycode.KEY_DEL -> "Del"; XKeycode.KEY_END -> "End"; XKeycode.KEY_NEXT -> "PgDn"
    XKeycode.KEY_UP -> "↑"; XKeycode.KEY_LEFT -> "←"; XKeycode.KEY_DOWN -> "↓"; XKeycode.KEY_RIGHT -> "→"; XKeycode.KEY_PRTSCN -> "PrtSc"
    XKeycode.KEY_NUM_LOCK -> "Num"; XKeycode.KEY_KP_DIVIDE -> "/"; XKeycode.KEY_KP_MULTIPLY -> "*"; XKeycode.KEY_KP_SUBTRACT -> "-"; XKeycode.KEY_KP_ADD -> "+"
    XKeycode.KEY_KP_ENTER -> "⏎"; XKeycode.KEY_KP_DEL -> "."
    XKeycode.KEY_KP_0 -> "0"; XKeycode.KEY_KP_1 -> "1"; XKeycode.KEY_KP_2 -> "2"; XKeycode.KEY_KP_3 -> "3"; XKeycode.KEY_KP_4 -> "4"
    XKeycode.KEY_KP_5 -> "5"; XKeycode.KEY_KP_6 -> "6"; XKeycode.KEY_KP_7 -> "7"; XKeycode.KEY_KP_8 -> "8"; XKeycode.KEY_KP_9 -> "9"
    else -> k.name.removePrefix("KEY_")
}

/** A label + a dropdown selector for a list of (value, label) options. */
@Composable
private fun <T> LabeledDropdown(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    /** Optional d-pad nav: the trigger becomes a nav cell that CYCLES to the next option on A (a DropdownMenu popup is
     *  a separate window the controller-nav bridge can't reach, so cycling in-place is the d-pad-friendly path). Touch
     *  still opens the full menu. */
    nav: ScNavState? = null,
    navLine: Int = 0,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: "—"
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            // With nav: tapping/A opens a controller-navigable choice modal (shows all options). Without: a Material popup.
            val open = { if (nav != null) choosing = true else expanded = true }
            val trigger: @Composable () -> Unit = {
                OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedLabel, modifier = Modifier.weight(1f))
                    Text("▾")
                }
            }
            if (nav != null) {
                ScNavItem(nav, navLine, modifier = Modifier.fillMaxWidth(), onActivate = open) { trigger() }
            } else {
                trigger()
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp).background(MaterialTheme.colorScheme.surface),
            ) {
                options.forEach { (value, lbl) ->
                    DropdownMenuItem(
                        text = { Text(lbl, fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { onSelected(value); expanded = false },
                    )
                }
            }
        }
    }
    if (choosing) {
        ScNavChoiceDialog(
            title = label,
            options = options,
            selected = selected,
            onPick = onSelected,
            onDismiss = { choosing = false },
        )
    }
}

private fun EditActivator.uiLabel(): String = when (this) {
    EditActivator.REGULAR -> "Regular (press)"
    EditActivator.DOUBLE_PRESS -> "Double press"
    EditActivator.LONG_PRESS -> "Long press"
    EditActivator.TURBO -> "Turbo (rapid fire)"
    EditActivator.RELEASE -> "On release"
}

/** The ms setting label for an activator that has one, or null for those that don't (Regular / Release). */
private fun EditActivator.timingLabel(): String? = when (this) {
    EditActivator.DOUBLE_PRESS -> "Double-press window"
    EditActivator.LONG_PRESS -> "Hold time"
    EditActivator.TURBO -> "Repeat interval"
    else -> null
}

/** Default ms for an activator's timing (used when the binding's activatorMs is 0). */
private fun EditActivator.defaultMs(): Int = when (this) {
    EditActivator.DOUBLE_PRESS -> 300
    EditActivator.LONG_PRESS -> 500
    EditActivator.TURBO -> 80
    else -> 0
}

/** Compact label for a key in a combo summary: friendly modifier names, else the [keyLabel]-style short form. */
private fun comboKeyLabel(name: String): String = when (name) {
    XKeycode.KEY_CTRL_L.name, XKeycode.KEY_CTRL_R.name -> "Ctrl"
    XKeycode.KEY_SHIFT_L.name, XKeycode.KEY_SHIFT_R.name -> "Shift"
    XKeycode.KEY_ALT_L.name, XKeycode.KEY_ALT_R.name -> "Alt"
    else -> runCatching { keyLabel(XKeycode.valueOf(name)) }.getOrDefault(name.removePrefix("KEY_"))
}

private fun summarize(b: EditBinding): String {
    val out = when (b.kind) {
        OutputKind.NONE -> "Unbound"
        OutputKind.INHERIT -> b.inheritDesc.ifBlank { "Advanced (kept)" }
        OutputKind.KEY -> b.keys.joinToString("+") { comboKeyLabel(it) }.ifBlank { "Key" }
        OutputKind.GAMEPAD_BUTTON ->
            ScEditableProfile.GAMEPAD_BUTTONS.firstOrNull { it.first == b.gamepadIdx }?.second ?: "Pad button"
        OutputKind.GAMEPAD_DPAD ->
            ScEditableProfile.DPAD_DIRECTIONS.firstOrNull { it.first == b.dpadIndex }?.second ?: "Pad d-pad"
        OutputKind.MOUSE_BUTTON -> b.mouseButton.removePrefix("BUTTON_").ifBlank { "Mouse" }
        OutputKind.SWITCH_ACTION_SET -> "→ set ${b.targetSetId.ifBlank { "?" }}"
        OutputKind.MOUSE_NUDGE -> if (b.nudgeDx == 0 && b.nudgeDy == 0) "Center (no-op)" else "Nudge (${b.nudgeDx},${b.nudgeDy})"
        OutputKind.LAYER_OP -> "${b.layerOp.lowercase().replaceFirstChar { it.uppercase() }} layer ${b.layerId.ifBlank { "?" }}"
        OutputKind.SHOW_KEYBOARD -> "Show keyboard"
        OutputKind.OPEN_QUICK_MENU -> "Open QuickMenu"
    }
    val act = when (b.activator) {
        EditActivator.REGULAR -> ""
        EditActivator.DOUBLE_PRESS -> " · double"
        EditActivator.LONG_PRESS -> " · long"
        EditActivator.TURBO -> " · turbo"
        EditActivator.RELEASE -> " · release"
    }
    return out + act
}

// ---- Analog sources (Phase 2: per-surface behavior + settings) ----

/** The four analog surfaces the editor authors a behavior for, with get/set accessors on [ScEditableProfile]. */
private enum class AnalogSurface(val label: String, val isStick: Boolean, val location: ScMenuLocation) {
    LEFT_STICK("Left Stick", true, ScMenuLocation.LEFT_STICK),
    RIGHT_STICK("Right Stick", true, ScMenuLocation.RIGHT_STICK),
    LEFT_PAD("Left Pad", false, ScMenuLocation.LEFT_PAD),
    RIGHT_PAD("Right Pad", false, ScMenuLocation.RIGHT_PAD);

    fun get(p: ScEditableProfile): EditAnalog? = when (this) {
        LEFT_STICK -> p.leftStick; RIGHT_STICK -> p.rightStick; LEFT_PAD -> p.leftPad; RIGHT_PAD -> p.rightPad
    }

    fun set(p: ScEditableProfile, a: EditAnalog?): ScEditableProfile = when (this) {
        LEFT_STICK -> p.copy(leftStick = a); RIGHT_STICK -> p.copy(rightStick = a)
        LEFT_PAD -> p.copy(leftPad = a); RIGHT_PAD -> p.copy(rightPad = a)
    }

    /** Behaviors offered for this surface kind. Sticks: joystick/mouse/flick + radial/touch menu + d-pad.
     *  Pads: mouse/scroll + radial/touch menu + button-pad + d-pad. (Button-pad is pad-only.) */
    fun modes(): List<AnalogMode> = if (isStick)
        listOf(AnalogMode.JOYSTICK, AnalogMode.MOUSE, AnalogMode.FLICK_STICK, AnalogMode.RADIAL, AnalogMode.TOUCH_MENU, AnalogMode.DPAD, AnalogMode.NONE)
    else listOf(AnalogMode.MOUSE, AnalogMode.SCROLL_WHEEL, AnalogMode.RADIAL, AnalogMode.TOUCH_MENU, AnalogMode.BUTTON_PAD, AnalogMode.DPAD, AnalogMode.NONE)

    companion object { val ALL = entries }
}

@Composable
private fun AnalogSurfaceRow(surface: AnalogSurface, current: EditAnalog?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(surface.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(summarizeAnalog(current), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun summarizeAnalog(a: EditAnalog?): String = when (a?.mode) {
    null -> "Inherit (default)"
    AnalogMode.NONE -> "Off"
    AnalogMode.MOUSE -> "Mouse"
    AnalogMode.JOYSTICK -> "Joystick (${a.outputStick.lowercase()})"
    AnalogMode.FLICK_STICK -> "Flick stick"
    AnalogMode.SCROLL_WHEEL -> "Scroll wheel"
    AnalogMode.DPAD -> "D-Pad"
    AnalogMode.RADIAL -> "Radial menu (${a.slots.size})"
    AnalogMode.TOUCH_MENU -> "Touch menu (${a.menuCols}×${a.menuRows})"
    AnalogMode.BUTTON_PAD -> "Button pad (${a.menuCols}×${a.menuRows})"
}

@Composable
private fun AnalogPickerDialog(
    surface: AnalogSurface,
    current: EditAnalog?,
    onDismiss: () -> Unit,
    onApply: (EditAnalog?) -> Unit,
) {
    val inherit = "INHERIT"
    val modeOptions = listOf(inherit to "Inherit / keep current") + surface.modes().map { it.name to it.uiLabel() }
    var modeKey by remember { mutableStateOf(if (current == null) inherit else current.mode.name) }
    var sens by remember { mutableIntStateOf(current?.sensitivityPct ?: 100) }
    var dead by remember { mutableIntStateOf(current?.deadzonePct ?: 12) }
    var invertY by remember { mutableStateOf(current?.invertY ?: false) }
    var curve by remember { mutableStateOf(current?.curve ?: EditCurve.LINEAR) }
    var outStick by remember { mutableStateOf(current?.outputStick ?: "RIGHT") }
    var scrollStep by remember { mutableIntStateOf(current?.scrollStep ?: 6000) }

    // Menu / d-pad authoring state (used when the chosen mode is RADIAL / TOUCH_MENU / BUTTON_PAD / DPAD).
    var slots by remember { mutableStateOf(current?.slots ?: emptyList()) }
    var menuCols by remember { mutableIntStateOf(current?.menuCols?.takeIf { it > 0 } ?: 2) }
    var menuRows by remember { mutableIntStateOf(current?.menuRows?.takeIf { it > 0 } ?: 2) }
    var menuHold by remember { mutableStateOf(current?.menuHold ?: surface.isStick) }
    var menuOnClick by remember { mutableStateOf(current?.menuOnClick ?: false) }
    var menuDirectional by remember { mutableStateOf(current?.menuDirectional ?: false) }
    var menuCenter by remember { mutableStateOf(current?.menuCenter) }
    var dUp by remember { mutableStateOf(current?.up ?: EditBinding()) }
    var dDown by remember { mutableStateOf(current?.down ?: EditBinding()) }
    var dLeft by remember { mutableStateOf(current?.left ?: EditBinding()) }
    var dRight by remember { mutableStateOf(current?.right ?: EditBinding()) }
    // Nested binding-picker target: a slot index, the radial center (-1), or a d-pad direction key.
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    var editingDir by remember { mutableStateOf<String?>(null) }

    val mode = runCatching { AnalogMode.valueOf(modeKey) }.getOrNull()
    val isMenu = mode == AnalogMode.RADIAL || mode == AnalogMode.TOUCH_MENU || mode == AnalogMode.BUTTON_PAD
    val isGrid = mode == AnalogMode.TOUCH_MENU || mode == AnalogMode.BUTTON_PAD
    val showSens = mode == AnalogMode.MOUSE || mode == AnalogMode.FLICK_STICK
    val showDead = (mode == AnalogMode.JOYSTICK || mode == AnalogMode.MOUSE || mode == AnalogMode.FLICK_STICK ||
        ((mode == AnalogMode.RADIAL || mode == AnalogMode.TOUCH_MENU) && surface.isStick) || mode == AnalogMode.DPAD)
    val showInvert = mode == AnalogMode.MOUSE || mode == AnalogMode.JOYSTICK
    val showCurve = surface.isStick && (mode == AnalogMode.JOYSTICK || mode == AnalogMode.MOUSE)

    fun build(): EditAnalog = EditAnalog(
        mode = mode ?: AnalogMode.NONE, sensitivityPct = sens, deadzonePct = dead,
        invertY = invertY, curve = curve, outputStick = outStick, scrollStep = scrollStep,
        up = dUp, down = dDown, left = dLeft, right = dRight,
        slots = slots, menuCols = menuCols, menuRows = menuRows, menuOnClick = menuOnClick,
        menuHold = menuHold, menuCenter = menuCenter, menuDirectional = menuDirectional,
    )
    val nav = remember { ScNavState() }

    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.95f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text("${surface.label} behavior") },
        text = {
            val doApply = { if (modeKey == inherit) onApply(null) else onApply(build()) }
            ScNavDialogColumn(nav, onBack = onDismiss, modifier = Modifier.heightIn(max = 520.dp).verticalScrollWithBar()) {
                LabeledDropdown("Behavior", modeOptions, modeKey, nav = nav, navLine = 0) { modeKey = it }
                if (showSens) { Spacer(Modifier.height(8.dp)); AnalogSlider("Sensitivity", sens, 25, 400, "%", nav = nav, navLine = 1) { sens = it } }
                if (showDead) { Spacer(Modifier.height(8.dp)); AnalogSlider("Deadzone", dead, 0, 50, "%", nav = nav, navLine = 2) { dead = it } }
                if (mode == AnalogMode.SCROLL_WHEEL) { Spacer(Modifier.height(8.dp)); AnalogSlider("Scroll step", scrollStep, 1000, 12000, "", nav = nav, navLine = 3) { scrollStep = it } }
                if (mode == AnalogMode.JOYSTICK) { Spacer(Modifier.height(8.dp)); LabeledDropdown("Output stick", listOf("LEFT" to "Left stick", "RIGHT" to "Right stick"), outStick, nav = nav, navLine = 4) { outStick = it } }
                if (showCurve) { Spacer(Modifier.height(8.dp)); LabeledDropdown("Response curve", EditCurve.entries.map { it to it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, curve, nav = nav, navLine = 5) { curve = it } }
                if (showInvert) { Spacer(Modifier.height(8.dp)); AnalogToggle("Invert Y", invertY, nav = nav, navLine = 6) { invertY = it } }

                // ── D-Pad: four direction binds ──
                if (mode == AnalogMode.DPAD) {
                    Spacer(Modifier.height(8.dp))
                    val dirs = listOf("UP" to dUp, "DOWN" to dDown, "LEFT" to dLeft, "RIGHT" to dRight)
                    dirs.forEachIndexed { i, (dir, b) ->
                        val open = { editingDir = dir }
                        ScNavItem(nav, 7 + i, modifier = Modifier.fillMaxWidth(), onActivate = open) {
                            DetailRow(dir.lowercase().replaceFirstChar { it.uppercase() }, summarize(b), open)
                        }
                    }
                }

                // ── Menus: grid dims + commit/onClick + slot list (+ radial center/directional) ──
                if (isMenu) {
                    Spacer(Modifier.height(8.dp))
                    if (isGrid) {
                        AnalogSlider("Columns", menuCols, 1, 6, "", nav = nav, navLine = 11) { menuCols = it }
                        AnalogSlider("Rows", menuRows, 1, 6, "", nav = nav, navLine = 12) { menuRows = it }
                    }
                    if (mode != AnalogMode.BUTTON_PAD) {
                        AnalogToggle("Hold (else commit on release)", menuHold, nav = nav, navLine = 13) { menuHold = it }
                    }
                    if (!surface.isStick) AnalogToggle("Commit on pad click", menuOnClick, nav = nav, navLine = 14) { menuOnClick = it }
                    if (mode == AnalogMode.RADIAL) {
                        AnalogToggle("Directional (8-way movement)", menuDirectional, nav = nav, navLine = 15) { menuDirectional = it }
                        val openCenter = { editingSlot = -1 }
                        ScNavItem(nav, 16, modifier = Modifier.fillMaxWidth(), onActivate = openCenter) {
                            DetailRow("Center button", menuCenter?.let { summarize(it.binding) } ?: "None", openCenter)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(if (mode == AnalogMode.BUTTON_PAD) "Cells (row-major)" else "Slots", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    slots.forEachIndexed { i, s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}.", modifier = Modifier.width(24.dp), style = MaterialTheme.typography.bodyMedium)
                            val editSlot = { editingSlot = i }
                            val removeSlot = { slots = slots.toMutableList().also { it.removeAt(i) } }
                            ScNavItem(nav, 20 + i, col = 0, modifier = Modifier.weight(1f), onActivate = editSlot) {
                                Box(Modifier.fillMaxWidth().clickable { editSlot() }) {
                                    Text(
                                        (s.label.ifBlank { "(no label)" }) + " — " + summarize(s.binding),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            ScNavItem(nav, 20 + i, col = 1, onActivate = removeSlot) { TextButton(onClick = removeSlot) { Text("✕") } }
                        }
                    }
                    val addSlot = { slots = slots + EditMenuSlot() }
                    ScNavItem(nav, 999, modifier = Modifier.fillMaxWidth(), onActivate = addSlot) {
                        TextButton(onClick = addSlot) { Text("+ Add ${if (mode == AnalogMode.BUTTON_PAD) "cell" else "slot"}") }
                    }
                }
                NavApplyCancelRow(nav, onApply = doApply, onCancel = onDismiss)
            }
        },
        confirmButton = {},
    )

    // Nested command picker for a slot / the radial center / a d-pad direction.
    editingSlot?.let { idx ->
        val cur = if (idx == -1) (menuCenter?.binding ?: EditBinding()) else slots.getOrNull(idx)?.binding ?: EditBinding()
        val curLabel = if (idx == -1) (menuCenter?.label ?: "") else slots.getOrNull(idx)?.label ?: ""
        SlotPickerDialog(
            title = if (idx == -1) "Center button" else "Slot ${idx + 1}",
            currentBinding = cur, currentLabel = curLabel, showLabel = mode != AnalogMode.BUTTON_PAD,
            onDismiss = { editingSlot = null },
            onApply = { b, lbl ->
                if (idx == -1) menuCenter = EditMenuSlot(lbl, b)
                else slots = slots.toMutableList().also { it[idx] = EditMenuSlot(lbl, b) }
                editingSlot = null
            },
        )
    }
    editingDir?.let { dir ->
        BindingPickerDialog(
            title = "D-Pad $dir",
            current = when (dir) { "UP" -> dUp; "DOWN" -> dDown; "LEFT" -> dLeft; else -> dRight },
            onDismiss = { editingDir = null },
            onApply = { b -> when (dir) { "UP" -> dUp = b; "DOWN" -> dDown = b; "LEFT" -> dLeft = b; else -> dRight = b }; editingDir = null },
            showActivator = false,
        )
    }
}

/** A slot editor: the shared command picker plus an optional slot-label field (menus show a HUD label; button-pad
 *  cells don't). Wraps [BindingPickerDialog] and threads the label through. */
@Composable
private fun SlotPickerDialog(
    title: String,
    currentBinding: EditBinding,
    currentLabel: String,
    showLabel: Boolean,
    onDismiss: () -> Unit,
    onApply: (EditBinding, String) -> Unit,
) {
    var label by remember { mutableStateOf(currentLabel) }
    // The label field lives above the picker; the picker's Apply carries the label out with the chosen binding.
    BindingPickerDialog(
        title = title,
        current = currentBinding,
        onDismiss = onDismiss,
        onApply = { b -> onApply(b, label) },
        headerContent = if (showLabel) {
            {
                ScTextEditField(
                    label = "Slot label (HUD)",
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
        } else null,
    )
}

@Composable
private fun AnalogSlider(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    suffix: String,
    nav: ScNavState? = null,
    navLine: Int = 0,
    onChange: (Int) -> Unit,
) {
    // With nav: d-pad LEFT/RIGHT nudge the value 1 point at a time (hold to auto-repeat for larger ranges).
    val nudge: (Int) -> Unit = { d -> onChange((value + d).coerceIn(min, max)) }
    val body: @Composable () -> Unit = {
        Column {
            Text("$label: $value$suffix" + if (nav != null) "   ◀ ▶" else "", style = MaterialTheme.typography.labelMedium)
            Slider(value = value.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = min.toFloat()..max.toFloat())
        }
    }
    if (nav != null) {
        ScNavItem(nav, navLine, modifier = Modifier.fillMaxWidth(), onHorizontal = nudge, onActivate = {}) { body() }
    } else {
        body()
    }
}

@Composable
private fun AnalogToggle(label: String, value: Boolean, nav: ScNavState? = null, navLine: Int = 0, onChange: (Boolean) -> Unit) {
    val toggle = { onChange(!value) }
    val body: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = toggle) { Text(if (value) "On" else "Off") }
        }
    }
    if (nav != null) {
        ScNavItem(nav, navLine, modifier = Modifier.fillMaxWidth(), onActivate = toggle) { body() }
    } else {
        body()
    }
}

/** The standard d-pad-navigable Cancel/Apply row at the bottom of an SC sub-dialog's nav column (always the last line). */
@Composable
private fun NavApplyCancelRow(nav: ScNavState, onApply: () -> Unit, onCancel: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScNavItem(nav, 9999, 0, onActivate = onCancel) { TextButton(onClick = onCancel) { Text("Cancel") } }
        Spacer(Modifier.width(8.dp))
        ScNavItem(nav, 9999, 1, onActivate = onApply) { Button(onClick = onApply) { Text("Apply") } }
    }
}

private fun AnalogMode.uiLabel(): String = when (this) {
    AnalogMode.NONE -> "Off"
    AnalogMode.MOUSE -> "Mouse"
    AnalogMode.JOYSTICK -> "Joystick"
    AnalogMode.FLICK_STICK -> "Flick stick"
    AnalogMode.SCROLL_WHEEL -> "Scroll wheel"
    AnalogMode.DPAD -> "D-Pad"
    AnalogMode.RADIAL -> "Radial menu"
    AnalogMode.TOUCH_MENU -> "Touch menu"
    AnalogMode.BUTTON_PAD -> "Button pad"
}
