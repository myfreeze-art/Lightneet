package app.lightneet

import app.lightneet.core.device.DeviceBinding
import app.lightneet.core.device.ProtectionLevel
import app.lightneet.data.VaultHolder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Robolectric has no AndroidKeyStore, so tests use AES-GCM keys kept in memory.
 * Everything else — libsodium via JNA, storage, ViewModel, Compose UI — is the real thing.
 */
class TestLightneetApp : LightneetApp() {
    override fun createHolder() = VaultHolder(this, device = InMemoryDeviceBinding())
}

class InMemoryDeviceBinding : DeviceBinding {
    private val keys = HashMap<String, ByteArray>()
    private val random = SecureRandom()
    private var n = 0

    override fun createKey(): String = "test-${n++}".also { keys[it] = ByteArray(32).apply(random::nextBytes) }

    override fun seal(keyId: String, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).apply(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.getValue(keyId), "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plaintext)
    }

    override fun open(keyId: String, sealed: ByteArray): ByteArray? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys[keyId] ?: return null, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        c.doFinal(sealed, 12, sealed.size - 12)
    }.getOrNull()

    override fun destroyKey(keyId: String) {
        keys.remove(keyId)
    }

    override val protectionLevel = ProtectionLevel.STRONGBOX
}
