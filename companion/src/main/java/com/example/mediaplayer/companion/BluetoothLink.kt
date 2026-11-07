package com.example.mediaplayer.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The player's API over classic Bluetooth (RFCOMM) to a paired player with Bluetooth control on.
 * Speaks the same HTTP/1.1 text as over Wi-Fi on one link that stays open between requests;
 * requests go one at a time. A dropped link is reopened once per request.
 */
object BluetoothLink {
    /** Must match the player (BluetoothControl.kt). */
    private val SERVICE_UUID: UUID = UUID.fromString("b0d63ac8-81d4-4769-90ba-e4f52e0da7a8")
    private const val TIMEOUT_MS = 15_000L

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var address: String? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bt-watchdog").apply { isDaemon = true } }

    /** Sends one request ([path] starts with "/api/") and reads the reply. Blocking. */
    @Synchronized
    fun exchange(deviceAddress: String, method: String, path: String, key: String): RawResponse {
        return try {
            once(deviceAddress, method, path, key)
        } catch (e: IOException) {
            close()
            once(deviceAddress, method, path, key) // the link may have dropped since the last request
        }
    }

    @Synchronized
    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        address = null
    }

    private fun once(deviceAddress: String, method: String, path: String, key: String): RawResponse {
        val s = connect(deviceAddress)
        // Bluetooth sockets have no read timeout: close the link if the player stops answering.
        val timer = watchdog.schedule({ runCatching { s.close() } }, TIMEOUT_MS, TimeUnit.MILLISECONDS)
        try {
            val head = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: player\r\n")
                if (key.isNotEmpty()) append("X-Api-Key: ").append(key).append("\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: keep-alive\r\n\r\n")
            }
            s.outputStream.write(head.toByteArray(Charsets.ISO_8859_1))
            s.outputStream.flush()
            return read(input!!)
        } finally {
            timer.cancel(false)
        }
    }

    @SuppressLint("MissingPermission") // asked before Bluetooth can be chosen in Settings
    private fun connect(deviceAddress: String): BluetoothSocket {
        val current = socket
        if (current != null && current.isConnected && address == deviceAddress) return current
        close()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("No Bluetooth on this phone")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val s = adapter.getRemoteDevice(deviceAddress).createRfcommSocketToServiceRecord(SERVICE_UUID)
        runCatching { adapter.cancelDiscovery() }
        try {
            s.connect()
        } catch (e: IOException) {
            runCatching { s.close() }
            throw IOException("Can't reach the player over Bluetooth. Is Bluetooth control switched on?")
        }
        socket = s
        input = BufferedInputStream(s.inputStream)
        address = deviceAddress
        return s
    }

    private fun read(input: InputStream): RawResponse {
        val status = readLine(input) ?: throw IOException("The player closed the link")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad reply: $status")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toLongOrNull()
        val body = if (length != null) {
            val out = ByteArray(length.toInt())
            var read = 0
            while (read < out.size) {
                val n = input.read(out, read, out.size - read)
                if (n < 0) throw IOException("The player closed the link")
                read += n
            }
            out
        } else {
            // No length: the body runs to the end of the link (the player closes it afterwards).
            val bytes = input.readBytes()
            close()
            bytes
        }
        if (headers["connection"].equals("close", ignoreCase = true)) close()
        return RawResponse(code, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 16 * 1024) throw IOException("Reply line too long")
        }
        return out.toString("ISO-8859-1")
    }
}
