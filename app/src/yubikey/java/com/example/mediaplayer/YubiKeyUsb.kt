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
            Op.Unlock -> if (TokenVault.isLocked()) "Netshare is locked: plug in a trusted YubiKey" else null
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
