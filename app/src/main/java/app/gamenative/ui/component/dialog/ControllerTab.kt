package app.gamenative.ui.component.dialog

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.steamcontroller.ScConfigStore
import app.gamenative.steamcontroller.ScTuningStore
import kotlin.math.roundToInt
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch
import com.winlator.container.Container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ControllerTabContent(state: ContainerConfigState, default: Boolean, containerId: String? = null) {
    val config = state.config.value

    SettingsGroup() {
        if (!default) {
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.use_sdl_api)) },
                state = config.sdlControllerAPI,
                onCheckedChange = { state.config.value = config.copy(sdlControllerAPI = it) },
            )
        }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.use_steam_input)) },
            state = config.useSteamInput,
            onCheckedChange = { state.config.value = config.copy(useSteamInput = it) },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.enable_xinput_api)) },
            state = config.enableXInput,
            onCheckedChange = { state.config.value = config.copy(enableXInput = it) },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.enable_directinput_api)) },
            state = config.enableDInput,
            onCheckedChange = { state.config.value = config.copy(enableDInput = it) },
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.directinput_mapper_type)) },
            value = if (config.dinputMapperType == 1.toByte()) 0 else 1,
            items = listOf("Standard", "XInput Mapper"),
            onItemSelected = { index ->
                state.config.value = config.copy(dinputMapperType = if (index == 0) 1 else 2)
            },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.shooter_mode_toggle)) },
            subtitle = { Text(text = stringResource(R.string.shooter_mode_toggle_description)) },
            state = config.shooterMode,
            onCheckedChange = { state.config.value = config.copy(shooterMode = it) },
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.external_display_input)) },
            subtitle = { Text(text = stringResource(R.string.external_display_input_subtitle)) },
            value = state.externalDisplayModeIndex.value,
            items = state.externalDisplayModes,
            onItemSelected = { index ->
                state.externalDisplayModeIndex.value = index
                state.config.value = config.copy(
                    externalDisplayMode = when (index) {
                        1 -> Container.EXTERNAL_DISPLAY_MODE_TOUCHPAD
                        2 -> Container.EXTERNAL_DISPLAY_MODE_KEYBOARD
                        3 -> Container.EXTERNAL_DISPLAY_MODE_HYBRID
                        else -> Container.EXTERNAL_DISPLAY_MODE_OFF
                    },
                )
            },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.external_display_swap)) },
            subtitle = { Text(text = stringResource(R.string.external_display_swap_subtitle)) },
            state = config.externalDisplaySwap,
            onCheckedChange = { state.config.value = config.copy(externalDisplaySwap = it) },
        )
    }

    // Steam Controller (Triton) config: per-game for a real container, or the shared default in the default
    // template editor (applies to any game without its own config).
    val scKey = containerId ?: if (default) ScConfigStore.DEFAULT_KEY else null
    if (scKey != null) {
        SteamControllerConfigSection(scKey, isShared = scKey == ScConfigStore.DEFAULT_KEY)
    }
}

/**
 * Lets the user author/import a Steam Controller mapping (persisted via [ScConfigStore] keyed by [storeKey]) and
 * remove it. [storeKey] is the game's container/appId for a per-game config, or [ScConfigStore.DEFAULT_KEY] when
 * [isShared] — the shared default applied to any game without its own. The live driver
 * ([app.gamenative.steamcontroller.TritonMapper]) loads this on launch (action sets / layers / mode-shift),
 * falling back to the built-in default mapping when absent.
 */
