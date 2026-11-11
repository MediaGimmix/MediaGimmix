package com.example.mediaplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.Color
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    var category by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    // Back: page -> its category -> the category list (these win over the library screen's handler).
    val goBack = {
        when {
            page != null -> page = null
            category != null -> category = null
            else -> onBack()
        }
    }
    BackHandler(enabled = page != null || category != null) { goBack() }
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
                TextButton(onClick = goBack) { Text("← Back") }
                Text(page?.title ?: category?.title ?: "Settings", style = MaterialTheme.typography.titleMedium)
            }
        }

        when (page) {
            null -> {
                val c = category
                if (c == null) {
                    items(SettingsCategory.entries) { cat ->
                        SettingsPageRow(
                            cat.title, if (cat.available) cat.summary else NOT_BUILT_IN,
                            enabled = cat.available, onClick = { category = cat },
                        )
                    }
                    item { AboutInfo(vm) }
                } else {
                    items(c.pages) { p ->
                        SettingsPageRow(
                            p.title, if (p.available) p.summary else NOT_BUILT_IN,
                            enabled = p.available, onClick = { page = p },
                        )
                    }
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
            SettingsPage.BUTTONS -> {
                item { ButtonSettings(vm.playback.buttons) }
            }
            SettingsPage.NETWORK -> {
                item { NetworkSettings(vm) }
            }
            SettingsPage.STAY_ON_TOP -> {
                item { StayOnTopSettings() }
            }
            SettingsPage.TRACK_INFO -> {
                item {
                    Note(
                        "Recently added tracks, newest first. Tap one for suggested tags (from its filename" +
                            (if (BuildConfig.FEATURE_MUSICBRAINZ) " and MusicBrainz" else "") + ") and write your pick " +
                            "into the file. Works for MP3, M4A, FLAC and OGG.",
                    )
                }
                items(vm.recentlyAdded, key = { it.uri.toString() }) { song -> TrackInfoRow(vm, song) }
            }
            SettingsPage.DOWNLOADER -> {
                item { DownloaderSettings(vm.downloader) }
            }
            SettingsPage.ARTIST_ART -> {
                item { ArtistArtworkSettings(vm) }
            }
            SettingsPage.NETSHARE -> {
                item { NetshareSettings(vm) }
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
            SettingsPage.BLUETOOTH -> {
                item { BluetoothControlSettings(vm) }
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

/** The netshare settings: a sign-in and status panel per server built into the app. */
@Composable
private fun NetshareSettings(vm: LibraryViewModel) {
    Column {
        vm.servers.forEachIndexed { i, st ->
            if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            ServerPanel(vm, st)
        }
    }
}

@Composable
private fun ServerPanel(vm: LibraryViewModel, st: ServerState) {
    val acc = st.account
    val b = st.backend
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (acc == null) {
            // Typed values; secrets are cleared after each attempt and never saved.
            val values = remember(st.id) { mutableStateMapOf("server" to vm.lastServer(st)) }
            Text("Sign in to your ${st.label} server", style = MaterialTheme.typography.titleSmall)
            b.fields.forEachIndexed { i, f ->
                OutlinedTextField(
                    value = values[f.key].orEmpty(),
                    onValueChange = { values[f.key] = it },
                    label = { Text(f.label + if (f.optional) " (optional)" else "") },
                    placeholder = if (f.placeholder.isNotEmpty()) ({ Text(f.placeholder) }) else null,
                    singleLine = true,
                    visualTransformation = if (f.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when {
                            f.secret -> KeyboardType.Password
                            f.key == "server" -> KeyboardType.Uri
                            else -> KeyboardType.Text
                        },
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = if (i == 0) 8.dp else 4.dp),
                )
            }
            Button(
                onClick = {
                    vm.signIn(st, values.toMap())
                    b.fields.filter { it.secret }.forEach { values.remove(it.key) }
                },
                enabled = !st.busy && b.fields.all { it.optional || !values[it.key].isNullOrBlank() },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Sign in") }
            b.hint?.let { Note(it, horizontalPadding = 0) }
            Note(
                "The app keeps only ${b.keeps}, never your password. " +
                    "It only ever contacts this server, and doesn't report what you play.",
                horizontalPadding = 0,
            )
        } else {
            if (st.locked) {
                Text(
                    "Locked: plug in a trusted YubiKey (Settings → System → YubiKey).",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(st.label, style = MaterialTheme.typography.titleSmall)
            InfoRowPadded("Server", acc.serverName)
            InfoRowPadded("Address", acc.server)
            InfoRowPadded("User", acc.userName)
            InfoRowPadded("Songs", count(st.songs.size))
            InfoRowPadded("Playlists", count(st.playlists.size))
            InfoRowPadded(
                "Last sync",
                if (st.syncedAt == 0L) "Never"
                else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(st.syncedAt)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { vm.syncServer(st) }, enabled = !st.busy && !st.locked) { Text("Sync now") }
                OutlinedButton(onClick = { vm.signOut(st) }) { Text("Sign out") }
            }
            Note(
                "Songs stream as the original files. The library refreshes by itself when the app starts " +
                    "if the last sync is over 12 hours old.",
                horizontalPadding = 0,
            )
        }
        if (st.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        val status = st.status
        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.contains("fail", true) || status.startsWith("Wrong") || status.startsWith("Couldn't") ||
                    status.contains("doesn't look") || status.contains("check", true) || status.contains("refused")
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
            keys.isEmpty() -> "Not protected: the netshare sign-ins are stored on the phone as is."
            vm.servers.any { it.locked } || TokenVault.isLocked() -> "Protected and locked: plug in a trusted YubiKey."
            TokenVault.unlocked -> "Protected and unlocked until the app closes."
            else -> "Protected."
        }
        Text(status, style = MaterialTheme.typography.bodyLarge)
        if (keys.isNotEmpty() && TokenVault.unlocked) {
            OutlinedButton(onClick = { vm.lockServers() }, modifier = Modifier.padding(top = 6.dp)) { Text("Lock now") }
        }
        yk.prompt?.let { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(p, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                if (!p.startsWith("Netshare is locked")) TextButton(onClick = { yk.cancel() }) { Text("Cancel") }
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
            "Each trusted key can unlock on its own; de-trust a lost one here. Only the netshare sign-ins " +
                "(Jellyfin, Navidrome, mStream) are protected (your passwords are never stored). If a key is lost, " +
                "also sign out of the netshare servers and in again so the old sign-ins stop working.",
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
                        "It's the only trusted key, so protection turns off and the netshare sign-ins are stored on " +
                            "the phone unprotected again. The YubiKey itself isn't changed."
                    } else {
                        "It can no longer unlock the netshare sign-ins. The YubiKey itself isn't changed. " +
                            "If it was lost, also sign out of the netshare servers and in again."
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
    var limitText by rememberSaveable { mutableStateOf(web.listLimit.toString()) }
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
            Text("API address on the same network:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
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
            "Send it as an X-Api-Key header or ?key=... on API calls.",
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
        SubTitleInline("Most items per reply")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = limitText,
                onValueChange = { limitText = it.filter(Char::isDigit).take(4) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { limitText.toIntOrNull()?.let { web.changeListLimit(it); limitText = web.listLimit.toString() } },
                enabled = limitText.toIntOrNull() != web.listLimit,
            ) { Text("Apply") }
        }
        Note(
            "No API reply lists more songs, albums, artists or playlists than this (1 to ${WebControl.MAX_LIST_LIMIT}), " +
                "so a companion app or script can never pull the whole library. Apps can ask for fewer, not more. " +
                "The queue always sends the last ${WebControl.QUEUE_BACK} played and up to ${WebControl.QUEUE_AHEAD} coming up.",
            horizontalPadding = 0,
        )
        Note(
            "The endpoint list is at the top of WebControl.kt in the project.",
            horizontalPadding = 0,
        )
    }
}

@Composable
private fun NetworkSettings(vm: LibraryViewModel) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SwitchRow(
            builtIn = Netshare.anyBuiltIn || BuildConfig.FEATURE_ARTIST_PICTURES || BuildConfig.FEATURE_DOWNLOADER,
            title = "Internet access",
            description =
            "Requests to the open internet: artist pictures, and a netshare server (Jellyfin, Navidrome, mStream) " +
                "on a public address. Off = nothing leaves the local network. A server at home keeps working.",
            checked = NetPolicy.internet,
        ) { vm.setInternetAccess(it) }
        SwitchRow(
            builtIn = BuildConfig.FEATURE_WEB_CONTROL,
            title = "Local network requests",
            description =
            "Companion apps, scripts and anything else calling the web control API. Off = the API server " +
                "stops and can't be switched on.",
            checked = NetPolicy.localApi,
        ) { vm.setLocalApiAccess(it) }
        SwitchRow(
            builtIn = BuildConfig.FEATURE_BLUETOOTH_CONTROL,
            title = "Bluetooth requests",
            description =
            "The same API over Bluetooth, from a paired phone (the companion app). Off = Bluetooth control " +
                "stops and can't be switched on. Separate from the local network switch.",
            checked = NetPolicy.bluetoothApi,
        ) { vm.setBluetoothApiAccess(it) }
    }
}

@Composable
private fun ArtistArtworkSettings(vm: LibraryViewModel) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "${"%,d".format(vm.artistPhotoIds.size)} downloaded" +
                vm.artistFetchStatus.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { vm.retryArtistPictures() }) { Text("Look again") }
            OutlinedButton(onClick = { vm.deleteArtistPictures() }) { Text("Delete all") }
        }
        Note(
            "Looked up on Deezer by artist name whenever the app is on Wi-Fi (exact name matches only). " +
                "Without a picture, an artist shows the cover of their newest album (by year tag). " +
                "Long-press an artist to use your own image instead. \"Look again\" retries artists that had no picture.",
            horizontalPadding = 0,
        )
    }
}

/** Version and other details, at the bottom of the category list. Long-press a value to copy it. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun AboutInfo(vm: LibraryViewModel) {
    val stats = vm.stats
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp)) {
        Text("About", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
        SelectionContainer {
            Column {
                InfoRowPadded("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}")
                if (BuildConfig.FEATURES_OMITTED.isNotEmpty()) InfoRowPadded("Left out", BuildConfig.FEATURES_OMITTED)
                InfoRowPadded("Commit", BuildConfig.GIT_COMMIT)
                InfoRowPadded("Built", BuildConfig.BUILD_TIME)
                InfoRowPadded("Package", BuildConfig.APPLICATION_ID)
                InfoRowPadded("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                InfoRowPadded(
                    "Android",
                    "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · " +
                        android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                )
                InfoRowPadded("Audio output", vm.playback.outputMode.label)
                InfoRowPadded(
                    "Library",
                    "${"%,d".format(stats.songs)} songs · ${"%,d".format(stats.albums)} albums · " +
                        "${"%,d".format(stats.artists)} artists",
                )
                vm.servers.filter { it.songs.isNotEmpty() }.forEach { st ->
                    InfoRowPadded(st.label, "${"%,d".format(st.songs.size)} songs")
                }
                InfoRowPadded("Artist pictures", "%,d".format(vm.artistPhotoIds.size))
                InfoRowPadded(
                    "Network",
                    listOf(
                        if (NetPolicy.internet) "internet on" else "internet off",
                        if (NetPolicy.localApi) "local API allowed" else "local API off",
                        if (NetPolicy.bluetoothApi) "Bluetooth API allowed" else "Bluetooth API off",
                    ).joinToString(" · "),
                )
                InfoRowPadded("Media3", androidx.media3.common.MediaLibraryInfo.VERSION)
                InfoRowPadded("Kotlin", KotlinVersion.CURRENT.toString())
            }
        }
    }
}

@Composable
private fun BluetoothControlSettings(vm: LibraryViewModel) {
    val bt = vm.bluetooth
    // Android 12+: "Nearby devices" permission, asked when switching on.
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) bt.setEnabled(true)
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bluetooth control", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = bt.running, onCheckedChange = { on ->
                if (on && !bt.hasPermission() && android.os.Build.VERSION.SDK_INT >= 31) {
                    askPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    bt.setEnabled(on)
                }
            })
        }
        Note(
            "Runs only while the app is open. A paired phone with the companion app can then use the same API as " +
                "Web control, without Wi-Fi. Pair the phones in Android's Bluetooth settings first.",
            horizontalPadding = 0,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Start with the app", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Switch Bluetooth control on whenever the app opens. Never overrides the \"Bluetooth requests\" " +
                        "switch in Network switches: when that's off, Bluetooth control stays off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = bt.autoStart, onCheckedChange = { bt.changeAutoStart(it) })
        }
        bt.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (bt.running) {
            Text(
                if (bt.connected.isEmpty()) "Waiting for a paired phone…" else "Connected: " + bt.connected.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        SubTitleInline("Access key")
        SelectionContainer { Text(vm.web.accessKey, style = MaterialTheme.typography.bodyLarge) }
        Note(
            "The same key as Web control (change it there). The companion app needs it in its settings.",
            horizontalPadding = 0,
        )
    }
}

@Composable
private fun StayOnTopSettings() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(WakeLaunch.canOpenFromBackground(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = WakeLaunch.canOpenFromBackground(context) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SwitchRow(
            "Stay on top",
            "While the device is awake and unlocked, the player is always the app in front, like Pure Music mode: " +
                "leave it (Home, Recents, another app) and it comes straight back. Other apps keep running in the " +
                "background. Keeps the small Open on wake service running.",
            StayOnTop.on,
        ) { StayOnTop.setOn(context, it) }
        SwitchRow(
            "Keep the screen on",
            "While Stay on top is on, the screen doesn't turn off by itself while the player is in front. " +
                "The power button still turns it off. Uses more battery.",
            StayOnTop.keepScreenOn,
        ) { StayOnTop.setKeepScreenOn(context, it) }
        if (StayOnTop.on && !allowed) {
            Text(
                "Needs \"Display over other apps\", or Android won't let the app bring itself back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = {
                StayOnTop.allowLeaving()
                context.startActivity(WakeLaunch.overlaySettingsIntent(context))
            }) { Text("Allow it") }
        }
        SwitchRow(
            "Show the pin",
            "The push-pin switch on the navigation bar, the now playing screen and search (never in Settings). " +
                "Off hides it everywhere; Stay on top can then only be changed here.",
            StayOnTop.showSwitch,
        ) { StayOnTop.setShowSwitch(context, it) }
    }
}

@Composable
private fun DownloaderSettings(dl: Downloader) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var video by rememberSaveable { mutableStateOf(false) }
    // Which folder the picker is for (only opened with a Change button, then remembered).
    var pickingVideo by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val rw = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, rw) }
            dl.setFolder(pickingVideo, uri.toString())
        }
    }
    fun choose(forVideo: Boolean) {
        pickingVideo = forVideo
        StayOnTop.allowLeaving() // the folder picker
        picker.launch(null)
    }
    val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) dl.importCookies(uri)
    }
    LaunchedEffect(Unit) { dl.prepare() }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim() },
            label = { Text("Link (from your media server)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !video, onClick = { video = false }, label = { Text("Track (audio)") })
            FilterChip(selected = video, onClick = { video = true }, label = { Text("Video") })
            Spacer(modifier = Modifier.weight(1f))
            if (dl.running) {
                OutlinedButton(onClick = { dl.cancel() }) { Text("Cancel") }
            } else {
                Button(onClick = { dl.download(url, video) }, enabled = url.isNotBlank()) { Text("Download") }
            }
        }
        if (dl.running || dl.status.isNotEmpty()) {
            if (dl.running) {
                if (dl.progress >= 0f) {
                    LinearProgressIndicator(progress = { dl.progress / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Text(dl.status, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        dl.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        SubTitleInline("Folders")
        FolderChoice("Tracks go to", dl.folderLabel(dl.audioFolder), dl.audioFolder == null, enabled = !dl.running) { choose(false) }
        FolderChoice("Videos go to", dl.folderLabel(dl.videoFolder), dl.videoFolder == null, enabled = !dl.running) { choose(true) }
        Note(
            "Chosen once and remembered. Tracks are saved as MP3 (best quality) with their tags and cover, and the " +
                "library rescans afterwards if the folder is one of your music folders. Videos are saved as MP4.",
            horizontalPadding = 0,
        )

        SubTitleInline("Sign-in cookies")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                dl.cookieSites?.let { "For: $it" } ?: "None",
                style = MaterialTheme.typography.bodyMedium,
                color = if (dl.cookieSites != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (dl.cookieSites != null) {
                TextButton(onClick = { dl.clearCookies() }, enabled = !dl.running) { Text("Remove") }
            }
            OutlinedButton(onClick = {
                StayOnTop.allowLeaving() // the file picker
                cookiePicker.launch(arrayOf("text/plain", "*/*"))
            }, enabled = !dl.running) { Text("Import") }
        }
        Note(
            "For servers that need you signed in (cookie auth): export a cookies.txt from your browser and import it " +
                "here. It's kept privately in the app and sent to yt-dlp with every download; only the site names " +
                "are shown. Re-import when your login expires.",
            horizontalPadding = 0,
        )

        SubTitleInline("yt-dlp")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Version ${dl.version ?: "…"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { dl.updateYtDlp() }, enabled = !dl.running) { Text("Update") }
        }
        Note(
            "Sites change and old yt-dlp versions stop working; Update fetches the newest one from GitHub " +
                "(needs internet access on). Links to the open internet are refused while internet access is off; " +
                "your home server always works.",
            horizontalPadding = 0,
        )
    }
}

