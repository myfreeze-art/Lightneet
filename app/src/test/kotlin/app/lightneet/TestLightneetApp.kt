package app.lightneet

import app.lightneet.core.device.DeviceBinding
import app.lightneet.core.device.DeviceResult
import app.lightneet.core.device.ProtectionLevel
import app.lightneet.data.VaultHolder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Robolectric has no AndroidKeyStore, so tests use keys kept in memory. Everything else —
 * libsodium via JNA, storage, ViewModel, Compose UI — is the real thing. Ending the process on
 * lock is disabled: it would end the test JVM.
 */
class TestLightneetApp : LightneetApp() {
    override fun isMainProcess() = true
    override fun createHolder() = VaultHolder(this, device = InMemoryDeviceBinding())
    override fun endProcess() = Unit
}

class InMemoryDeviceBinding : DeviceBinding {
    private val keys = HashMap<String, ByteArray>()
    private val random = SecureRandom()
    private var n = 0

    private fun newKey(prefix: String) = "$prefix${n++}".also { keys[it] = ByteArray(32).apply(random::nextBytes) }

    override fun createKey(): String = newKey("lightneet-s-")

    override fun createPepperKey(): String = newKey("lightneet-p-")

    override fun seal(keyId: String, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).apply(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.getValue(keyId), "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plaintext)
    }

    override fun open(keyId: String, sealed: ByteArray): DeviceResult<ByteArray> {
        val key = keys[keyId] ?: return DeviceResult.KeyGone
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
            DeviceResult.Ok(c.doFinal(sealed, 12, sealed.size - 12))
        }.getOrDefault(DeviceResult.KeyGone)
    }

    override fun pepper(keyId: String, input: ByteArray): DeviceResult<ByteArray> {
        val key = keys[keyId] ?: return DeviceResult.KeyGone
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return DeviceResult.Ok(mac.doFinal(input))
    }

    override fun destroyKey(keyId: String) {
        keys.remove(keyId)
    }

    override fun destroyAllExcept(keep: Set<String>) {
        keys.keys.filter { it !in keep }.forEach(::destroyKey)
    }

    override val protectionLevel = ProtectionLevel.STRONGBOX
}
