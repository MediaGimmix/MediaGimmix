package com.example.mediaplayer

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** A YubiKey trusted to unlock the vault. [serial] is -1 when the key doesn't report one. */
data class TrustedKey(
    val id: String,
    val name: String,
    val serial: Int,
    val slot: Int,
    val added: Long,
    internal val challenge: ByteArray,
    internal val wrapped: ByteArray,
)

/**
 * Keeps the netshare backends' sign-ins (Jellyfin's access token, Navidrome's
 * subsonic token and salt), optionally locked behind YubiKeys. Each backend has
 * its own [scope]: "jellyfin" and "navidrome".
 *
 * Without trusted keys a scope's secret is stored in that backend's own
 * app-private prefs (key "token"). With them, a random 256-bit vault key
 * encrypts every scope's secret (AES-GCM, prefs "vault"), and each trusted key
 * holds its own copy of the vault key, wrapped with a key derived (HKDF-SHA256)
 * from that YubiKey's HMAC-SHA1 answer to a random challenge. Only challenges
 * and wrapped copies are stored: never the answers or the vault key. Unlocking
 * keeps the vault key in memory until the app closes or "Lock now".
 *
 * All methods are synchronized; [keys] and [unlocked] are Compose state.
 */
object TokenVault {
    private const val INFO = "mediaplayer-vault-kek-v1"

    /** The backend scopes that share the one vault key. */
    const val SCOPE_JELLYFIN = "jellyfin"
    const val SCOPE_NAVIDROME = "navidrome"
    const val SCOPE_MSTREAM = "mstream"
    private val SCOPES = listOf(SCOPE_JELLYFIN, SCOPE_NAVIDROME, SCOPE_MSTREAM)

    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    private val random = SecureRandom()
    private val main = Handler(Looper.getMainLooper())

    var keys by mutableStateOf<List<TrustedKey>>(emptyList())
        private set
    var unlocked by mutableStateOf(false)
        private set

    private var vaultKey: ByteArray? = null
    /** Sign-ins made while locked: kept in memory, per scope, until a trusted key unlocks. */
    private val pending = HashMap<String, String>()

