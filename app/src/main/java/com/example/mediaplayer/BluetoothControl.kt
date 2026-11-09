package com.example.mediaplayer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.util.UUID

/**
 * The web control API over classic Bluetooth (RFCOMM) for the companion app on a paired phone.
 * Same requests and replies as over the network (HTTP/1.1 text, kept open between requests), same
 * access key and limits; handled by [WebControl.handleApi].
 *
 * Own switch (Settings → Network → Bluetooth control). [running] isn't saved; it only comes on with the
 * app when "Start with the app" ([autoStart], "bt_autostart") is on and the "Bluetooth requests" kill switch allows it.
 * Settings → Network → Network switches → "Bluetooth requests" off blocks it entirely.
 * Pairing is done in Android's Bluetooth settings; only paired phones can connect.
 */
class BluetoothControl(private val context: Context, private val web: WebControl) {

    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Names of the phones connected right now. */
    var connected by mutableStateOf<List<String>>(emptyList())
        private set

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Switch Bluetooth control on whenever the app opens. Off by default. */
    var autoStart by mutableStateOf(prefs.getBoolean(KEY_AUTOSTART, false))
        private set

    fun changeAutoStart(on: Boolean) {
        autoStart = on
        prefs.edit().putBoolean(KEY_AUTOSTART, on).apply()
    }

    /** At app start: on only if [autoStart] and the kill switch allow it (and permission was given before). */
    fun startIfWanted() {
        if (autoStart && NetPolicy.bluetoothApi && hasPermission()) start()
    }

    private val main = Handler(Looper.getMainLooper())
    private var server: BluetoothServerSocket? = null
    private val clients = HashSet<BluetoothSocket>()

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun setEnabled(on: Boolean) {
        if (on) start() else stop()
    }

    @SuppressLint("MissingPermission") // checked in hasPermission()
    private fun start() {
        if (running || !BuildConfig.FEATURE_BLUETOOTH_CONTROL) return
        if (!NetPolicy.bluetoothApi) {
            error = "Bluetooth requests are off (Settings → Network → Network switches)."
            return
        }
        if (!hasPermission()) {
            error = "Allow \"Nearby devices\" for this app first."
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            error = "This device has no Bluetooth."
            return
        }
        if (!adapter.isEnabled) {
            error = "Bluetooth is off. Switch it on, then try again."
            return
        }
        val socket = try {
            adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        } catch (e: IOException) {
            error = "Couldn't start: ${e.message}"
            return
        }
        server = socket
        error = null
        running = true
        Thread({ acceptLoop(socket) }, "bt-api-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        running = false
        connected = emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop(socket: BluetoothServerSocket) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break // closed by stop()
            }
            val name = runCatching { client.remoteDevice.name }.getOrNull() ?: client.remoteDevice.address
            synchronized(clients) { clients.add(client) }
            main.post { connected = connected + name }
            Thread({
                try {
                    WebServer.serveStream(client.inputStream, client.outputStream) { web.handleApi(it, viaBluetooth = true) }
                } catch (_: IOException) {
                    // the phone went away
                } finally {
                    runCatching { client.close() }
                    synchronized(clients) { clients.remove(client) }
                    main.post { connected = connected - name }
                }
            }, "bt-api-client").apply { isDaemon = true }.start()
        }
    }

    companion object {
        private const val SERVICE_NAME = "MediaGimmix API"
        private const val KEY_AUTOSTART = "bt_autostart"
        /** Shared with the companion app (BluetoothLink.kt). */
        val SERVICE_UUID: UUID = UUID.fromString("b0d63ac8-81d4-4769-90ba-e4f52e0da7a8")
    }
}
