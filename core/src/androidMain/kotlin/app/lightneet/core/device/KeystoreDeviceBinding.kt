package app.lightneet.core.device

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Device binding through the Android Keystore: AES-256-GCM keys that never leave secure
 * hardware. StrongBox (a dedicated chip) is preferred; the TEE is the fallback.
 */
class KeystoreDeviceBinding : DeviceBinding {
    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override var protectionLevel: ProtectionLevel = ProtectionLevel.TEE
        private set

    override fun createKey(): String {
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val alias = "lightneet-" + bytes.joinToString("") { "%02x".format(it) }
        val key = try {
            generate(alias, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (_: StrongBoxUnavailableException) {
            generate(alias, strongBox = false)
        }
        protectionLevel = levelOf(key)
        return alias
    }

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    private fun levelOf(key: SecretKey): ProtectionLevel = runCatching {
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> ProtectionLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> ProtectionLevel.TEE
                else -> ProtectionLevel.SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) ProtectionLevel.TEE else ProtectionLevel.SOFTWARE
        }
    }.getOrDefault(ProtectionLevel.TEE)

    override fun seal(keyId: String, plaintext: ByteArray): ByteArray {
        val key = keyStore.getKey(keyId, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    override fun open(keyId: String, sealed: ByteArray): ByteArray? = runCatching {
        val key = keyStore.getKey(keyId, null) as? SecretKey ?: return null
        val ivLength = sealed[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 1, ivLength))
        cipher.doFinal(sealed, 1 + ivLength, sealed.size - 1 - ivLength)
    }.getOrNull()

    override fun destroyKey(keyId: String) {
        runCatching { keyStore.deleteEntry(keyId) }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
