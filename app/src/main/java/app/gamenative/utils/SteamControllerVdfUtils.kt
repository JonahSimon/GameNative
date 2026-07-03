package app.gamenative.utils

import app.gamenative.steamcontroller.Activator
import app.gamenative.steamcontroller.Binding
import app.gamenative.steamcontroller.GyroGate
import app.gamenative.steamcontroller.GyroMode
import app.gamenative.steamcontroller.LayerOpType
import app.gamenative.steamcontroller.MenuSlot
import app.gamenative.steamcontroller.PadMode
import app.gamenative.steamcontroller.ScConfig
import app.gamenative.steamcontroller.ScOutput
import app.gamenative.steamcontroller.ScProfile
import app.gamenative.steamcontroller.Stick
import app.gamenative.steamcontroller.StickMode
import app.gamenative.steamcontroller.TriggerAxis
import app.gamenative.steamcontroller.TriggerMode
import app.gamenative.steamcontroller.TritonProtocol
import com.winlator.inputcontrols.ExternalController
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.math.sqrt
import timber.log.Timber

object SteamControllerVdfUtils {
    private val keymapDigital = mapOf(
        "button_a" to "A",
        "button_b" to "B",
        "button_x" to "X",
        "button_y" to "Y",
        "dpad_north" to "DUP",
        "dpad_south" to "DDOWN",
        "dpad_east" to "DRIGHT",
        "dpad_west" to "DLEFT",
        "button_escape" to "START",
        "button_menu" to "BACK",
        "left_bumper" to "LBUMPER",
        "right_bumper" to "RBUMPER",
        "button_back_left" to "A",
        "button_back_right" to "X",
        "button_back_left_upper" to "B",
        "button_back_right_upper" to "Y",
    )

    fun generateControllerConfig(controllerVdfText: String, outputDir: Path) {
        val root = VdfParser(controllerVdfText).parse()
        val controllerMappings = root.getObject("controller_mappings") ?: return

        val groupsById = LinkedHashMap<String, VdfObject>()
        controllerMappings.getObjects("group").forEach { group ->
            group.getString("id")?.let { groupsById[it] = group }
        }

        val actionList = mutableListOf<String>()
        controllerMappings.getObjects("actions").forEach { actions ->
            actionList.addAll(actions.keys())
        }

        val presets = controllerMappings.getObjects("preset")
        val presetsByName = presets.mapNotNull { preset ->
            preset.getString("name")?.let { name -> name to preset }
        }.toMap()
        val allBindings = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()

        for (preset in presets) {
            val name = preset.getString("name") ?: continue
            if (!actionList.contains(name) && name.lowercase() != "default") continue

            val bindings = buildPresetBindings(name, preset, groupsById)
            allBindings[name] = bindings
        }

        controllerMappings.getObject("action_layers")?.keys()?.forEach { layerName ->
            val preset = presetsByName[layerName]
            if (preset == null) {
                Timber.tag("SteamControllerVdf").d("Missing preset for action layer $layerName")
                return@forEach
            }
            val bindings = buildPresetBindings(layerName, preset, groupsById)
            allBindings[layerName] = bindings
        }

        if (allBindings.isEmpty()) return

        Files.createDirectories(outputDir)
        for ((presetName, bindings) in allBindings) {
            val outputFile = outputDir.resolve("$presetName.txt")
            val content = buildString {
                for ((actionName, actionBindings) in bindings) {
                    append(actionName)
                    append("=")
                    appendLine(actionBindings.joinToString(","))
                }
            }
            outputFile.toFile().writeText(content, Charsets.UTF_8)
        }
    }

