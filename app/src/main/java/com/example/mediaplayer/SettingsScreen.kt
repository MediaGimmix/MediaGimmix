package com.example.mediaplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    vm: LibraryViewModel,
    onAddFolder: () -> Unit,
    onRegrantFolder: (String) -> Unit,
    onBack: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var newDelimiter by remember { mutableStateOf("") }
    var newWhitelisted by remember { mutableStateOf("") }
    var tryTag by remember { mutableStateOf("") }
    val stats = vm.stats
    val canScan = !vm.scanning && !vm.loading && vm.folders.isNotEmpty()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Settings", style = MaterialTheme.typography.titleMedium)
            }
        }

        // ---- folders
        item { SettingsTitle("Media folders") }
        if (vm.folders.isEmpty()) {
            item { Note("No folders yet. Add the folder that holds your music.") }
        }
        items(vm.folders) { uri ->
            FolderRow(
                label = folderLabel(uri),
                writable = vm.canWrite(uri),
                onAllowChanges = { onRegrantFolder(uri) },
                onRemove = { confirmRemove = uri },
            )
        }
        item {
            Button(onClick = onAddFolder, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Add folder")
            }
        }

        // ---- scanning
        item { SettingsTitle("Scanning") }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.rescan(full = false) }, enabled = canScan) { Text("Quick rescan") }
                    OutlinedButton(onClick = { vm.rescan(full = true) }, enabled = canScan) { Text("Full rescan") }
                }
                Note(
                    "Quick rescan reads only new and changed files and removes deleted ones. " +
                        "Full rescan re-reads the tags of every file. Both re-read all playlists.",
                    horizontalPadding = 0,
                )
                if (vm.scanning) {
                    Text(vm.scanStatus, style = MaterialTheme.typography.bodyMedium)
                    val p = vm.scanProgress
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                    TextButton(onClick = { vm.stopScan() }) { Text("Stop") }
                } else if (vm.scanStatus.isNotEmpty()) {
                    Text(vm.scanStatus, style = MaterialTheme.typography.bodyMedium)
                }
                vm.error?.let {
                    Text(
                        text = "Problems:\n$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // ---- audio output
        item { SettingsTitle("Audio output") }
        items(OutputMode.entries) { mode ->
            OutputModeRow(
                mode = mode,
                selected = vm.playback.outputMode == mode,
                onSelect = { vm.setOutputMode(mode) },
            )
        }
        item {
            SubTitle("Output device")
            if (vm.playback.outputMode == OutputMode.OPENSL) {
                Note("OpenSL ES can't choose a device: it always plays where Android decides. Pick another mode to use this.")
            }
        }
        item {
            RadioRow(
                label = "Automatic",
                description = "Android decides (usually the most recently connected device)",
                selected = vm.playback.preferredDeviceKey == null,
                onSelect = { vm.setOutputDevice(null) },
            )
        }
        items(vm.playback.outputDevices, key = { it.key }) { device ->
            RadioRow(
                label = device.label,
                description = null,
                selected = vm.playback.preferredDeviceKey == device.key,
                onSelect = { vm.setOutputDevice(device.key) },
            )
        }
        val missing = vm.playback.preferredDeviceKey?.takeIf { k -> vm.playback.outputDevices.none { it.key == k } }
        if (missing != null) {
            item {
                RadioRow(
                    label = OutputDevices.labelFromKey(missing),
                    description = "Not connected: using Automatic until it's back",
                    selected = true,
                    onSelect = {},
                )
            }
        }
        item {
            val info = vm.playback.outputInfo
            Column(modifier = Modifier.padding(top = 4.dp)) {
                SubTitle("Now playing")
                if (info.input.isEmpty() && info.output.isEmpty()) {
                    Note("Play a song to see its format and the output Android opened.")
                } else {
                    InfoRow("File", info.input.ifEmpty { "-" })
                    if (info.decoder.isNotEmpty()) InfoRow("Decoder", info.decoder)
                    InfoRow("Output", info.output.ifEmpty { "-" })
                    if (info.direct.isNotEmpty()) InfoRow("Direct", info.direct)
                }
            }
        }

        // ---- open on wake
        item { SettingsTitle("Open on wake") }
        item { WakeLaunchSettings() }

        // ---- artist splitting
        item { SettingsTitle("Artist splitting") }
        item {
            Note(
                "Delimiters split artist and album artist tags into separate artists. Spaces count " +
                    "(␣ marks a space): \"␣&␣\" splits \"A & B\" but not \"A&B\". Case is ignored. " +
                    "Whitelisted names are never split. Changes apply right away; no rescan needed.",
            )
        }
        item { SubTitle("Delimiters") }
        items(vm.delimiters) { d ->
            RemovableRow(text = showSpaces(d), onRemove = { vm.removeDelimiter(d) })
        }
        item {
            AddRow(
                value = newDelimiter,
                onValueChange = { newDelimiter = it },
                label = "New delimiter",
                onAdd = {
                    vm.addDelimiter(newDelimiter) // not trimmed: spaces are part of a delimiter
                    newDelimiter = ""
                },
            )
        }
        item {
            TextButton(onClick = { vm.resetDelimiters() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Reset delimiters to defaults")
            }
        }
        item { SubTitle("Whitelist") }
        if (vm.whitelist.isEmpty()) {
            item { Note("Empty. Add names like \"Simon & Garfunkel\" or \"Earth, Wind & Fire\".") }
        }
        items(vm.whitelist) { name ->
            RemovableRow(text = name, onRemove = { vm.removeWhitelisted(name) })
        }
        item {
            AddRow(
                value = newWhitelisted,
                onValueChange = { newWhitelisted = it },
                label = "Artist name",
                onAdd = {
                    vm.addWhitelisted(newWhitelisted)
                    newWhitelisted = ""
                },
            )
        }
        item { SubTitle("Try it") }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = tryTag,
                    onValueChange = { tryTag = it },
                    label = { Text("Artist tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tryTag.isNotBlank()) {
                    val parts = vm.previewSplit(tryTag)
                    Text(
                        text = "${parts.size} artist${if (parts.size == 1) "" else "s"}: " + parts.joinToString("  |  "),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
        }

        // ---- artwork
        item { SettingsTitle("Artwork") }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Note(
                    "Covers come from the picture embedded in a song, or else an image in its folder " +
                        "(cover, folder, front, ...). They are cached; clear the cache after changing covers. " +
                        "Long-press an album or playlist tile to give it a custom image.",
                    horizontalPadding = 0,
                )
                OutlinedButton(onClick = { vm.clearArtworkCache() }) { Text("Clear artwork cache") }
            }
        }

        // ---- info
        item { SettingsTitle("Library") }
        item { InfoRow("Songs", count(stats.songs)) }
        item { InfoRow("Albums", count(stats.albums)) }
        item { InfoRow("Artists", count(stats.artists)) }
        item { InfoRow("Playlists", count(stats.playlists)) }
        item { InfoRow("Subfolders", count(stats.subdirs)) }
        item { InfoRow("Total size", formatBytes(stats.totalBytes)) }
        item { InfoRow("Total time", formatHours(stats.totalDurationMs)) }
        item {
            InfoRow(
                "Last scan",
                if (stats.lastScan == 0L) {
                    "Never"
                } else {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(stats.lastScan)) +
                        if (stats.lastScanFull) " (full)" else " (quick)"
                },
            )
        }
        item {
            Note(
                "Albums group songs by album tag and album artist tag (or by folder when there is no album " +
                    "artist tag). Artists are counted after splitting. Untagged files are in neither.",
            )
        }
    }

    confirmRemove?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove folder?") },
            text = { Text("${folderLabel(uri)} and everything found in it will be removed from the library. Your files are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeFolder(uri)
                    confirmRemove = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FolderRow(label: String, writable: Boolean, onAllowChanges: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (writable) "Read and write" else "Read only: adding to playlists and deleting files are off",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!writable) TextButton(onClick = onAllowChanges) { Text("Allow changes") }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun OutputModeRow(mode: OutputMode, selected: Boolean, onSelect: () -> Unit) =
    RadioRow(label = mode.label, description = mode.description, selected = selected, onSelect = onSelect)

@Composable
private fun RadioRow(label: String, description: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WakeLaunchSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(WakeLaunch.isEnabled(context)) }
    var allowed by remember { mutableStateOf(WakeLaunch.canOpenFromBackground(context)) }
    // Back from Android's permission screen: check again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = WakeLaunch.canOpenFromBackground(context) }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Open the player when the phone is unlocked",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    WakeLaunch.setEnabled(context, it)
                },
            )
        }
        Note(
            "With no screen lock, that's as soon as the screen turns on. Keeps a small background service " +
                "running, with a silent notification.",
            horizontalPadding = 0,
        )
        if (enabled && !allowed) {
            Text(
                "Needs \"Display over other apps\", or Android won't let the app open itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = { context.startActivity(WakeLaunch.overlaySettingsIntent(context)) }) {
                Text("Allow")
            }
        }
    }
}

/** Shows a delimiter in quotes with spaces made visible. */
private fun showSpaces(s: String): String = "\"" + s.replace(" ", "␣") + "\""

@Composable
private fun SettingsTitle(text: String) {
    Column {
        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun RemovableRow(text: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun AddRow(value: String, onValueChange: (String) -> Unit, label: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onAdd, enabled = value.isNotEmpty()) { Text("Add") }
    }
}

@Composable
private fun Note(text: String, horizontalPadding: Int = 16) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = horizontalPadding.dp, vertical = 6.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false).padding(start = 16.dp),
        )
    }
}

private fun count(n: Int): String = "%,d".format(n)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
    else -> "%.0f KB".format(bytes / 1e3)
}

private fun formatHours(ms: Long): String {
    val minutes = ms / 60_000
    return "${minutes / 60} h ${minutes % 60} min"
}
