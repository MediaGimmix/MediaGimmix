package com.example.mediaplayer.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Paired Bluetooth devices to pick the player from (pairing itself happens in Android's settings). */
object BluetoothDevices {
    class Device(val name: String, val address: String)

    /** Android 12+ asks for "Nearby devices" before paired devices can be listed or connected. */
    val PERMISSION: String =
        if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by allowed()
    fun paired(context: Context): List<Device> {
        if (!allowed(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.map { Device(it.name ?: it.address, it.address) }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }
}
