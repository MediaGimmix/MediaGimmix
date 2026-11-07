package com.example.mediaplayer

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The two network switches (Settings → Network), saved in "settings":
 *
 * - [internet] ("net_internet"): requests to the open internet (artist pictures,
 *   a Jellyfin server on a public address). Off = every outgoing request to a
 *   non-local address fails before it is sent. Servers on the local network
 *   (home Jellyfin) are not affected.
 * - [localApi] ("net_local_api"): incoming requests from the local network to
 *   the web control API (companion apps, scripts). Off = the server can't be
 *   switched on and stops if it is running.
 * - [bluetoothApi] ("net_bluetooth_api"): the same for API requests over Bluetooth ([BluetoothControl]).
 */
object NetPolicy {
    private const val KEY_INTERNET = "net_internet"
    private const val KEY_LOCAL_API = "net_local_api"
    private const val KEY_BLUETOOTH_API = "net_bluetooth_api"

    var internet by mutableStateOf(true)
        private set
    var localApi by mutableStateOf(true)
        private set
    /** Incoming API requests over Bluetooth ([BluetoothControl]). Separate from [localApi]. */
    var bluetoothApi by mutableStateOf(true)
        private set

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        val p = prefs(context)
        internet = p.getBoolean(KEY_INTERNET, true)
        localApi = p.getBoolean(KEY_LOCAL_API, true)
        bluetoothApi = p.getBoolean(KEY_BLUETOOTH_API, true)
        loaded = true
    }

    fun setInternet(context: Context, on: Boolean) {
        internet = on
        prefs(context).edit().putBoolean(KEY_INTERNET, on).apply()
    }

    fun setLocalApi(context: Context, on: Boolean) {
        localApi = on
        prefs(context).edit().putBoolean(KEY_LOCAL_API, on).apply()
    }

    fun setBluetoothApi(context: Context, on: Boolean) {
        bluetoothApi = on
        prefs(context).edit().putBoolean(KEY_BLUETOOTH_API, on).apply()
    }

    class Blocked(host: String) : IOException("Internet access is off (Settings → Network): $host")

    /** Throws [Blocked] when [url] points at the open internet and internet access is off. Blocking (may resolve DNS). */
    fun check(url: String) = checkHost(Uri.parse(url).host)

    fun check(uri: Uri) {
        if (uri.scheme == "http" || uri.scheme == "https") checkHost(uri.host)
    }

    private fun checkHost(host: String?) {
        if (internet) return
        if (host.isNullOrEmpty() || !isLocal(host)) throw Blocked(host ?: "?")
    }

    /** True when every address of [host] is on this device or the local network. */
    fun isLocal(host: String): Boolean {
        val h = host.trim('[', ']')
        if (h.equals("localhost", ignoreCase = true)) return true
        val addresses = runCatching { InetAddress.getAllByName(h) }.getOrNull() ?: return false
        return addresses.isNotEmpty() && addresses.all { isLocal(it) }
    }

    fun isLocal(a: InetAddress): Boolean = when {
        a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress -> true
        a is Inet4Address -> {
            val b = a.address
            // 100.64.0.0/10 (carrier-grade NAT, also VPNs like Tailscale)
            (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
        }
        a is Inet6Address -> (a.address[0].toInt() and 0xFE) == 0xFC // fc00::/7 unique local
        else -> false
    }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