    private fun addInputBindings(
        group: VdfObject,
        bindings: MutableMap<String, MutableList<String>>,
        forceBinding: String? = null,
        keymap: Map<String, String> = keymapDigital,
    ) {
        val inputs = group.getObject("inputs") ?: return
        for ((inputName, inputValue) in inputs.objectEntries()) {
            for (activator in inputValue.objectValues()) {
                for (fullPress in activator.objectValues()) {
                    for (bindingGroup in fullPress.objectValues()) {
                        for ((bindingKey, bindingValue) in bindingGroup.stringEntries()) {
                            if (!bindingKey.equals("binding", ignoreCase = true)) continue
                            val tokens = bindingValue.split(Regex("\\s+"))
                            if (tokens.isEmpty()) continue

                            val actionName = when (tokens[0].lowercase()) {
                                "game_action" -> tokens.getOrNull(2)?.trimEnd(',')
                                "xinput_button" -> tokens.getOrNull(1)?.trimEnd(',')
                                else -> null
                            }

                            if (actionName.isNullOrEmpty()) continue

                            val binding = forceBinding ?: keymap[inputName.lowercase()]
                            if (binding.isNullOrEmpty()) {
                                Timber.tag("SteamControllerVdf").d("Missing keymap for $inputName")
                                continue
                            }

                            val list = bindings.getOrPut(actionName) { mutableListOf() }
                            if (!list.contains(binding)) {
                                list.add(binding)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun addActionBinding(
        bindings: MutableMap<String, MutableList<String>>,
        actionName: String,
        binding: String,
        bindingSuffix: String,
    ) {
        val list = bindings.getOrPut(actionName) { mutableListOf() }
        val bindingWithSuffix = "$binding=$bindingSuffix"
        if (!list.contains(binding) && !list.contains(bindingWithSuffix)) {
            if (list.isEmpty()) {
                list.add(bindingWithSuffix)
            } else {
                list.add(0, binding)
            }
        }
    }

    private fun buildPresetBindings(
        presetName: String,
        preset: VdfObject,
        groupsById: Map<String, VdfObject>,
    ): LinkedHashMap<String, MutableList<String>> {
        val groupBindings = preset.getObject("group_source_bindings") ?: return LinkedHashMap()
        val bindings = LinkedHashMap<String, MutableList<String>>()

        for ((groupId, groupBinding) in groupBindings.stringEntries()) {
            val tokens = groupBinding.split(Regex("\\s+"))
            if (tokens.size < 2 || tokens[1].lowercase() != "active") continue

            val group = groupsById[groupId] ?: continue
            val groupMode = group.getString("mode")?.lowercase().orEmpty()
            val bindingType = tokens[0].lowercase()

            if (bindingType in listOf("switch", "button_diamond", "dpad")) {
                addInputBindings(group, bindings)
            }

            if (bindingType in listOf("left_trigger", "right_trigger")) {
                if (groupMode == "trigger") {
                    val actionName = group.getObject("gameactions")?.getString(presetName)
                    if (!actionName.isNullOrEmpty()) {
                        val binding = if (bindingType == "left_trigger") "LTRIGGER" else "RTRIGGER"
                        addActionBinding(bindings, actionName, binding, bindingSuffix = "trigger")
                    }
                    val forceBinding = if (bindingType == "left_trigger") "DLTRIGGER" else "DRTRIGGER"
                    addInputBindings(group, bindings, forceBinding = forceBinding)
                } else {
                    Timber.tag("SteamControllerVdf").d("Unhandled trigger mode: $groupMode")
                }
            }

            if (bindingType in listOf("joystick", "right_joystick", "dpad")) {
                if (groupMode == "joystick_move") {
                    val actionName = group.getObject("gameactions")?.getString(presetName)
                    if (!actionName.isNullOrEmpty()) {
                        val binding = when (bindingType) {
                            "joystick" -> "LJOY"
                            "right_joystick" -> "RJOY"
                            "dpad" -> "DPAD"
                            else -> ""
                        }
                        if (binding.isNotEmpty()) {
                            addActionBinding(bindings, actionName, binding, bindingSuffix = "joystick_move")
                        }
                    }
                    val forceBinding = if (bindingType == "joystick") "LSTICK" else "RSTICK"
                    addInputBindings(group, bindings, forceBinding = forceBinding)
                } else if (groupMode == "dpad") {
                    if (bindingType == "joystick") {
                        val bindingMap = mapOf(
                            "dpad_north" to "DLJOYUP",
                            "dpad_south" to "DLJOYDOWN",
                            "dpad_west" to "DLJOYLEFT",
                            "dpad_east" to "DLJOYRIGHT",
                            "click" to "LSTICK",
                        )
                        addInputBindings(group, bindings, keymap = bindingMap)
                    } else if (bindingType == "right_joystick") {
                        val bindingMap = mapOf(
                            "dpad_north" to "DRJOYUP",
                            "dpad_south" to "DRJOYDOWN",
                            "dpad_west" to "DRJOYLEFT",
                            "dpad_east" to "DRJOYRIGHT",
                            "click" to "RSTICK",
                        )
                        addInputBindings(group, bindings, keymap = bindingMap)
                    }
                }
            }
        }

        return bindings
    }
}

/**
 * Imports a Steam Input `.vdf` controller config (`controller_mappings`) into our [ScProfile] model — the
 * "coverage guarantee" of docs/STEAM-INPUT-COVERAGE.md: if we can parse any Triton config into the profile
 * model and run it through [app.gamenative.steamcontroller.ProfileInterpreter], we cover the practical surface.
 *
 * Handles both schema variants the configurator writes:
 *  - **v3** (current Triton): `preset { group_source_bindings }`, group `inputs { <name> { activators { <Type>
 *    { bindings { binding } settings } } } }`. (e.g. `chord_triton.vdf`, the bundled xboxone templates.)
 *  - **v2** (older): top-level `group_source_bindings` + `switch_bindings`, flat group `bindings { <name>
 *    <bindingString> }`. (e.g. `gamepad_joystick.vdf`.)
 *
 * Maps each `group_source_bindings` *source* (button_diamond / switch / dpad / joystick / right_joystick /
 * left_trackpad / right_trackpad / left_trigger / right_trigger / gyro) onto the matching [ScProfile] field,
 * each binding string onto an [ScOutput], and each activator onto an [Activator]. Features our model can't yet
 * represent (stick-as-dpad, absolute/region mouse precision, button_pad grids, most `controller_action`s) are
 * logged and left unbound rather than mis-imported — keeping the importer honest against the coverage matrix.
 *
 * Reuses the file-private [VdfParser]. Pure logic, unit-tested on PC (SteamControllerProfileImporterTest).
 */
object SteamControllerProfileImporter {
    private const val TAG = "ScVdfImport"

    /** Parse [vdfText] into a profile, using the named [presetName] action set (falls back to the first preset). */
    fun import(vdfText: String, presetName: String = "Default"): ScProfile {
        val cm = parseMappings(vdfText) ?: return ScProfile(name = "Imported (empty)")
        val groupsById = indexGroups(cm)
        val presets = cm.getObjects("preset")
        val preset = presets.firstOrNull { it.getString("name").equals(presetName, ignoreCase = true) }
            ?: presets.firstOrNull()
        val name = cm.getString("title")?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: "Imported"
        return buildProfile(cm, groupsById, preset, name).first
    }

    /**
     * Import **every action set** in the config into a name→profile map (ordered as the config lists them).
     * Steam "action sets" (the `actions` block — e.g. KSP's Menu/Flight/Docking/EVA, ToME4's Main/Extra) each
     * have their own `preset`/`group_source_bindings`; this decodes all of them so a future action-set switcher
     * (build step 3) or the binding-editor can present them. Falls back to a single "Default" entry when the
     * config has no `actions` block (plain templates). Pure data — does not wire any runtime switching.
     */
    fun importActionSets(vdfText: String): LinkedHashMap<String, ScProfile> {
        val out = LinkedHashMap<String, ScProfile>()
        val cm = parseMappings(vdfText) ?: return out
        val groupsById = indexGroups(cm)
        val presets = cm.getObjects("preset")
        val actionKeys = cm.getObject("actions")?.keys().orEmpty()
        if (actionKeys.isEmpty()) {
            out["Default"] = import(vdfText)
            return out
        }
        for (key in actionKeys) {
            val preset = presets.firstOrNull { it.getString("name").equals(key, ignoreCase = true) }
            if (preset == null) { Timber.tag(TAG).d("action set '$key' has no matching preset -> skipped"); continue }
            val title = cm.getObject("actions")?.getObject(key)?.getString("title")
                ?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: key
            out[title] = buildProfile(cm, groupsById, preset, title).first
        }
        return out
    }

    /**
     * Import the whole config as an [ScConfig]: every `preset` decoded and **keyed by its Steam preset id**
     * (what `CHANGE_PRESET` / [ScOutput.SwitchActionSet] target), plus the launch set. Feed this to
     * [app.gamenative.steamcontroller.ProfileInterpreter] for config-driven action-set switching.
     */
    fun importConfig(vdfText: String): ScConfig {
        val cm = parseMappings(vdfText) ?: return ScConfig(emptyMap(), "")
        val groupsById = indexGroups(cm)
        val presets = cm.getObjects("preset")
        val sets = LinkedHashMap<String, ScProfile>()
        val setSources = LinkedHashMap<String, Set<String>>()
        for (preset in presets) {
            val id = preset.getString("id") ?: continue
            val name = preset.getString("name")?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: "Set $id"
            val (profile, sources) = buildProfile(cm, groupsById, preset, name)
            sets[id] = profile
            setSources[id] = sources
        }
        val defaultId = presets.firstOrNull { it.getString("name").equals("Default", ignoreCase = true) }
            ?.getString("id")
            ?: presets.firstOrNull()?.getString("id")
            ?: ""
        // Decode each mode_shift target group as its single source (momentary overlay, merged while held).
        val shiftOverlays = LinkedHashMap<String, ScProfile>()
        sets.values.forEach { set ->
            set.buttons.values.forEach { b ->
                val o = b.output
                if (o is ScOutput.ModeShift && !shiftOverlays.containsKey(o.groupId)) {
                    groupsById[o.groupId]?.let { shiftOverlays[o.groupId] = decodeSingleSource(o.source, it, groupsById) }
                }
            }
        }
        return ScConfig(sets, defaultId, setSources, shiftOverlays)
    }

    private fun parseMappings(vdfText: String): VdfObject? {
        val cm = VdfParser(vdfText).parse().getObject("controller_mappings")
        if (cm == null) Timber.tag(TAG).w("No controller_mappings block; returning empty profile")
        return cm
    }

    private fun indexGroups(cm: VdfObject): LinkedHashMap<String, VdfObject> {
        val groupsById = LinkedHashMap<String, VdfObject>()
        cm.getObjects("group").forEach { g -> g.getString("id")?.let { groupsById[it] = g } }
        return groupsById
    }

    /**
     * Decode one action set (a [preset]'s `group_source_bindings`) into a profile named [name], plus the set of
     * sources it defines (needed for action-layer merging — see [mergeProfiles]).
     */
    private fun buildProfile(
        cm: VdfObject,
        groupsById: Map<String, VdfObject>,
        preset: VdfObject?,
        name: String,
    ): Pair<ScProfile, Set<String>> {
        // Resolve which group drives each physical source (active bindings only).
        val gsb = preset?.getObject("group_source_bindings") ?: cm.getObject("group_source_bindings")
        val sourceGroup = LinkedHashMap<String, VdfObject>()
        gsb?.stringEntries()?.forEach { (groupId, spec) ->
            val parts = spec.trim().split(Regex("\\s+"))
            val source = parts.getOrNull(0)?.lowercase() ?: return@forEach
            val state = parts.getOrNull(1)?.lowercase()
            if (state != "active") return@forEach
            // Only the *base* layer (e.g. "right_trackpad active"). A 3rd qualifier marks a mode-shift / action
            // layer variant ("right_trackpad active modeshift") whose group must NOT clobber the base binding —
            // those belong to the action-sets/mode-shift feature (step 3, still TODO), so defer + log them.
            if (parts.size > 2) {
                Timber.tag(TAG).d("source $source group $groupId is a '${parts.drop(2).joinToString(" ")}' layer -> deferred (action-set/mode-shift TODO)")
                return@forEach
            }
            val group = groupsById[groupId]
            if (group == null) Timber.tag(TAG).d("source $source -> missing group $groupId")
            else sourceGroup[source] = resolveReference(group, groupsById)
        }

        val buttons = LinkedHashMap<Int, Binding>()
        var leftStick: StickMode = StickMode.None
        var rightStick: StickMode = StickMode.None
        var leftPad: PadMode = PadMode.None
        var rightPad: PadMode = PadMode.None
        var leftTrigger: TriggerMode = TriggerMode.Axis(TriggerAxis.GAMEPAD_L2)
        var rightTrigger: TriggerMode = TriggerMode.Axis(TriggerAxis.GAMEPAD_R2)
        var gyro: GyroMode = GyroMode.None

        sourceGroup["button_diamond"]?.let { addDigitalInputs(it, DIAMOND_MAP, buttons) }
        sourceGroup["switch"]?.let { addDigitalInputs(it, SWITCH_MAP, buttons) }
        cm.getObject("switch_bindings")?.let { addDigitalInputs(it, SWITCH_MAP, buttons) } // v2
        sourceGroup["dpad"]?.let { addDigitalInputs(it, DPAD_MAP, buttons) }
        sourceGroup["joystick"]?.let { leftStick = importStick(it, TritonProtocol.BTN_L3, Stick.LEFT, buttons) }
        sourceGroup["right_joystick"]?.let { rightStick = importStick(it, TritonProtocol.BTN_R3, Stick.RIGHT, buttons) }
        sourceGroup["left_trackpad"]?.let {
            leftPad = importPad(it, TritonProtocol.BTN_LPAD_CLICK, buttons)
        }
        sourceGroup["right_trackpad"]?.let {
            rightPad = importPad(it, TritonProtocol.BTN_RPAD_CLICK, buttons)
        }
        sourceGroup["left_trigger"]?.let {
            leftTrigger = importTrigger(it, TritonProtocol.BTN_LTRIG_CLICK, TriggerAxis.GAMEPAD_L2, buttons)
        }
        sourceGroup["right_trigger"]?.let {
            rightTrigger = importTrigger(it, TritonProtocol.BTN_RTRIG_CLICK, TriggerAxis.GAMEPAD_R2, buttons)
        }
        sourceGroup["gyro"]?.let { gyro = importGyro(it) }

        val profile = ScProfile(
            name = name,
            buttons = buttons,
            leftStick = leftStick,
            rightStick = rightStick,
            leftPad = leftPad,
            rightPad = rightPad,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            gyro = gyro,
        )
        return profile to sourceGroup.keys.toSet()
    }

    /** Decode a single [group] as one [source] into a partial profile (only that source's field set). For mode-shift. */
    private fun decodeSingleSource(source: String, group: VdfObject, groupsById: Map<String, VdfObject>): ScProfile {
        val g = resolveReference(group, groupsById)
        val buttons = LinkedHashMap<Int, Binding>()
        var leftStick: StickMode = StickMode.None
        var rightStick: StickMode = StickMode.None
        var leftPad: PadMode = PadMode.None
        var rightPad: PadMode = PadMode.None
        var leftTrigger: TriggerMode = TriggerMode.Axis(TriggerAxis.GAMEPAD_L2)
        var rightTrigger: TriggerMode = TriggerMode.Axis(TriggerAxis.GAMEPAD_R2)
        var gyro: GyroMode = GyroMode.None
        when (source) {
            "button_diamond" -> addDigitalInputs(g, DIAMOND_MAP, buttons)
            "switch" -> addDigitalInputs(g, SWITCH_MAP, buttons)
            "dpad" -> addDigitalInputs(g, DPAD_MAP, buttons)
            "joystick" -> leftStick = importStick(g, TritonProtocol.BTN_L3, Stick.LEFT, buttons)
            "right_joystick" -> rightStick = importStick(g, TritonProtocol.BTN_R3, Stick.RIGHT, buttons)
            "left_trackpad" -> leftPad = importPad(g, TritonProtocol.BTN_LPAD_CLICK, buttons)
            "right_trackpad" -> rightPad = importPad(g, TritonProtocol.BTN_RPAD_CLICK, buttons)
            "left_trigger" -> leftTrigger = importTrigger(g, TritonProtocol.BTN_LTRIG_CLICK, TriggerAxis.GAMEPAD_L2, buttons)
            "right_trigger" -> rightTrigger = importTrigger(g, TritonProtocol.BTN_RTRIG_CLICK, TriggerAxis.GAMEPAD_R2, buttons)
            "gyro" -> gyro = importGyro(g)
            else -> Timber.tag(TAG).d("mode_shift: unhandled source '$source'")
        }
        return ScProfile(
            name = "shift:$source", buttons = buttons, leftStick = leftStick, rightStick = rightStick,
            leftPad = leftPad, rightPad = rightPad, leftTrigger = leftTrigger, rightTrigger = rightTrigger, gyro = gyro,
        )
    }

    /**
     * Follow `mode "reference"` groups to their target. Steam Deck configs (e.g. Delf's ToME4 layout) share a
     * group definition across action sets/layers via `settings { referenced_mode "<id>" }` instead of inlining
     * it; resolving the chain lets the real referenced mode/inputs flow into the importer. Guarded against cycles.
     */
    private fun resolveReference(group: VdfObject, groupsById: Map<String, VdfObject>, depth: Int = 0): VdfObject {
        if (depth > 8 || group.getString("mode")?.lowercase() != "reference") return group
        val target = group.getObject("settings")?.getString("referenced_mode")
        val tg = target?.let { groupsById[it] } ?: return group
        Timber.tag(TAG).d("resolving reference group -> $target")
        return resolveReference(tg, groupsById, depth + 1)
    }

    // ---- digital sources (buttons) ------------------------------------------------------------------

    /** input-name -> SC button bit, for the four face buttons (button_diamond source). */
    private val DIAMOND_MAP = mapOf(
        "button_a" to TritonProtocol.BTN_A, "button_b" to TritonProtocol.BTN_B,
        "button_x" to TritonProtocol.BTN_X, "button_y" to TritonProtocol.BTN_Y,
    )

    /**
     * System/bumper/paddle buttons (switch source). Start/Back: Steam's `button_menu` is the Start-side
     * button (our BTN_VIEW, =Start per SDL) and `button_escape` is the Back/≡-side button (our BTN_MENU).
     * Paddles: back_left/right = lower L4/R4, *_upper = L5/R5. (TODO: verify the Start/Back pairing on-device.)
     */
    private val SWITCH_MAP = mapOf(
        "button_escape" to TritonProtocol.BTN_MENU,
        "button_menu" to TritonProtocol.BTN_VIEW,
        "left_bumper" to TritonProtocol.BTN_LBUMPER,
        "right_bumper" to TritonProtocol.BTN_RBUMPER,
        "button_back_left" to TritonProtocol.BTN_L4,
        "button_back_right" to TritonProtocol.BTN_R4,
        "button_back_left_upper" to TritonProtocol.BTN_L5,
        "button_back_right_upper" to TritonProtocol.BTN_R5,
    )

    /** Physical d-pad source. Our GamepadDpad index order is 0=up,1=right,2=down,3=left. */
    private val DPAD_MAP = mapOf(
        "dpad_north" to TritonProtocol.BTN_DPAD_UP, "dpad_south" to TritonProtocol.BTN_DPAD_DOWN,
        "dpad_east" to TritonProtocol.BTN_DPAD_RIGHT, "dpad_west" to TritonProtocol.BTN_DPAD_LEFT,
    )

    /** Fill [buttons] from a group's inputs (v3 activators or v2 flat bindings) using [bitMap]. */
    private fun addDigitalInputs(group: VdfObject, bitMap: Map<String, Int>, buttons: MutableMap<Int, Binding>) {
        // v3: inputs { <name> { activators ... } }
        group.getObject("inputs")?.objectEntries()?.forEach { (name, inputObj) ->
            val bit = bitMap[name.lowercase()]
            if (bit == null) { Timber.tag(TAG).d("no bit for input '$name'"); return@forEach }
            resolveInput(inputObj)?.let { buttons[bit] = it }
        }
        // v2: bindings { <name> <bindingString> }
        group.getObject("bindings")?.stringEntries()?.forEach { (name, str) ->
            val bit = bitMap[name.lowercase()] ?: return@forEach
            parseBindingOutput(str)?.let { buttons[bit] = Binding(it) }
        }
    }

    // ---- analog sources -----------------------------------------------------------------------------

    private fun importStick(group: VdfObject, clickBit: Int, stick: Stick, buttons: MutableMap<Int, Binding>): StickMode {
        extractInput(group, "click")?.let { buttons[clickBit] = it }
        val s = group.getObject("settings")
        fun joystick() = StickMode.JoystickMove(
            stick,
            invertY = readInvertY(s, default = true),
            deadzone = readDeadzone(s, default = 0.12f),
        )
        return when (val mode = group.getString("mode")?.lowercase().orEmpty()) {
            "joystick_move" -> joystick()
            "joystick_camera" -> {
                Timber.tag(TAG).d("stick mode 'joystick_camera' -> approximating as JoystickMove (curve TODO)")
                joystick()
            }
            "joystick_mouse" -> StickMode.Mouse(
                sensitivity = 12f * readSensScale(s),
                deadzone = readDeadzone(s, default = 0.10f),
            )
            "flickstick" -> StickMode.FlickStick(deadzone = readDeadzone(s, default = 0.20f))
            "radial_menu" -> parseRadial(group).let {
                if (it.ring.isEmpty()) StickMode.None // HOLD by default (movement radial)
                else StickMode.RadialMenu(it.ring, center = it.center, directional = it.directional)
            }
            "touch_menu" -> parseMenuSlots(group).let {
                if (it.isEmpty()) StickMode.None else gridDims(it.size).let { (c, r) -> StickMode.TouchMenu(it, c, r) }
            }
            "", "disabled" -> StickMode.None
            else -> { Timber.tag(TAG).d("unsupported stick mode '$mode' -> None"); StickMode.None }
        }
    }

    private fun importPad(group: VdfObject, clickBit: Int, buttons: MutableMap<Int, Binding>): PadMode {
        extractInput(group, "click")?.let { buttons[clickBit] = it }
        val s = group.getObject("settings")
        fun mouse() = PadMode.Mouse(sensitivity = (1.0f / 70f) * readSensScale(s), invertY = readInvertY(s, default = true))
        return when (val mode = group.getString("mode")?.lowercase().orEmpty()) {
            "dpad" -> PadMode.DPad(
                up = dirOut(group, "dpad_north"), down = dirOut(group, "dpad_south"),
                left = dirOut(group, "dpad_west"), right = dirOut(group, "dpad_east"),
                deadzone = readDeadzone(s, default = 0.35f),
            )
            "scrollwheel" -> PadMode.ScrollWheel()
            // Absolute / region mouse: finger position → 1:1 screen position within a region. Steam params
            // (confirmed): position_x/y (center %, def 50), scale (size %, def 80), scale_x/y (def 100), invert_x/y.
            "mouse_region", "absolute_mouse" -> {
                fun pct(key: String, def: Float) = (s?.getString(key)?.toFloatOrNull() ?: def) / 100f
                val scale = pct("scale", 80f)
                PadMode.AbsoluteMouse(
                    centerX = pct("position_x", 50f), centerY = pct("position_y", 50f),
                    sizeX = scale * pct("scale_x", 100f), sizeY = scale * pct("scale_y", 100f),
                    invertX = s?.getString("invert_x") == "1", invertY = s?.getString("invert_y") == "1",
                )
            }
            // Single-button pad (whole surface = one button). The vdf binds it on the "click" input; importPad
            // already put that in buttons[clickBit] — move it into the mode so it fires on the pad (avoid double-bind).
            "single_button" -> {
                val out = buttons.remove(clickBit)?.output ?: extractInput(group, "click")?.output ?: ScOutput.None
                PadMode.SingleButton(out, onClick = readRequiresClick(s))
            }
            // Directional swipe (Steam `2dscroll`): flick a cardinal direction → pulse that dir's output. Uses the
            // dpad_* inputs, like a d-pad, but swipe-triggered.
            "2dscroll" -> PadMode.DirectionalSwipe(
                up = dirOut(group, "dpad_north"), down = dirOut(group, "dpad_south"),
                left = dirOut(group, "dpad_west"), right = dirOut(group, "dpad_east"),
            )
            "mouse", "relative_mouse", "mouse_joystick" -> mouse()
            // Overlay-tier menus: the selection LOGIC is built (radial = angle→slot, touch = grid cell→slot,
            // commit pulses the slot). The visual ring/grid HUD is the step-6 overlay (separate). hotbar ≈ touch grid.
            "touch_menu", "radial_menu", "hotbar" -> importMenu(group, mode)
            // button_pad: a blind grid. Reuse the touch_menu slot naming if present; else None. (Rare in the wild.)
            "button_pad" -> {
                val menu = importMenu(group, "touch_menu")
                if (menu is PadMode.TouchMenu) {
                    PadMode.ButtonPadGrid(menu.cols, menu.rows, menu.slots.map { it.binding.output }, onClick = false)
                } else { Timber.tag(TAG).d("pad mode 'button_pad' has no parseable slots -> None"); PadMode.None }
            }
            "", "disabled" -> PadMode.None
            else -> { Timber.tag(TAG).d("unsupported pad mode '$mode' -> None"); PadMode.None }
        }
    }

    /**
     * Parse a radial_menu/touch_menu/hotbar group into its [PadMode]. Slots are the ordered `touch_menu_button_N`
     * inputs (both menu types use that key naming), each resolved to a [Binding] + display label. Radial → ring;
     * touch/hotbar → a near-square grid (row 0 = top). Returns [PadMode.None] if no slots resolve.
     */
    private fun importMenu(group: VdfObject, mode: String): PadMode {
        // requires_click=1 → commit only on a pad click; =0/absent → release-style ("point and release"). Either
        // way a click still commits (the interpreter treats click as a universal commit); this flag only controls
        // whether disengaging (lifting the finger) also commits. Steam's menus default to release-style.
        val onClick = readRequiresClick(group.getObject("settings"))
        if (mode == "radial_menu") {
            val r = parseRadial(group)
            if (r.ring.isEmpty()) { Timber.tag(TAG).d("radial_menu group has no resolvable ring slots -> None"); return PadMode.None }
            return PadMode.RadialMenu(r.ring, onClick = onClick, center = r.center, directional = r.directional)
        }
        val slots = parseMenuSlots(group)
        if (slots.isEmpty()) {
            Timber.tag(TAG).d("$mode group has no resolvable slots -> None")
            return PadMode.None
        }
        val (cols, rows) = gridDims(slots.size)
        return PadMode.TouchMenu(slots, cols, rows, onClick = onClick)
    }

    /** Ordered `touch_menu_button_N` slots (both menu kinds use that naming) → [MenuSlot]s with display labels. */
    private fun parseMenuSlots(group: VdfObject): List<MenuSlot> =
        group.getObject("inputs")?.objectEntries()
            ?.filter { it.first.startsWith("touch_menu_button", ignoreCase = true) }
            ?.sortedBy { it.first.substringAfterLast('_').toIntOrNull() ?: Int.MAX_VALUE }
            ?.mapNotNull { (_, obj) -> resolveInput(obj)?.let { MenuSlot(it, menuLabelOf(obj)) } }
            ?: emptyList()

    /** A radial split into its ring + center: Steam's `touch_menu_button_0` is the radial CENTER (often a neutral/
     *  no-op like the movement radial's `mouse_delta 0 0`); buttons 1.. are the ring, in order. [directional] flags
     *  a movement radial — an 8-slot ring all bound to arrow/keypad direction keys — so the HUD can label it ↑↗→… */
    private class RadialParse(val ring: List<MenuSlot>, val center: MenuSlot?, val directional: Boolean)

    private fun parseRadial(group: VdfObject): RadialParse {
        val entries = group.getObject("inputs")?.objectEntries()
            ?.filter { it.first.startsWith("touch_menu_button", ignoreCase = true) }
            ?.sortedBy { it.first.substringAfterLast('_').toIntOrNull() ?: Int.MAX_VALUE }
            ?: emptyList()
        var center: MenuSlot? = null
        val ring = ArrayList<MenuSlot>()
        for ((name, obj) in entries) {
            val slot = resolveInput(obj)?.let { MenuSlot(it, menuLabelOf(obj)) } ?: continue
            if (name.substringAfterLast('_').toIntOrNull() == 0) center = slot else ring.add(slot)
        }
        // The center (button_0) is the visual hub; the actual center ACTION is the group's `click` input (e.g.
        // ToME4 stick-click = "Wait a turn"). Label the center from that click action when button_0 has no label.
        val clickLabel = group.getObject("inputs")?.objectEntries()
            ?.firstOrNull { it.first.equals("click", ignoreCase = true) }
            ?.let { menuLabelOf(it.second) }?.takeIf { it.isNotBlank() }
        val labeledCenter = center?.let { if (it.label.isBlank() && clickLabel != null) MenuSlot(it.binding, clickLabel) else it }
        val directional = ring.size == 8 && ring.all { isDirectionalKey(it.binding.output) }
        return RadialParse(ring, labeledCenter, directional)
    }

    /** A single arrow/keypad direction key (the building blocks of a movement radial's ring). */
    private fun isDirectionalKey(out: ScOutput?): Boolean {
        val keys = (out as? ScOutput.Key)?.keys ?: return false
        if (keys.size != 1) return false
        val n = keys[0].name
        return n in DIRECTION_KEYS || n.startsWith("KEY_KP_")
    }

    private val DIRECTION_KEYS = setOf("KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT")

    /** Near-square grid for a touch/hotbar menu of [n] slots. */
    private fun gridDims(n: Int): Pair<Int, Int> {
        val cols = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
        val rows = ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)
        return cols to rows
    }

    /** A menu slot's display label, from its binding metadata ("key_press P, Level Up, icon, colors" -> "Level Up"). */
    private fun menuLabelOf(inputObj: VdfObject): String {
        val raw = inputObj.getObject("activators")?.objectValues()
            ?.firstNotNullOfOrNull { it.getObject("bindings")?.getStrings("binding")?.firstOrNull() } ?: return ""
        return raw.split(',').getOrNull(1)?.trim().orEmpty()
    }

    private fun importTrigger(
        group: VdfObject,
        clickBit: Int,
        axis: TriggerAxis,
        buttons: MutableMap<Int, Binding>,
    ): TriggerMode {
        val edge = extractInput(group, "edge")?.output
        if (edge != null && edge != ScOutput.None) {
            // Digital full-pull binding -> staged (keep the analog axis live too so games still see the trigger).
            return TriggerMode.Staged(soft = ScOutput.None, full = edge, axis = axis)
        }
        val clickRaw = rawBindingOf(group, "click")
        val clickIsAnalogAxis = clickRaw?.contains("TRIGGER_", ignoreCase = true) == true
        if (!clickIsAnalogAxis) extractInput(group, "click")?.let { buttons[clickBit] = it }
        return TriggerMode.Axis(axis)
    }

    private fun importGyro(group: VdfObject): GyroMode {
        val s = group.getObject("settings")
        return when (val mode = group.getString("mode")?.lowercase().orEmpty()) {
            "mouse", "absolute_mouse", "mouse_region" ->
                GyroMode.Mouse((1.0f / 900f) * readSensScale(s), GyroGate.EITHER_GRIP)
            "", "disabled" -> GyroMode.None
            else -> { Timber.tag(TAG).d("unsupported gyro mode '$mode' -> None"); GyroMode.None }
        }
    }

    // ---- per-group tunable settings -> existing model fields (the common ones; the long tail is step 3/7) ----

    /** Steam inner dead zone is raw 0..32768; our `deadzone` is 0..1. Falls back to [default] when unset. */
    private fun readDeadzone(settings: VdfObject?, default: Float): Float {
        val raw = settings?.getString("deadzone_inner_radius")?.toIntOrNull()
            ?: settings?.getString("deadzone")?.toIntOrNull()
        return raw?.let { (it / 32768f).coerceIn(0f, 0.95f) } ?: default
    }

    /** `invert_y` is "0"/"1"; keep [default] (our model's convention) when the setting is absent. */
    private fun readInvertY(settings: VdfObject?, default: Boolean): Boolean =
        settings?.getString("invert_y")?.let { it.trim() == "1" } ?: default

    /** Menu commit style: `requires_click=1` → click-to-commit only; absent/0 → release-style. */
    private fun readRequiresClick(settings: VdfObject?): Boolean =
        settings?.getString("requires_click")?.let { it.trim() == "1" } ?: false

    /** Steam `sensitivity` is a percent (100 = baseline); return a multiplier for our default sensitivities. */
    private fun readSensScale(settings: VdfObject?): Float {
        val pct = settings?.getString("sensitivity")?.toFloatOrNull() ?: 100f
        return (pct / 100f).coerceIn(0.05f, 20f)
    }

    /** The resolved output of a named input, or [ScOutput.None] if absent/unsupported (for pad d-pad cells). */
    private fun dirOut(group: VdfObject, name: String): ScOutput = extractInput(group, name)?.output ?: ScOutput.None

    // ---- input / binding / activator parsing --------------------------------------------------------

    /** Resolve a v3 input object (its activators) to a single [Binding], picking the best-priority bound activator. */
    private fun resolveInput(inputObj: VdfObject): Binding? {
        val activators = inputObj.getObject("activators") ?: return null
        // (priority, activator, output, rawActivatorType)
        var best: Quad? = null
        for ((type, actObj) in activators.objectEntries()) {
            val bindingStrings = actObj.getObject("bindings")?.getStrings("binding") ?: continue
            val out = parseActivatorOutput(bindingStrings) ?: continue
            val act = activatorOf(type, actObj.getObject("settings"))
            val prio = activatorPriority(type)
            if (best == null || prio < best!!.prio) best = Quad(prio, act, out, type.lowercase())
        }
        val b = best ?: return null
        // An action-set switch fires on its activator's edge: a `release` activator => switch on release.
        val out = if (b.output is ScOutput.SwitchActionSet && b.type == "release") {
            (b.output as ScOutput.SwitchActionSet).copy(onRelease = true)
        } else b.output
        return Binding(out, b.activator)
    }

    private class Quad(val prio: Int, val activator: Activator, val output: ScOutput, val type: String)

    /** Look up a named input across both schema variants and resolve it to a [Binding]. */
    private fun extractInput(group: VdfObject, name: String): Binding? {
        group.getObject("inputs")?.objectEntries()
            ?.firstOrNull { it.first.equals(name, ignoreCase = true) }
            ?.let { return resolveInput(it.second) }
        group.getObject("bindings")?.stringEntries()
            ?.firstOrNull { it.first.equals(name, ignoreCase = true) }
            ?.let { (_, str) -> parseBindingOutput(str)?.let { return Binding(it) } }
        return null
    }

    /** First raw binding string for a named input (used to sniff analog trigger passthrough). */
    private fun rawBindingOf(group: VdfObject, name: String): String? {
        group.getObject("bindings")?.stringEntries()
            ?.firstOrNull { it.first.equals(name, ignoreCase = true) }?.let { return it.second }
        val input = group.getObject("inputs")?.objectEntries()
            ?.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second ?: return null
        input.getObject("activators")?.objectValues()?.forEach { act ->
            act.getObject("bindings")?.getStrings("binding")?.firstOrNull()?.let { return it }
        }
        return null
    }

    /** Lower = higher priority when an input defines multiple activators (we keep one). */
    private fun activatorPriority(type: String): Int = when (type.lowercase()) {
        "full_press" -> 0
        "soft_press" -> 1
        "start_press" -> 2
        "double_press" -> 3
        "long_press" -> 4
        "release" -> 5
        else -> 6
    }

    private fun activatorOf(type: String, settings: VdfObject?): Activator {
        val holdRepeats = settings?.getString("hold_repeats")?.toIntOrNull() ?: 0
        if (holdRepeats != 0) return Activator.Turbo(repeatIntervalMs(settings))
        return when (type.lowercase()) {
            "double_press" -> Activator.DoublePress(settings?.getString("doubletap_max_duration")?.toLongOrNull() ?: 300)
            "long_press" -> Activator.LongPress(settings?.getString("long_press_time")?.toLongOrNull() ?: 500)
            "release" -> Activator.OnRelease // fire on the release edge (was collapsing to Regular)
            // full_press / start_press / soft_press collapse to Regular (closest digital behaviour).
            else -> Activator.Regular
        }
    }

    /** Steam `repeat_rate` is repeats-per-second; convert to a turbo interval in ms (clamped sane). */
    private fun repeatIntervalMs(settings: VdfObject?): Long {
        // Steam's `repeat_rate` is the interval BETWEEN repeats in **milliseconds** (e.g. ToME4 uses 280 and
        // 1000), NOT a frequency. Treating it as Hz (1000/rate) collapsed every repeat to the 10ms floor —
        // a machine-gun that made e.g. ToME4's d-pad up fire continuously. Use it directly, clamped to a sane
        // range (a real key/turbo repeat shouldn't be faster than ~30ms or slower than 2s).
        val rate = settings?.getString("repeat_rate")?.toLongOrNull() ?: 0
        return if (rate <= 0) 120 else rate.coerceIn(30, 2000)
    }

    /** Combine an activator's binding line(s) into one output (multiple key_press lines -> a held combo). */
    private fun parseActivatorOutput(bindingStrings: List<String>): ScOutput? {
        val outs = bindingStrings.mapNotNull { parseBindingOutput(it) }
        if (outs.isEmpty()) return null
        if (outs.size == 1) return outs[0]
        return if (outs.all { it is ScOutput.Key }) {
            ScOutput.Key(outs.flatMap { (it as ScOutput.Key).keys })
        } else {
            Timber.tag(TAG).d("mixed multi-binding activator; using first output only")
            outs[0]
        }
    }

    /** Parse a single Steam `binding` string into an [ScOutput], or null if unsupported (e.g. controller_action). */
    private fun parseBindingOutput(bindingValue: String): ScOutput? {
        val cmd = bindingValue.substringBefore(',').trim()
        if (cmd.isEmpty()) return null
        val t = cmd.split(Regex("\\s+"))
        return when (t[0].lowercase()) {
            "key_press" -> keyOf(t.getOrNull(1))?.let { ScOutput.Key(it) }
            "mouse_button" -> mouseButtonOf(t.getOrNull(1))?.let { ScOutput.MouseButton(it) }
            "mouse_wheel" -> wheelOf(t.getOrNull(1))?.let { ScOutput.MouseButton(it) }
            "xinput_button" -> xinputOf(t.getOrNull(1))
            "mode_shift" -> {
                // mode_shift <source> <rawGroupId> -> momentary single-source overlay (decoded in importConfig).
                val source = t.getOrNull(1)?.lowercase()
                val groupId = t.getOrNull(2)?.trimEnd(',')
                if (source != null && groupId != null) ScOutput.ModeShift(source, groupId) else null
            }
            "controller_action" -> {
                // CHANGE_PRESET / add_layer / hold_layer / remove_layer all take a 1-based preset id (id = N-1,
                // verified against Delf {0,1}->{1,2} and KSP add_layer 7->layer id 6). Edge (press vs release for
                // CHANGE_PRESET) is finalised in resolveInput.
                val sub = t.getOrNull(1)?.uppercase()
                val targetId = t.getOrNull(2)?.trimEnd(',')?.toIntOrNull()?.let { (it - 1).toString() }
                when (sub) {
                    "CHANGE_PRESET" -> targetId?.let { ScOutput.SwitchActionSet(it) }
                    "ADD_LAYER" -> targetId?.let { ScOutput.LayerOp(it, LayerOpType.ADD) }
                    "HOLD_LAYER" -> targetId?.let { ScOutput.LayerOp(it, LayerOpType.HOLD) }
                    "REMOVE_LAYER" -> targetId?.let { ScOutput.LayerOp(it, LayerOpType.REMOVE) }
                    "SHOW_KEYBOARD" -> ScOutput.ShowKeyboard
                    "MOUSE_DELTA" -> {
                        // controller_action mouse_delta <dx> <dy> -> a one-shot relative mouse nudge.
                        val dx = t.getOrNull(2)?.trimEnd(',')?.toIntOrNull() ?: 0
                        val dy = t.getOrNull(3)?.trimEnd(',')?.toIntOrNull() ?: 0
                        ScOutput.MouseNudge(dx, dy)
                    }
                    "MOUSE_POSITION" -> {
                        // controller_action MOUSE_POSITION <x> <y> <return> -> warp cursor (x,y in 0..32767 screen space).
                        val x = t.getOrNull(2)?.trimEnd(',')?.toFloatOrNull()
                        val y = t.getOrNull(3)?.trimEnd(',')?.toFloatOrNull()
                        if (x != null && y != null) {
                            ScOutput.MousePosition(x / 32767f, y / 32767f, t.getOrNull(4)?.trimEnd(',') == "1")
                        } else null
                    }
                    else -> { Timber.tag(TAG).d("unsupported controller_action '$cmd'"); null }
                }
            }
            else -> { Timber.tag(TAG).d("unsupported binding '$cmd'"); null }
        }
    }

    private fun mouseButtonOf(name: String?): Pointer.Button? = when (name?.uppercase()) {
        "LEFT" -> Pointer.Button.BUTTON_LEFT
        "RIGHT" -> Pointer.Button.BUTTON_RIGHT
        "MIDDLE" -> Pointer.Button.BUTTON_MIDDLE
        else -> null
    }

    private fun wheelOf(name: String?): Pointer.Button? = when (name?.uppercase()) {
        "SCROLL_UP" -> Pointer.Button.BUTTON_SCROLL_UP
        "SCROLL_DOWN" -> Pointer.Button.BUTTON_SCROLL_DOWN
        else -> null
    }

    private fun xinputOf(name: String?): ScOutput? = when (name?.uppercase()) {
        "A" -> ScOutput.GamepadButton(ExternalController.IDX_BUTTON_A.toInt())
        "B" -> ScOutput.GamepadButton(ExternalController.IDX_BUTTON_B.toInt())
        "X" -> ScOutput.GamepadButton(ExternalController.IDX_BUTTON_X.toInt())
        "Y" -> ScOutput.GamepadButton(ExternalController.IDX_BUTTON_Y.toInt())
        "SHOULDER_LEFT" -> ScOutput.GamepadButton(ExternalController.IDX_BUTTON_L1.toInt())
        "SHOULDER_RIGHT" -> ScOutput.GamepadButton(ExternalController.IDX_BUTTON_R1.toInt())
        "START" -> ScOutput.GamepadButton(ExternalController.IDX_BUTTON_START.toInt())
        "SELECT", "BACK" -> ScOutput.GamepadButton(ExternalController.IDX_BUTTON_SELECT.toInt())
        "JOYSTICK_LEFT" -> ScOutput.GamepadButton(ExternalController.IDX_BUTTON_L3.toInt())
        "JOYSTICK_RIGHT" -> ScOutput.GamepadButton(ExternalController.IDX_BUTTON_R3.toInt())
        "TRIGGER_LEFT" -> ScOutput.GamepadButton(ExternalController.IDX_BUTTON_L2.toInt())
        "TRIGGER_RIGHT" -> ScOutput.GamepadButton(ExternalController.IDX_BUTTON_R2.toInt())
        "DPAD_UP" -> ScOutput.GamepadDpad(0)
        "DPAD_RIGHT" -> ScOutput.GamepadDpad(1)
        "DPAD_DOWN" -> ScOutput.GamepadDpad(2)
        "DPAD_LEFT" -> ScOutput.GamepadDpad(3)
        else -> { Timber.tag(TAG).d("unsupported xinput_button '$name'"); null }
    }

    /** Steam key-name -> XKeycode, returned as a single-element list (combos are merged by the caller). */
    private fun keyOf(name: String?): List<XKeycode>? {
        if (name == null) return null
        val k = KEY_MAP[name.uppercase()]
        if (k == null) Timber.tag(TAG).d("unmapped key_press '$name'")
        return k?.let { listOf(it) }
    }

    private val KEY_MAP: Map<String, XKeycode> = buildMap {
        ('A'..'Z').forEach { put(it.toString(), XKeycode.valueOf("KEY_$it")) }
        ('0'..'9').forEach { put(it.toString(), XKeycode.valueOf("KEY_$it")) }
        (1..12).forEach { put("F$it", XKeycode.valueOf("KEY_F$it")) }
        // Numpad — Steam tokens KEYPAD_0..9 (+ operators). Without these, configs that bind the numpad
        // (e.g. ToME4's 8-way movement radial uses KEYPAD_1/3/7/9 for the diagonals) silently drop those
        // slots — leaving a 4-way (cardinals-only) radial. Map them to the X11 keypad codes.
        (0..9).forEach { put("KEYPAD_$it", XKeycode.valueOf("KEY_KP_$it")) }
        put("KEYPAD_PERIOD", XKeycode.KEY_KP_DEL)
        put("KEYPAD_DIVIDE", XKeycode.KEY_KP_DIVIDE)
        put("KEYPAD_FORWARD_SLASH", XKeycode.KEY_KP_DIVIDE)
        put("KEYPAD_MULTIPLY", XKeycode.KEY_KP_MULTIPLY)
        put("KEYPAD_MINUS", XKeycode.KEY_KP_SUBTRACT)
        put("KEYPAD_DASH", XKeycode.KEY_KP_SUBTRACT)
        put("KEYPAD_PLUS", XKeycode.KEY_KP_ADD)
        put("SPACE", XKeycode.KEY_SPACE)
        put("TAB", XKeycode.KEY_TAB)
        put("ESCAPE", XKeycode.KEY_ESC)
        put("RETURN", XKeycode.KEY_ENTER)
        put("ENTER", XKeycode.KEY_ENTER)
        put("KEYPAD_ENTER", XKeycode.KEY_KP_ENTER)
        put("LEFT_SHIFT", XKeycode.KEY_SHIFT_L)
        put("RIGHT_SHIFT", XKeycode.KEY_SHIFT_R)
        put("LEFT_CONTROL", XKeycode.KEY_CTRL_L)
        put("LEFT_CTRL", XKeycode.KEY_CTRL_L)
        put("RIGHT_CONTROL", XKeycode.KEY_CTRL_R)
        put("RIGHT_CTRL", XKeycode.KEY_CTRL_R)
        put("LEFT_ALT", XKeycode.KEY_ALT_L)
        put("RIGHT_ALT", XKeycode.KEY_ALT_R)
        put("BACKSPACE", XKeycode.KEY_BKSP)
        put("DELETE", XKeycode.KEY_DEL)
        put("INSERT", XKeycode.KEY_INSERT)
        put("HOME", XKeycode.KEY_HOME)
        put("END", XKeycode.KEY_END)
        put("PAGE_UP", XKeycode.KEY_PRIOR)
        put("PAGE_DOWN", XKeycode.KEY_NEXT)
        put("UP_ARROW", XKeycode.KEY_UP)
        put("DOWN_ARROW", XKeycode.KEY_DOWN)
        put("LEFT_ARROW", XKeycode.KEY_LEFT)
        put("RIGHT_ARROW", XKeycode.KEY_RIGHT)
        put("CAPSLOCK", XKeycode.KEY_CAPS_LOCK)
        put("PERIOD", XKeycode.KEY_PERIOD)
        put("COMMA", XKeycode.KEY_COMMA)
        put("DASH", XKeycode.KEY_MINUS)
        put("MINUS", XKeycode.KEY_MINUS)
        put("EQUALS", XKeycode.KEY_EQUAL)
        put("FORWARD_SLASH", XKeycode.KEY_SLASH)
        put("BACK_SLASH", XKeycode.KEY_BACKSLASH)
        put("SEMICOLON", XKeycode.KEY_SEMICOLON)
        put("SINGLE_QUOTE", XKeycode.KEY_APOSTROPHE)
        put("APOSTROPHE", XKeycode.KEY_APOSTROPHE)
        put("LEFT_BRACKET", XKeycode.KEY_BRACKET_LEFT)
        put("RIGHT_BRACKET", XKeycode.KEY_BRACKET_RIGHT)
        put("BACK_QUOTE", XKeycode.KEY_GRAVE)
        put("TILDE", XKeycode.KEY_GRAVE)
    }
}

private sealed interface VdfValue

private data class VdfEntry(val key: String, val value: VdfValue)

private data class VdfString(val value: String) : VdfValue

private class VdfObject : VdfValue {
    private val entries = mutableListOf<VdfEntry>()

    fun add(key: String, value: VdfValue) {
        entries.add(VdfEntry(key, value))
    }

    fun getObject(key: String): VdfObject? = getObjects(key).firstOrNull()

    fun getObjects(key: String): List<VdfObject> = entries.mapNotNull {
        if (it.key == key && it.value is VdfObject) it.value else null
    }

    fun getString(key: String): String? = getStrings(key).firstOrNull()

    fun getStrings(key: String): List<String> = entries.mapNotNull {
        if (it.key == key && it.value is VdfString) it.value.value else null
    }

    fun objectEntries(): List<Pair<String, VdfObject>> = entries.mapNotNull {
        if (it.value is VdfObject) it.key to it.value else null
    }

    fun objectValues(): List<VdfObject> = entries.mapNotNull { it.value as? VdfObject }

    fun stringEntries(): List<Pair<String, String>> = entries.mapNotNull {
        if (it.value is VdfString) it.key to it.value.value else null
    }

    fun keys(): List<String> = entries.map { it.key }
}

private class VdfParser(text: String) {
    private val source = if (text.startsWith("\uFEFF")) text.substring(1) else text
    private var index = 0

    fun parse(): VdfObject = parseObject()

    private fun parseObject(): VdfObject {
        val obj = VdfObject()
        while (true) {
            val token = nextToken() ?: break
            if (token == "}") break
            val key = token
            val valueToken = nextToken() ?: break
            if (valueToken == "{") {
                obj.add(key, parseObject())
            } else if (valueToken == "}") {
                break
            } else {
                obj.add(key, VdfString(valueToken))
            }
        }
        return obj
    }

    private fun nextToken(): String? {
        skipWhitespaceAndComments()
        if (index >= source.length) return null
        return when (val ch = source[index]) {
            '{', '}' -> {
                index++
                ch.toString()
            }
            '"' -> parseQuoted()
            else -> parseUnquoted()
        }
    }

    private fun parseQuoted(): String {
        index++ // skip opening quote
        val sb = StringBuilder()
        while (index < source.length) {
            val ch = source[index++]
            if (ch == '"') break
            if (ch == '\\' && index < source.length) {
                val escaped = source[index++]
                sb.append(unescapeChar(escaped))
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parseUnquoted(): String {
        val start = index
        while (index < source.length) {
            val ch = source[index]
            if (ch.isWhitespace() || ch == '{' || ch == '}') break
            index++
        }
        return unescape(source.substring(start, index))
    }

    private fun skipWhitespaceAndComments() {
        while (index < source.length) {
            val ch = source[index]
            if (ch.isWhitespace()) {
                index++
                continue
            }
            if (ch == '/' && index + 1 < source.length && source[index + 1] == '/') {
                index += 2
                while (index < source.length && source[index] != '\n') index++
                continue
            }
            break
        }
    }

    private fun unescapeChar(ch: Char): Char = when (ch) {
        'n' -> '\n'
        't' -> '\t'
        'v' -> '\u000B'
        'b' -> '\b'
        'r' -> '\r'
        'f' -> '\u000C'
        'a' -> '\u0007'
        '\\' -> '\\'
        '?' -> '?'
        '"' -> '"'
        '\'' -> '\''
        else -> ch
    }

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                val next = value[i + 1]
                sb.append(unescapeChar(next))
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