    val isProtected: Boolean get() = keys.isNotEmpty()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        ctx = context.applicationContext
        prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
        // The vault started life with only the Jellyfin token under "token_enc"; move it to its scope key.
        if (prefs.contains("token_enc") && !prefs.contains(encKey(SCOPE_JELLYFIN))) {
            prefs.edit().putString(encKey(SCOPE_JELLYFIN), prefs.getString("token_enc", null)).remove("token_enc").apply()
        }
        keys = parseKeys(prefs.getString("keys", "[]")!!)
    }

    private fun encKey(scope: String) = "token_enc_$scope"

    private fun plain(scope: String): SharedPreferences = ctx.getSharedPreferences(scope, Context.MODE_PRIVATE)

    // ---- the secrets -------------------------------------------------------------

    /** [scope]'s saved secret: plain, or decrypted when unlocked. Null when there is none or it's locked. */
    @Synchronized
    fun readToken(scope: String): String? {
        if (!isProtected) return plain(scope).getString("token", null)
        val key = vaultKey ?: return pending[scope]
        val enc = prefs.getString(encKey(scope), null) ?: return pending[scope]
        return runCatching { String(decrypt(key, b64(enc))) }.getOrNull()
    }

    /** True when a protected secret is waiting for a trusted key. */
    @Synchronized
    fun isLocked(): Boolean =
        isProtected && vaultKey == null && (SCOPES.any { prefs.contains(encKey(it)) } || pending.isNotEmpty())

    /** Saves [scope]'s secret. Returns false when it's protected and locked: then it's kept in memory until unlock. */
    @Synchronized
    fun writeToken(token: String, scope: String): Boolean {
        if (!isProtected) {
            plain(scope).edit().putString("token", token).apply()
            return true
        }
        val key = vaultKey
        if (key == null) {
            pending[scope] = token
            return false
        }
        prefs.edit().putString(encKey(scope), s64(encrypt(key, token.toByteArray()))).apply()
        plain(scope).edit().remove("token").apply()
        pending.remove(scope)
        return true
    }

    @Synchronized
    fun deleteToken(scope: String) {
        plain(scope).edit().remove("token").apply()
        prefs.edit().remove(encKey(scope)).apply()
        pending.remove(scope)
    }

    /** Forgets the vault key (and so every secret) until a trusted key is plugged in again. */
    @Synchronized
    fun lock() {
        vaultKey?.fill(0)
        vaultKey = null
        main.post { unlocked = false }
    }

    // ---- keys -------------------------------------------------------------------------

    /** Tries [response] (the YubiKey's answer to [entry]'s challenge). On success the vault is unlocked. */
    @Synchronized
    fun tryUnlock(entry: TrustedKey, response: ByteArray): Boolean {
        val kek = kek(response, entry.challenge)
        val key = runCatching { decrypt(kek, entry.wrapped) }.getOrNull() ?: return false
        vaultKey = key
        for ((scope, token) in pending.toList()) writeToken(token, scope)
        pending.clear()
        main.post { unlocked = true }
        return true
    }

    /**
     * Adds a key whose answer to [challenge] is [response]. The first key creates the vault and
     * moves the plain secrets into it; later ones need the vault unlocked. Returns an error or null.
     */
    @Synchronized
    fun addKey(name: String, serial: Int, slot: Int, challenge: ByteArray, response: ByteArray): String? {
        val key: ByteArray
        if (!isProtected) {
            key = ByteArray(32).also { random.nextBytes(it) }
            vaultKey = key
            for (scope in SCOPES) {
                val plainToken = plain(scope).getString("token", null) ?: pending[scope] ?: continue
                prefs.edit().putString(encKey(scope), s64(encrypt(key, plainToken.toByteArray()))).apply()
                plain(scope).edit().remove("token").apply()
                pending.remove(scope)
            }
        } else {
            key = vaultKey ?: return "Unlock with a trusted key first"
        }
        val entry = TrustedKey(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "YubiKey" + if (serial > 0) " $serial" else "" },
            serial = serial,
            slot = slot,
            added = System.currentTimeMillis(),
            challenge = challenge,
            wrapped = encrypt(kek(response, challenge), key),
        )
        saveKeys(keys + entry)
        main.post { unlocked = true }
        return null
    }

    /**
     * De-trusts a key. Removing the last one turns protection off and stores the secrets plainly
     * again, which needs the vault unlocked (or no saved secrets). Returns an error or null.
     */
    @Synchronized
    fun removeKey(id: String): String? {
        val rest = keys.filter { it.id != id }
        if (rest.isEmpty()) {
            val key = vaultKey
            for (scope in SCOPES) {
                if (!prefs.contains(encKey(scope))) continue
                if (key == null) return "Unlock with a trusted key first, or sign out of the netshare servers"
                val token = String(decrypt(key, b64(prefs.getString(encKey(scope), null)!!)))
                plain(scope).edit().putString("token", token).apply()
                prefs.edit().remove(encKey(scope)).apply()
            }
            vaultKey?.fill(0)
            vaultKey = null
            main.post { unlocked = false }
        }
        saveKeys(rest)
        return null
    }

    @Synchronized
    fun rename(id: String, name: String) {
        saveKeys(keys.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })
    }

    private fun saveKeys(list: List<TrustedKey>) {
        val arr = JSONArray()
        for (k in list) {
            arr.put(
                JSONObject().put("id", k.id).put("name", k.name).put("serial", k.serial).put("slot", k.slot)
                    .put("added", k.added).put("challenge", s64(k.challenge)).put("wrapped", s64(k.wrapped)),
            )
        }
        prefs.edit().putString("keys", arr.toString()).apply()
        if (Looper.myLooper() == Looper.getMainLooper()) keys = list else main.post { keys = list }
    }

    private fun parseKeys(json: String): List<TrustedKey> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TrustedKey(
                o.getString("id"), o.getString("name"), o.optInt("serial", -1), o.getInt("slot"), o.optLong("added"),
                b64(o.getString("challenge")), b64(o.getString("wrapped")),
            )
        }
    }.getOrDefault(emptyList())

    fun newChallenge(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun newSecret(): ByteArray = ByteArray(20).also { random.nextBytes(it) }

    // ---- crypto -----------------------------------------------------------------------

    /** HKDF-SHA256(ikm = response, salt = challenge) -> 32 bytes. */
    private fun kek(response: ByteArray, challenge: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(challenge, "HmacSHA256")) }.doFinal(response)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }
        return expand.doFinal(INFO.toByteArray() + byteArrayOf(1))
    }

    private fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    private fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    private fun s64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
