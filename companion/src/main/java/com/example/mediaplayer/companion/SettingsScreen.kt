package com.example.mediaplayer.companion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: RemoteViewModel) {
    val c = vm.connection
    var host by rememberSaveable { mutableStateOf(c.host) }
    var port by rememberSaveable { mutableStateOf(c.port.toString()) }
    var key by rememberSaveable { mutableStateOf(c.key) }
    var bluetooth by rememberSaveable { mutableStateOf(c.bluetooth) }
    var btAddress by rememberSaveable { mutableStateOf(c.btAddress) }
    var btName by rememberSaveable { mutableStateOf(c.btName) }
    val edited = Connection(host.trim(), port.toIntOrNull() ?: 0, key.trim(), bluetooth, btAddress, btName)
    val valid = if (bluetooth) btAddress.isNotEmpty() else edited.host.isNotEmpty() && edited.port in 1..65535
    var limit by rememberSaveable { mutableStateOf(vm.searchLimit.toString()) }

    val context = LocalContext.current
    var btAllowed by remember { mutableStateOf(BluetoothDevices.allowed(context)) }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        btAllowed = granted
        if (granted) bluetooth = true
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Player connection", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !bluetooth, onClick = { bluetooth = false }, label = { Text("Wi-Fi") })
            FilterChip(
                selected = bluetooth,
                onClick = {
                    if (btAllowed) bluetooth = true else askBluetooth.launch(BluetoothDevices.PERMISSION)
                },
                label = { Text("Bluetooth") },
            )
        }
        if (!bluetooth) {
            Text(
                "On the player: Settings → Network → Web control. Switch it on (it's off every time the player starts), " +
                    "then copy the address and access key from there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("Address (e.g. 192.168.1.12)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                "Pair this phone with the player in Android's Bluetooth settings, then on the player: Settings → Network → " +
                    "Bluetooth control (off every time the player starts). Pick the player below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val paired = remember(btAllowed) { BluetoothDevices.paired(context) }
            if (paired.isEmpty()) {
                Text(
                    "No paired devices found. Pair the player first (and check Bluetooth is on).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            paired.forEach { d ->
                Row(
                    modifier = Modifier.fillMaxWidth().selectable(
                        selected = d.address == btAddress,
                        onClick = { btAddress = d.address; btName = d.name },
                        role = Role.RadioButton,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = d.address == btAddress, onClick = null)
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        Text(d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("Access key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.saveConnection(edited) }, enabled = valid) { Text("Save and connect") }

        val status = vm.status
        val err = vm.error
        Text(
            when {
                !vm.connection.isSet -> "Not set up yet."
                err != null -> err
                status != null -> "Connected to ${vm.connection.label}."
                else -> "Connecting…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (err != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        )

        Text("Notification", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Media notification", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Shows what the player is playing, with buttons, while a song is loaded (no seek bar). " +
                        "Keeps checking the player in the background until it stops or you tap ✕.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = vm.notificationOn, onCheckedChange = { vm.changeNotification(it) })
        }

        LastFmSettings()

        Text("Search", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = limit,
                onValueChange = { limit = it.filter(Char::isDigit).take(4) },
                label = { Text("Most results") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { limit.toIntOrNull()?.let { vm.changeSearchLimit(it); limit = vm.searchLimit.toString() } },
                enabled = limit.toIntOrNull() != vm.searchLimit,
            ) { Text("Apply") }
        }
        Text(
            "Most songs, albums and artists (each) a search shows, and most playlists listed " +
                "(1 to ${RemoteViewModel.MAX_SEARCH_LIMIT}, default ${RemoteViewModel.DEFAULT_SEARCH_LIMIT}). " +
                "The player also has its own limit, which wins when it's lower.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Last.fm scrobbling: sign in once (the password isn't kept), then plays on the player are scrobbled. */
@Composable
private fun LastFmSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(LastFm.account(context)) }
    var enabled by remember { mutableStateOf(Scrobbler.isEnabled(context)) }
    var apiKey by rememberSaveable { mutableStateOf(LastFm.savedApiKey(context)) }
    var secret by rememberSaveable { mutableStateOf(LastFm.savedSecret(context)) }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // never saved
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { Scrobbler.refreshPending(context) }

    Text("Last.fm", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    val a = account
    if (a != null) {
        Text("Signed in as ${a.user}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Scrobble", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Songs the player plays while this app or its notification is running: \"now playing\" at the start, " +
                        "a scrobble after half the song or 4 minutes (songs over 30 s).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it; Scrobbler.setEnabled(context, it) })
        }
        if (Scrobbler.lastResult.isNotEmpty()) {
            Text(Scrobbler.lastResult, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (Scrobbler.pendingCount > 0) {
                OutlinedButton(onClick = { Scrobbler.retry(context) }) { Text("Send ${Scrobbler.pendingCount} waiting") }
            }
            OutlinedButton(onClick = { LastFm.signOut(context); account = null }) { Text("Sign out") }
        }
    } else {
        Text(
            "Needs your own Last.fm API account: make one at last.fm/api/account/create (any app name; callback can be " +
                "empty), then copy its API key and shared secret here. Your password is only used to sign in once and " +
                "isn't stored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(apiKey, { apiKey = it.trim() }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(secret, { secret = it.trim() }, label = { Text("Shared secret") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it.trim() }, label = { Text("Last.fm username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            password, { password = it },
            label = { Text("Last.fm password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && apiKey.isNotEmpty() && secret.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty(),
            onClick = {
                busy = true
                message = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { LastFm.signIn(context, apiKey, secret, user, password) } }
                    password = ""
                    busy = false
                    r.onSuccess { account = LastFm.account(context) }
                        .onFailure { message = it.message ?: "Couldn't sign in" }
                }
            },
        ) { Text(if (busy) "Signing in…" else "Sign in") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}