@Composable
private fun SteamControllerConfigSection(storeKey: String, isShared: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bumped after import/remove/edit to recompute the status line.
    var version by remember { mutableIntStateOf(0) }
    var showEditor by remember { mutableStateOf(false) }
    var showLabelEditor by remember { mutableStateOf(false) }
    var showOverlayEditor by remember { mutableStateOf(false) }
    var showKbOverlayEditor by remember { mutableStateOf(false) }
    // Global touchpad-feel knobs (hardware/grip dependent, so user-tweakable): deadzone = rest-freeze radius;
    // smoothing = low-pass during motion (+ keyboard cursor).
    var deadzone by remember { mutableIntStateOf(ScTuningStore.deadzone(context)) }
    var smoothing by remember { mutableIntStateOf(ScTuningStore.smoothing(context)) }
    var menuCommit by remember { mutableIntStateOf(ScTuningStore.menuCommit(context)) }
    val configs = remember(storeKey, version) { ScConfigStore.listConfigs(context, storeKey) }
    val activeId = remember(storeKey, version) { ScConfigStore.activeConfigId(context, storeKey) }
    val activeIdx = configs.indexOfFirst { it.id == activeId }.let { if (it < 0) 0 else it }
    val activeEntry = configs.getOrNull(activeIdx)
    val hasLabels = remember(storeKey, version) { ScConfigStore.hasLabels(context, storeKey) }

    // Naming dialog for Duplicate / Rename.
    var nameDialog by remember { mutableStateOf<NameAction?>(null) }

    val builtInFallback = if (isShared) "None — games use the built-in default mapping"
    else "None — using the shared default / built-in default mapping"
    val status = if (activeEntry == null) builtInFallback
    else "${configs.size} saved config(s) · active: ${activeEntry.name}"

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
                    .getOrNull()
            }
            if (text.isNullOrBlank()) {
                SnackbarManager.show("Could not read the selected file")
                return@launch
            }
            val parsed = withContext(Dispatchers.IO) { ScConfigStore.validate(text) }
            if (parsed == null) {
                SnackbarManager.show("Not a valid Steam Controller .vdf config")
                return@launch
            }
            val newId = withContext(Dispatchers.IO) { ScConfigStore.importVdfConfig(context, storeKey, text) }
            version++
            SnackbarManager.show(
                if (newId != null) "Imported ${parsed.sets.size} action set(s) — now active"
                else "Could not save the imported config",
            )
        }
    }

    val groupTitle = if (isShared) "Steam Controller (Triton) — shared default" else "Steam Controller (Triton)"
    SettingsGroup(title = { Text(text = groupTitle) }) {
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = if (isShared) "Default mapping for all games" else "Controller mapping") },
            subtitle = { Text(text = status) },
            onClick = {},
        )
        // Saved-config selector: pick which config is active (loaded on launch). Editing saves into the active one.
        if (configs.isNotEmpty()) {
            SettingsListDropdown(
                colors = settingsTileColorsAlt(),
                title = { Text(text = "Active config") },
                subtitle = { Text(text = "Loaded on launch; edits save into this config.") },
                value = activeIdx,
                items = configs.map { it.name + if (it.kind == app.gamenative.steamcontroller.ScConfigKind.VDF) "  (.vdf)" else "  (custom)" },
                onItemSelected = { idx ->
                    configs.getOrNull(idx)?.let {
                        if (ScConfigStore.setActiveConfig(context, storeKey, it.id)) {
                            version++
                            SnackbarManager.show("Active config: ${it.name}")
                        }
                    }
                },
            )
            SettingsMenuLink(
                colors = settingsTileColors(),
                title = { Text(text = "Duplicate active config") },
                subtitle = { Text(text = "Copy it to a new config you can tweak as a fallback") },
                onClick = { activeEntry?.let { nameDialog = NameAction(NameActionKind.DUPLICATE, it.id, "${it.name} copy") } },
            )
            SettingsMenuLink(
                colors = settingsTileColors(),
                title = { Text(text = "Rename active config") },
                subtitle = { Text(text = activeEntry?.name ?: "") },
                onClick = { activeEntry?.let { nameDialog = NameAction(NameActionKind.RENAME, it.id, it.name) } },
            )
            SettingsMenuLink(
                colors = settingsTileColorsAlt(),
                title = { Text(text = "Delete active config") },
                subtitle = { Text(text = "Remove it; falls back to another saved config or the built-in default") },
                onClick = {
                    activeEntry?.let {
                        if (ScConfigStore.deleteConfig(context, storeKey, it.id)) {
                            version++
                            SnackbarManager.show("Deleted ${it.name}")
                        }
                    }
                },
            )
        }
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(text = "Edit bindings (built-in editor)") },
            subtitle = { Text(text = "Buttons/paddles/grips (command picker + activators), sticks/pads, triggers, gyro, haptics") },
            onClick = { showEditor = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = "Edit menu labels") },
            subtitle = { Text(text = "Rename radial / touch-menu slots shown on the overlay") },
            onClick = { showLabelEditor = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = "Adjust overlay HUD (position & size)") },
            subtitle = {
                Text(
                    text = if (isShared) "Drag + pinch the radial/grid HUD; applies to all games"
                    else "Drag + pinch the radial/grid HUD for this game (falls back to global)",
                )
            },
            onClick = { showOverlayEditor = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = "Adjust on-screen keyboard (position & size)") },
            subtitle = {
                Text(
                    text = if (isShared) "Drag + pinch the split keyboard; applies to all games"
                    else "Drag + pinch the split keyboard for this game (falls back to global)",
                )
            },
            onClick = { showKbOverlayEditor = true },
        )
        // Touchpad deadzone: rest-freeze radius on relative-mouse pads. Within it the cursor doesn't move at all
        // (kills resting jitter); higher = stiller at rest, lower = more sensitive to tiny movements. Global.
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text = "Touchpad deadzone (global): $deadzone")
            Slider(
                value = deadzone.toFloat(),
                onValueChange = { deadzone = it.roundToInt().coerceIn(ScTuningStore.MIN_DEADZONE, ScTuningStore.MAX_DEADZONE) },
                onValueChangeFinished = { ScTuningStore.setDeadzone(context, deadzone) },
                valueRange = ScTuningStore.MIN_DEADZONE.toFloat()..ScTuningStore.MAX_DEADZONE.toFloat(),
            )
            Text(text = "Higher = steadier cursor at rest; lower = more sensitive. Applies on next launch.")
        }
        // Touchpad smoothing: low-pass on motion (and the on-screen keyboard cursor). Higher = smoother but laggier.
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text = "Touchpad smoothing (global): $smoothing%")
            Slider(
                value = smoothing.toFloat(),
                onValueChange = { smoothing = it.roundToInt().coerceIn(ScTuningStore.MIN_SMOOTHING, ScTuningStore.MAX_SMOOTHING) },
                onValueChangeFinished = { ScTuningStore.setSmoothing(context, smoothing) },
                valueRange = ScTuningStore.MIN_SMOOTHING.toFloat()..ScTuningStore.MAX_SMOOTHING.toFloat(),
            )
            Text(text = "Higher = smoother motion + keyboard cursor, but more lag. 0 = off. Applies on next launch.")
        }
        // Touch/radial-menu commit style (global): when does a highlighted menu slot fire? "Use config default"
        // honors each menu's own requires_click; the others force click or point-and-release for every menu.
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = "Touch-menu commit (global)") },
            subtitle = { Text(text = "When a menu slot fires. Applies on next launch.") },
            value = menuCommit,
            items = listOf("Use config default", "Click to commit", "Release to commit"),
            onItemSelected = { index ->
                menuCommit = index
                ScTuningStore.setMenuCommit(context, index)
            },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = "Import config (.vdf)") },
            subtitle = { Text(text = "Use a Steam Input config (action sets / layers / pad modes)") },
            onClick = {
                // Steam exports are .vdf (text); accept any type since SAF rarely registers a .vdf MIME.
                importLauncher.launch(arrayOf("*/*"))
            },
        )
        if (hasLabels) {
            SettingsMenuLink(
                colors = settingsTileColorsAlt(),
                title = { Text(text = "Remove custom menu labels") },
                subtitle = { Text(text = "Revert menu slots to their default labels") },
                onClick = {
                    if (ScConfigStore.removeLabels(context, storeKey)) {
                        version++
                        SnackbarManager.show("Removed")
                    }
                },
            )
        }
    }

    if (showEditor) {
        SteamControllerBindingEditorDialog(
            containerId = storeKey,
            onDismiss = {
                showEditor = false
                version++
            },
        )
    }

    if (showLabelEditor) {
        ScMenuLabelEditorDialog(
            storeKey = storeKey,
            onDismiss = {
                showLabelEditor = false
                version++
            },
        )
    }

    if (showOverlayEditor) {
        ScOverlayEditorDialog(
            storeKey = storeKey,
            isShared = isShared,
            target = ScOverlayTarget.MENU,
            onDismiss = { showOverlayEditor = false },
        )
    }
    if (showKbOverlayEditor) {
        ScOverlayEditorDialog(
            storeKey = storeKey,
            isShared = isShared,
            target = ScOverlayTarget.KEYBOARD,
            onDismiss = { showKbOverlayEditor = false },
        )
    }

    nameDialog?.let { action ->
        var text by remember(action) { mutableStateOf(action.initial) }
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(if (action.kind == NameActionKind.DUPLICATE) "Duplicate config" else "Rename config") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Config name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = when (action.kind) {
                        NameActionKind.DUPLICATE -> ScConfigStore.duplicateConfig(context, storeKey, action.configId, text) != null
                        NameActionKind.RENAME -> ScConfigStore.renameConfig(context, storeKey, action.configId, text)
                    }
                    nameDialog = null
                    if (ok) { version++; SnackbarManager.show(if (action.kind == NameActionKind.DUPLICATE) "Duplicated" else "Renamed") }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("Cancel") } },
        )
    }
}

private enum class NameActionKind { DUPLICATE, RENAME }

/** A pending name-entry dialog for [ScConfigStore] duplicate/rename of config [configId]. */
private data class NameAction(val kind: NameActionKind, val configId: String, val initial: String)
