package com.example.mediaplayer

import android.content.Context

/**
 * Stand-in used when the app is built without Jellyfin (-Pmg.noJellyfin or -Pmg.offline): the YubiKey lock only
 * protects the Jellyfin sign-in, so it and the yubikit library are left out. Same API as src/yubikey, does nothing.
 */
@Suppress("UNUSED_PARAMETER")
class YubiKeyUsb(context: Context, onUnlocked: () -> Unit) {
    val prompt: String? = null
    val message: String? = null

    fun refresh() = Unit
    fun register(name: String, slot: Int, program: Boolean, requireTouch: Boolean) = Unit
    fun cancel() = Unit
    fun stop() = Unit
}
