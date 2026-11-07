package com.example.mediaplayer

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.yubiotp.HmacSha1SlotConfiguration
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.YubiOtpSession
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
 * Keeps the Jellyfin access token, optionally locked behind YubiKeys.
 *
 * Without trusted keys the token is stored as before (app-private prefs). With
 * them, a random 256-bit vault key encrypts the token (AES-GCM, prefs "vault"),
 * and each trusted key holds its own copy of the vault key, wrapped with a key
 * derived (HKDF-SHA256) from that YubiKey's HMAC-SHA1 answer to a random
 * challenge. Only challenges and wrapped copies are stored: never the answers
 * or the vault key. Unlocking keeps the vault key in memory until the app
 * closes or "Lock now".
 *
 * All methods are synchronized; [keys] and [unlocked] are Compose state.
 */
object TokenVault {
    private const val INFO = "mediaplayer-vault-kek-v1"
    private lateinit var prefs: SharedPreferences
    private lateinit var plainPrefs: SharedPreferences
    private val random = SecureRandom()
    private val main = Handler(Looper.getMainLooper())

    var keys by mutableStateOf<List<TrustedKey>>(emptyList())
        private set
    var unlocked by mutableStateOf(false)
        private set

    private var vaultKey: ByteArray? = null
    /** A sign-in made while locked: kept in memory until a trusted key unlocks and it can be saved. */
    private var pendingToken: String? = null

