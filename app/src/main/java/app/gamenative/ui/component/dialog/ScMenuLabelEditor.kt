package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.steamcontroller.MenuLabelOverride
import app.gamenative.steamcontroller.ScConfigStore
import app.gamenative.steamcontroller.ScMenuLabelTool
import app.gamenative.steamcontroller.ScMenuLabels
import app.gamenative.ui.util.SnackbarManager

/**
 * Authors custom labels for radial/touch-menu slots (the overlay HUD text), persisted via [ScConfigStore] keyed
 * by the game's config key and layered over the resolved config in [ScConfigStore.forKey]. Menus come from an
 * imported `.vdf` or the built-in default (they aren't in the digital binding editor), so this lists whatever
 * menus the resolved config actually has and lets the user rename each slot. Blank = keep the default label.
 */
@Composable
fun ScMenuLabelEditorDialog(storeKey: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cfg = remember(storeKey) { ScConfigStore.rawConfig(context, storeKey) }
    val menus = remember(storeKey) { cfg?.let { ScMenuLabelTool.enumerate(it) } ?: emptyList() }
    val multiSet = remember(storeKey) { (cfg?.sets?.size ?: 0) > 1 }
    // Edited labels keyed by "setId|LOCATION|slot"; seeded from the stored overrides.
    val edits = remember(storeKey) {
        mutableStateMapOf<String, String>().apply {
            ScConfigStore.loadLabels(context, storeKey)?.overrides?.forEach {
                put("${it.setId}|${it.location}|${it.slot}", it.label)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 640.dp).padding(8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                ScNavDialogCapture(onBack = onDismiss)
                Text("Menu labels", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Rename radial / touch-menu slots shown on the overlay. Leave blank to keep the default. " +
                        "Applies on next launch.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                HorizontalDivider()

                if (menus.isEmpty()) {
                    Text(
                        "This config has no radial or touch menus to label. Import a .vdf with menus, or use the " +
                            "built-in default's movement radial.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    Column(modifier = Modifier.weight(1f, fill = false).verticalScrollWithBar()) {
                        for (menu in menus) {
                            val header = buildString {
                                append(menu.location.label); append(" · "); append(menu.kind)
                                if (multiSet) append(" (set ${menu.setId})")
                            }
                            Text(
                                header,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                            )
                            menu.slotDefaults.forEachIndexed { i, default ->
                                val key = "${menu.setId}|${menu.location.name}|$i"
                                OutlinedTextField(
                                    value = edits[key] ?: "",
                                    onValueChange = { edits[key] = it },
                                    label = { Text("Slot ${i + 1}") },
                                    placeholder = { Text(default) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = menus.isNotEmpty(),
                        onClick = {
                            val overrides = edits.entries.mapNotNull { (k, v) ->
                                val label = v.trim()
                                if (label.isBlank()) return@mapNotNull null
                                val parts = k.split("|")
                                if (parts.size != 3) return@mapNotNull null
                                MenuLabelOverride(parts[0], parts[1], parts[2].toIntOrNull() ?: return@mapNotNull null, label)
                            }
                            if (ScConfigStore.saveLabels(context, storeKey, ScMenuLabels(overrides))) {
                                SnackbarManager.show(if (overrides.isEmpty()) "Custom labels cleared" else "Labels saved")
                                onDismiss()
                            } else {
                                SnackbarManager.show("Could not save labels")
                            }
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}