@Composable
private fun TrackInfoRow(vm: LibraryViewModel, song: Song) {
    var open by remember { mutableStateOf(false) }
    val untagged = TrackInfo.looksUntagged(song)
    Column(
        modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(song.displayTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            (if (untagged) "Tags look missing · " else (song.artist?.let { "$it · " } ?: "")) + song.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = if (untagged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (open) TrackInfoDialog(vm, song, onClose = { open = false })
}

/** Suggested tags for one track; pick one, then Write. */
@Composable
private fun TrackInfoDialog(vm: LibraryViewModel, song: Song, onClose: () -> Unit) {
    val fromName = remember(song) { TrackInfo.fromFilename(song) }
    var mb by remember(song) { mutableStateOf<List<TrackInfo.Option>?>(null) }
    var mbProblem by remember(song) { mutableStateOf<String?>(null) }
    var chosen by remember(song) { mutableStateOf<TrackInfo.Option?>(null) }
    var writing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val writable = TrackInfo.canWrite(song)

    // MusicBrainz: search with the current tags if they look right, else the filename's best guess.
    LaunchedEffect(song) {
        if (!BuildConfig.FEATURE_MUSICBRAINZ) return@LaunchedEffect
        if (!NetPolicy.internet) {
            mbProblem = "Internet access is off (Settings → Network)."
            return@LaunchedEffect
        }
        val guess = if (!TrackInfo.looksUntagged(song)) TrackInfo.Option(song.title ?: "", song.artist, source = "")
        else fromName.first()
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { TrackInfo.searchMusicBrainz(guess.title, guess.artist) }
        }
        result.onSuccess { mb = it }.onFailure { mbProblem = "MusicBrainz: ${it.message}" }
    }

    AlertDialog(
        onDismissRequest = { if (!writing) onClose() },
        title = { Text(song.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text(song.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Now: " + listOfNotNull(song.artist, song.album, song.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
                        .ifEmpty { "no tags" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!writable) {
                    Text(
                        "This file's format can't be edited (only MP3, M4A, FLAC, OGG...). New downloads are MP3.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                SubTitleInline("From the filename")
                fromName.forEach { o -> OptionRow(o, chosen == o, enabled = writable) { chosen = o } }
                SubTitleInline("MusicBrainz")
                when {
                    !BuildConfig.FEATURE_MUSICBRAINZ -> Text(
                        NOT_BUILT_IN, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    mbProblem != null -> Text(mbProblem!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    mb == null -> Text("Searching…", style = MaterialTheme.typography.bodySmall)
                    mb!!.isEmpty() -> Text("No matches.", style = MaterialTheme.typography.bodySmall)
                    else -> mb!!.forEach { o -> OptionRow(o, chosen == o, enabled = writable) { chosen = o } }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            Button(
                enabled = chosen != null && writable && !writing,
                onClick = {
                    val o = chosen ?: return@Button
                    writing = true
                    problem = null
                    vm.writeTrackInfo(song, o) { err ->
                        writing = false
                        if (err == null) onClose() else problem = err
                    }
                },
            ) { Text(if (writing) "Writing…" else "Write tags") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !writing) { Text("Cancel") } },
    )
}

@Composable
private fun OptionRow(o: TrackInfo.Option, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(o.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                o.artist, o.album, o.year?.toString(), o.track?.let { "track $it" },
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FolderChoice(label: String, folder: String, missing: Boolean, enabled: Boolean, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                folder,
                style = MaterialTheme.typography.bodySmall,
                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(onClick = onChange, enabled = enabled) { Text(if (missing) "Choose" else "Change") }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    builtIn: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val alpha = if (builtIn) 1f else 0.4f
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Text(
                if (builtIn) description else NOT_BUILT_IN,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
        Switch(checked = checked && builtIn, onCheckedChange = onChange, enabled = builtIn)
    }
}

/** Two columns of button → action dropdowns (see [HwButton.column]) split by a dark accent line. */
@Composable
private fun ButtonSettings(buttons: ButtonMap) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            ButtonColumn(buttons, 0, Modifier.weight(1f).padding(end = 12.dp))
            Box(modifier = Modifier.fillMaxHeight().width(2.dp).background(DarkAccent))
            ButtonColumn(buttons, 1, Modifier.weight(1f).padding(start = 12.dp))
        }
        OutlinedButton(
            onClick = { buttons.resetAll() },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Reset to normal") }
        Note(
            "Normal = the button does its usual job. Volume buttons can only be remapped while the app is on " +
                "screen; media buttons (on the device, headphones or Bluetooth) also work in the background " +
                "while music is loaded.",
            horizontalPadding = 0,
        )
    }
}

@Composable
private fun ButtonColumn(buttons: ButtonMap, column: Int, modifier: Modifier) {
    Column(modifier = modifier) {
        HwButton.entries.filter { it.column == column }.forEach { b ->
            Text(b.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            ActionDropdown(buttons[b]) { buttons.set(b, it) }
        }
    }
}

@Composable
private fun ActionDropdown(current: ButtonAction, onPick: (ButtonAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("\u25BE")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ButtonAction.entries.forEach { a ->
                DropdownMenuItem(
                    text = {
                        Text(
                            a.label,
                            color = if (a == current) MaterialTheme.colorScheme.secondary else Color.Unspecified,
                        )
                    },
                    onClick = { onPick(a); open = false },
                )
            }
        }
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
/** A settings page; each belongs to one [SettingsCategory]. */
enum class SettingsPage(val title: String, val summary: String) {
    AUDIO("Audio", "Output mode, output device, what's playing"),
    BUTTONS("Buttons", "Remap the volume and media buttons"),
    NETWORK("Network switches", "Internet access, local network requests"),
    NETSHARE("Netshare", "Jellyfin, Navidrome and mStream servers, sign in, sync"),
    WEB("Web control", "Control the player through an HTTP API"),
    BLUETOOTH("Bluetooth control", "The same API for a paired phone, over Bluetooth"),
    LIBRARY("Library", "Music folders, scanning, library totals"),
    ARTISTS("Artist names", "Splitting artist tags, whitelist"),
    ARTWORK("Artwork cache", "Album and playlist covers"),
    ARTIST_ART("Artist artwork", "Downloaded artist pictures"),
    DOWNLOADER("Downloader", "Download tracks and videos with yt-dlp"),
    TRACK_INFO("Track info", "Fix the tags of recently added tracks"),
    STARTUP("Startup", "Splash screen, open on wake"),
    SECURITY("YubiKey", "Lock the netshare sign-ins behind security keys"),
    STAY_ON_TOP("Stay on top", "Keep the player in front; show or hide its pin"),
    ;

    /** False for pages of features left out of this build (-Pmg.no* flags, see the README). */
    val available: Boolean
        get() = when (this) {
            DOWNLOADER -> BuildConfig.FEATURE_DOWNLOADER
            NETSHARE, SECURITY -> Netshare.anyBuiltIn
            WEB -> BuildConfig.FEATURE_WEB_CONTROL
            BLUETOOTH -> BuildConfig.FEATURE_BLUETOOTH_CONTROL
            ARTIST_ART -> BuildConfig.FEATURE_ARTIST_PICTURES
            // The switches page is only useful with something network-facing in the build.
            NETWORK -> Netshare.anyBuiltIn || BuildConfig.FEATURE_WEB_CONTROL || BuildConfig.FEATURE_BLUETOOTH_CONTROL ||
                BuildConfig.FEATURE_ARTIST_PICTURES
            else -> true
        }
}

/** The top level of Settings. New pages go into one of these (the user names the category). */
enum class SettingsCategory(val title: String, val summary: String, val pages: List<SettingsPage>) {
    AUDIO("Audio", "Output, buttons", listOf(SettingsPage.AUDIO, SettingsPage.BUTTONS)),
    NETWORK(
        "Network", "Network switches, netshare, web and Bluetooth control",
        listOf(SettingsPage.NETWORK, SettingsPage.NETSHARE, SettingsPage.WEB, SettingsPage.BLUETOOTH),
    ),
    LIBRARY(
        "Library", "Folders, artist names, artwork, downloader",
        listOf(SettingsPage.LIBRARY, SettingsPage.ARTISTS, SettingsPage.ARTWORK, SettingsPage.ARTIST_ART, SettingsPage.DOWNLOADER,
            SettingsPage.TRACK_INFO,
        ),
    ),
    SYSTEM(
        "System", "Startup, YubiKey, stay on top",
        listOf(SettingsPage.STARTUP, SettingsPage.SECURITY, SettingsPage.STAY_ON_TOP),
    ),
    ;

    /** Hidden when every page in it was left out of this build. */
    val available: Boolean get() = pages.any { it.available }
}

@Composable
private fun SettingsPageRow(title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
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
            OutlinedButton(onClick = {
                StayOnTop.allowLeaving()
                context.startActivity(WakeLaunch.overlaySettingsIntent(context))
            }) {
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