    val isProtected: Boolean get() = keys.isNotEmpty()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
        plainPrefs = context.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)
        keys = parseKeys(prefs.getString("keys", "[]")!!)
    }

    // ---- the token ---------------------------------------------------------------------

    /** The saved token: plain, or decrypted when unlocked. Null when there is none or it's locked. */
    @Synchronized
    fun readToken(): String? {
        if (!isProtected) return plainPrefs.getString("token", null)
        val key = vaultKey ?: return null
        val enc = prefs.getString("token_enc", null) ?: return pendingToken
        return runCatching { String(decrypt(key, b64(enc))) }.getOrNull()
    }

    /** True when an encrypted token is waiting for a trusted key. */
    @Synchronized
    fun isLocked(): Boolean = isProtected && vaultKey == null && (prefs.contains("token_enc") || pendingToken != null)

    /** Saves the token. Returns false when it's protected and locked: then it's kept in memory until unlock. */
    @Synchronized
    fun writeToken(token: String): Boolean {
        if (!isProtected) {
            plainPrefs.edit().putString("token", token).apply()
            return true
        }
        val key = vaultKey
        if (key == null) {
            pendingToken = token
            return false
        }
        prefs.edit().putString("token_enc", s64(encrypt(key, token.toByteArray()))).apply()
        plainPrefs.edit().remove("token").apply()
        pendingToken = null
        return true
    }

    @Synchronized
    fun deleteToken() {
        plainPrefs.edit().remove("token").apply()
        prefs.edit().remove("token_enc").apply()
        pendingToken = null
    }

    /** Forgets the vault key (and so the token) until a trusted key is plugged in again. */
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
        pendingToken?.let { writeToken(it) }
        main.post { unlocked = true }
        return true
    }

    /**
     * Adds a key whose answer to [challenge] is [response]. The first key creates the vault and
     * moves the plain token into it; later ones need the vault unlocked. Returns an error or null.
     */
    @Synchronized
    fun addKey(name: String, serial: Int, slot: Int, challenge: ByteArray, response: ByteArray): String? {
        val key: ByteArray
        if (!isProtected) {
            key = ByteArray(32).also { random.nextBytes(it) }
            vaultKey = key
            val plain = plainPrefs.getString("token", null) ?: pendingToken
            if (plain != null) {
                prefs.edit().putString("token_enc", s64(encrypt(key, plain.toByteArray()))).apply()
                plainPrefs.edit().remove("token").apply()
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
     * De-trusts a key. Removing the last one turns protection off and stores the token plainly
     * again, which needs the vault unlocked (or no saved token). Returns an error or null.
     */
    @Synchronized
    fun removeKey(id: String): String? {
        val rest = keys.filter { it.id != id }
        if (rest.isEmpty()) {
            val hasToken = prefs.contains("token_enc")
            val key = vaultKey
            if (hasToken && key == null) return "Unlock with this key first, or sign out of Jellyfin"
            if (hasToken && key != null) {
                val token = String(decrypt(key, b64(prefs.getString("token_enc", null)!!)))
                plainPrefs.edit().putString("token", token).apply()
            }
            prefs.edit().remove("token_enc").apply()
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

/**
 * Talks to YubiKeys over USB: registers new trusted keys (optionally programming
 * a slot for HMAC-SHA1 challenge-response) and unlocks the vault when a trusted
 * key is plugged in. Android asks once per key for permission to use it.
 */
class YubiKeyUsb(context: Context, private val onUnlocked: () -> Unit) {

    sealed interface Op {
        /** [program]: write a new secret into [slot] (erasing it); else use the slot's existing setup. */
        data class Register(val name: String, val slot: Int, val program: Boolean, val requireTouch: Boolean) : Op
        data object Unlock : Op
    }

    private val manager = YubiKitManager(context)
    private val main = Handler(Looper.getMainLooper())
    private var discovering = false

    /** What the user should do now ("Plug in...", "Touch..."), or null when nothing is waiting. */
    var prompt by mutableStateOf<String?>(null)
        private set
    /** The result of the last operation. */
    var message by mutableStateOf<String?>(null)
        private set

    @Volatile private var op: Op? = null

    /** Watches USB while a vault is locked or an operation is waiting. Call when either changes. */
    fun refresh() {
        val wanted = op != null || TokenVault.isLocked()
        if (wanted && op == null) op = Op.Unlock
        if (wanted && !discovering) {
            discovering = true
            manager.startUsbDiscovery(UsbConfiguration()) { device -> handle(device) }
        } else if (!wanted && discovering) {
            discovering = false
            manager.stopUsbDiscovery()
        }
        prompt = when (op) {
            is Op.Register -> "Plug in the YubiKey to register"
            Op.Unlock -> if (TokenVault.isLocked()) "Jellyfin is locked: plug in a trusted YubiKey" else null
            null -> null
        }
    }

    fun register(name: String, slot: Int, program: Boolean, requireTouch: Boolean) {
        message = null
        op = Op.Register(name, slot, program, requireTouch)
        // A key already plugged in is picked up again by restarting discovery.
        if (discovering) {
            manager.stopUsbDiscovery()
            discovering = false
        }
        refresh()
    }

    fun cancel() {
        op = null
        refresh()
    }

    fun stop() {
        if (discovering) manager.stopUsbDiscovery()
        discovering = false
    }

    private fun handle(device: UsbYubiKeyDevice) {
        val current = op ?: return
        YubiOtpSession.create(device) { result ->
            val outcome = try {
                val session = result.value
                when (current) {
                    is Op.Register -> doRegister(session, current)
                    Op.Unlock -> doUnlock(session)
                }
            } catch (e: Exception) {
                "YubiKey error: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post {
                message = outcome
                if (current is Op.Register || TokenVault.unlocked) op = null
                if (TokenVault.unlocked && current == Op.Unlock) onUnlocked()
                refresh()
            }
        }
    }

    private fun doRegister(session: YubiOtpSession, r: Op.Register): String {
        val slot = if (r.slot == 1) Slot.ONE else Slot.TWO
        if (r.program) {
            val secret = TokenVault.newSecret()
            try {
                session.putConfiguration(slot, HmacSha1SlotConfiguration(secret).requireTouch(r.requireTouch), null, null)
            } catch (e: CommandException) {
                return "Couldn't program slot ${r.slot} (is it protected by an access code?)"
            } finally {
                secret.fill(0)
            }
        } else if (!session.configurationState.isConfigured(slot)) {
            return "Slot ${r.slot} is empty: choose \"Program the slot\" instead"
        }
        val serial = runCatching { session.serialNumber }.getOrDefault(-1)
        if (serial > 0 && TokenVault.keys.any { it.serial == serial && it.slot == r.slot }) {
            return "This YubiKey's slot ${r.slot} is already trusted"
        }
        val challenge = TokenVault.newChallenge()
        val response = try {
            session.calculateHmacSha1(slot, challenge, touchState())
        } catch (e: CommandException) {
            return "Slot ${r.slot} isn't set up for HMAC challenge-response"
        }
        main.post { prompt = null }
        val error = TokenVault.addKey(r.name, serial, r.slot, challenge, response)
        response.fill(0)
        return error ?: "Registered \"${r.name.ifBlank { "YubiKey" }}\""
    }

    private fun doUnlock(session: YubiOtpSession): String {
        if (!TokenVault.isLocked()) return "Already unlocked"
        val serial = runCatching { session.serialNumber }.getOrDefault(-1)
        // This key's entries first; entries without a serial could be any key.
        val candidates = TokenVault.keys.filter { serial > 0 && it.serial == serial } +
            TokenVault.keys.filter { it.serial <= 0 }
        if (candidates.isEmpty()) return "This YubiKey isn't trusted"
        for (entry in candidates) {
            val slot = if (entry.slot == 1) Slot.ONE else Slot.TWO
            val response = try {
                session.calculateHmacSha1(slot, entry.challenge, touchState())
            } catch (e: CommandException) {
                continue
            }
            val ok = TokenVault.tryUnlock(entry, response)
            response.fill(0)
            if (ok) return "Unlocked with \"${entry.name}\""
        }
        return "This YubiKey couldn't unlock it (was its slot reprogrammed?)"
    }

    /** Shows "Touch your YubiKey" when the key waits for a touch. */
    private fun touchState() = object : CommandState() {
        override fun onKeepAliveStatus(status: Byte) {
            if (status == STATUS_UPNEEDED) main.post { prompt = "Touch your YubiKey" }
        }
    }
}
