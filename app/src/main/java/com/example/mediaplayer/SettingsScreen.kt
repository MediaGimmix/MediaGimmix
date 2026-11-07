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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.mutableIntStateOf
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    vm: LibraryViewModel,
    onAddFolder: () -> Unit,
    onRegrantFolder: (String) -> Unit,
    onBack: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    // Back on a page goes to the list of pages (this handler wins over the library screen's).
    BackHandler(enabled = page != null) { page = null }
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
                TextButton(onClick = { if (page == null) onBack() else page = null }) { Text("← Back") }
                Text(page?.title ?: "Settings", style = MaterialTheme.typography.titleMedium)
            }
        }

        when (page) {
            null -> {
                items(SettingsPage.entries) { p ->
                    SettingsPageRow(p, onClick = { page = p })
                }
            }
            SettingsPage.LIBRARY -> {
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

                // ---- info
                item { SettingsTitle("Totals") }
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
            SettingsPage.AUDIO -> {
                // ---- audio output
                item { SettingsTitle("Output mode") }
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
            }
            SettingsPage.JELLYFIN -> {
                item { JellyfinSettings(vm) }
            }
            SettingsPage.ARTISTS -> {
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
            }
            SettingsPage.SECURITY -> {
                item { SecurityKeySettings(vm) }
            }
            SettingsPage.WEB -> {
                item { WebControlSettings(vm.web) }
            }
            SettingsPage.ARTWORK -> {
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
            }
            SettingsPage.STARTUP -> {
                // ---- open on wake
                item { SettingsTitle("Splash screen") }
                item { SplashSetting() }
                item { SettingsTitle("Open on wake") }
                item { WakeLaunchSettings() }
            }
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
private fun JellyfinSettings(vm: LibraryViewModel) {
    val acc = vm.jellyfinAccount
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (acc == null) {
            var server by rememberSaveable { mutableStateOf(vm.jellyfinLastServer()) }
            var user by rememberSaveable { mutableStateOf("") }
            var password by remember { mutableStateOf("") } // never saved
            Text("Sign in to your Jellyfin server", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Server address") },
                placeholder = { Text("192.168.1.10:8096") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("User name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Button(
                onClick = {
                    vm.signInJellyfin(server, user, password)
                    password = ""
                },
                enabled = !vm.jellyfinBusy && server.isNotBlank() && user.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Sign in") }
            Note(
                "The password is sent to the server once and not stored; the app keeps only the access token. " +
                    "The app only ever contacts this server, and doesn't report what you play.",
                horizontalPadding = 0,
            )
        } else {
            if (vm.jellyfinLocked) {
                Text(
                    "Locked: plug in a trusted YubiKey (Settings → YubiKey).",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            InfoRowPadded("Server", acc.serverName)
            InfoRowPadded("Address", acc.server)
            InfoRowPadded("User", acc.userName)
            InfoRowPadded("Songs", count(vm.jellyfinSongs.size))
            InfoRowPadded("Playlists", count(vm.jellyfinPlaylists.size))
            InfoRowPadded(
                "Last sync",
                if (vm.jellyfinSyncedAt == 0L) "Never"
                else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(vm.jellyfinSyncedAt)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { vm.syncJellyfin() }, enabled = !vm.jellyfinBusy && !vm.jellyfinLocked) { Text("Sync now") }
                OutlinedButton(onClick = { vm.signOutJellyfin() }) { Text("Sign out") }
            }
            Note(
                "Songs stream as the original files. The library refreshes by itself when the app starts " +
                    "if the last sync is over 12 hours old.",
                horizontalPadding = 0,
            )
        }
        if (vm.jellyfinBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (vm.jellyfinStatus.isNotEmpty()) {
            Text(
                vm.jellyfinStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = if (vm.jellyfinStatus.contains("fail", true) || vm.jellyfinStatus.startsWith("Wrong") ||
                    vm.jellyfinStatus.startsWith("Couldn't") || vm.jellyfinStatus.contains("doesn't look")
                ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SecurityKeySettings(vm: LibraryViewModel) {
    val yk = vm.yubikey
    val keys = TokenVault.keys
    var renaming by remember { mutableStateOf<TrustedKey?>(null) }
    var removing by remember { mutableStateOf<TrustedKey?>(null) }
    var confirmProgram by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var slot by rememberSaveable { mutableIntStateOf(2) }
    var program by rememberSaveable { mutableStateOf(true) }
    var touch by rememberSaveable { mutableStateOf(true) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        // ---- status
        val status = when {
            keys.isEmpty() -> "Not protected: the Jellyfin sign-in is stored on the phone as is."
            vm.jellyfinLocked || TokenVault.isLocked() -> "Protected and locked: plug in a trusted YubiKey."
            TokenVault.unlocked -> "Protected and unlocked until the app closes."
            else -> "Protected."
        }
        Text(status, style = MaterialTheme.typography.bodyLarge)
        if (keys.isNotEmpty() && TokenVault.unlocked) {
            OutlinedButton(onClick = { vm.lockJellyfin() }, modifier = Modifier.padding(top = 6.dp)) { Text("Lock now") }
        }
        yk.prompt?.let { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(p, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                if (!p.startsWith("Jellyfin is locked")) TextButton(onClick = { yk.cancel() }) { Text("Cancel") }
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        yk.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }

        // ---- trusted keys
        SubTitleInline("Trusted keys")
        if (keys.isEmpty()) Note("None yet.", horizontalPadding = 0)
        keys.forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(k.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(
                            if (k.serial > 0) "Serial ${k.serial}" else null,
                            "slot ${k.slot}",
                            "added " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(k.added)),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { renaming = k }) { Text("Rename") }
                TextButton(onClick = { removing = k }) { Text("De-trust") }
            }
        }

        // ---- register
        SubTitleInline("Register a key")
        val canRegister = keys.isEmpty() || TokenVault.unlocked
        if (!canRegister) Note("Unlock with a trusted key first, then add more.", horizontalPadding = 0)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (e.g. Keychain 5C)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Slot", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Row {
            for (n in 1..2) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.selectable(selected = slot == n, onClick = { slot = n }, role = Role.RadioButton).padding(end = 16.dp),
                ) {
                    RadioButton(selected = slot == n, onClick = null)
                    Text(" Slot $n" + if (n == 1) " (often the factory OTP)" else "")
                }
            }
        }
        RadioRow(
            label = "Program the slot with a new secret",
            description = "Erases whatever is in slot $slot on the YubiKey and sets it up for challenge-response.",
            selected = program,
            onSelect = { program = true },
        )
        RadioRow(
            label = "Use the slot as it is",
            description = "Slot $slot is already set up for HMAC-SHA1 challenge-response (e.g. with YubiKey Manager).",
            selected = !program,
            onSelect = { program = false },
        )
        if (program) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Require a touch each time", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = touch, onCheckedChange = { touch = it })
            }
        }
        Button(
            onClick = { if (program) confirmProgram = true else yk.register(name, slot, program = false, requireTouch = false) },
            enabled = canRegister && yk.prompt?.startsWith("Plug in the YubiKey to register") != true,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Register") }
        Note(
            "Each trusted key can unlock on its own; de-trust a lost one here. Only the Jellyfin sign-in is " +
                "protected (your password is never stored). If a key is lost, also sign out of Jellyfin and in " +
                "again so the old sign-in stops working on the server.",
            horizontalPadding = 0,
        )
    }

    if (confirmProgram) {
        AlertDialog(
            onDismissRequest = { confirmProgram = false },
            title = { Text("Erase slot $slot?") },
            text = {
                Text(
                    "Whatever is stored in slot $slot of the YubiKey you plug in (a one-time password, static password " +
                        "or another challenge-response secret) is erased and can't be recovered. Other slots and the " +
                        "key's FIDO/PIV/OATH features are not touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmProgram = false
                    yk.register(name, slot, program = true, requireTouch = touch)
                }) { Text("Erase and register") }
            },
            dismissButton = { TextButton(onClick = { confirmProgram = false }) { Text("Cancel") } },
        )
    }
    renaming?.let { k ->
        var newName by remember(k.id) { mutableStateOf(k.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename key") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    TokenVault.rename(k.id, newName)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    removing?.let { k ->
        val last = keys.size == 1
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("De-trust ${k.name}?") },
            text = {
                Text(
                    if (last) {
                        "It's the only trusted key, so protection turns off and the Jellyfin sign-in is stored on " +
                            "the phone unprotected again. The YubiKey itself isn't changed."
                    } else {
                        "It can no longer unlock Jellyfin. The YubiKey itself isn't changed. If it was lost, also " +
                            "sign out of Jellyfin and in again."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.detrustKey(k.id)
                    removing = null
                }) { Text("De-trust") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WebControlSettings(web: WebControl) {
    var portText by rememberSaveable { mutableStateOf(web.port.toString()) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Web control server", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = web.running, onCheckedChange = { web.setEnabled(it) })
        }
        Note(
            "Always off when the app starts: switch it on each time you want it. Runs only while the app is open. " +
                "Anyone on the same network with the key can control the player and download songs.",
            horizontalPadding = 0,
        )
        web.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (web.running) {
            Text("Open in a browser on the same network:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            val addresses = web.addresses()
            if (addresses.isEmpty()) Text("No network address (not on Wi-Fi?)", style = MaterialTheme.typography.bodyMedium)
            addresses.forEach { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary) } }
        }

        SubTitleInline("Access key")
        SelectionContainer { Text(web.accessKey, style = MaterialTheme.typography.bodyLarge) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Require the key", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = web.requireKey, onCheckedChange = { web.changeRequireKey(it) })
        }
        OutlinedButton(onClick = { web.regenerateKey() }) { Text("New key") }
        Note(
            "Send it as an X-Api-Key header or ?key=... on API calls. The web page asks for it once and remembers it.",
            horizontalPadding = 0,
        )

        SubTitleInline("Port")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { portText.toIntOrNull()?.let { web.changePort(it) } }, enabled = portText.toIntOrNull() != web.port) {
                Text("Apply")
            }
        }
        Note(
            "The page is app/src/main/assets/web/index.html in the project. To change it without rebuilding, put your " +
                "own index.html in Android/data/com.example.mediaplayer/files/web/ on the phone; it wins over the built-in one.",
            horizontalPadding = 0,
        )
    }
}

@Composable
private fun SubTitleInline(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun InfoRowPadded(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false).padding(start = 16.dp),
        )
    }
}

/** The pages of Settings, listed on its first screen. */
enum class SettingsPage(val title: String, val summary: String) {
    LIBRARY("Library", "Music folders, scanning, library totals"),
    AUDIO("Audio", "Output mode, output device, what's playing"),
    JELLYFIN("Jellyfin", "Server, sign in, sync"),
    ARTISTS("Artist names", "Splitting artist tags, whitelist"),
    SECURITY("YubiKey", "Lock the Jellyfin sign-in behind security keys"),
    WEB("Web control", "Control the player from a browser or the API"),
    ARTWORK("Artwork", "Cover cache"),
    STARTUP("Startup", "Splash screen, open on wake"),
}

@Composable
private fun SettingsPageRow(page: SettingsPage, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(page.title, style = MaterialTheme.typography.titleMedium)
        Text(page.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
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
private fun SplashSetting() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(SplashConfig.isEnabled(context)) }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show the splash at startup", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = on,
                onCheckedChange = {
                    on = it
                    SplashConfig.setEnabled(context, it)
                },
            )
        }
        Note(
            "Also covers the splash when Open on wake brings the app up. Its timing is in SplashConfig.kt.",
            horizontalPadding = 0,
        )
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
