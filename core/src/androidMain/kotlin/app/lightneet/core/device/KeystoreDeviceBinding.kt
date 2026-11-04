package app.lightneet.core.device

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Device binding through the Android Keystore. Keys never leave secure hardware: StrongBox (a
 * dedicated chip) is preferred, the TEE is the fallback.
 *
 * Every key is created with `setUnlockedDeviceRequired(true)`: while the phone's screen lock is
 * engaged the keys cannot be used at all, which shuts out tools that attack a seized, locked
 * phone. (Lightneet itself is only ever used on an unlocked phone and locks on screen-off.)
 */
class KeystoreDeviceBinding : DeviceBinding {
    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
    private val random = SecureRandom()

    override var protectionLevel: ProtectionLevel = ProtectionLevel.TEE
        private set

    override fun createKey(): String = create(SEAL_PREFIX, KeyProperties.KEY_ALGORITHM_AES) { alias ->
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
    }

    override fun createPepperKey(): String = create(PEPPER_PREFIX, KeyProperties.KEY_ALGORITHM_HMAC_SHA256) { alias ->
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setKeySize(256)
    }

    /**
     * Tries StrongBox + "unlocked device required" first, then relaxes one property at a time.
     * Some devices reject one combination or the other; refusing to work at all would be worse.
     */
    private fun create(prefix: String, algorithm: String, spec: (String) -> KeyGenParameterSpec.Builder): String {
        val alias = prefix + ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val attempts = listOf(true to true, false to true, true to false, false to false)
        var last: Throwable? = null
        for ((strongBox, unlockedOnly) in attempts) {
            try {
                val builder = spec(alias)
                if (strongBox) builder.setIsStrongBoxBacked(true)
                if (unlockedOnly) builder.setUnlockedDeviceRequired(true)
                val key = KeyGenerator.getInstance(algorithm, PROVIDER).run {
                    init(builder.build())
                    generateKey()
                }
                protectionLevel = levelOf(key)
                return alias
            } catch (e: StrongBoxUnavailableException) {
                last = e
            } catch (e: Exception) {
                last = e
                runCatching { keyStore.deleteEntry(alias) }
            }
        }
        throw DeviceUnavailableException(last)
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

    override fun seal(keyId: String, plaintext: ByteArray): ByteArray = try {
        val key = keyStore.getKey(keyId, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plaintext)
    } catch (e: Exception) {
        throw DeviceUnavailableException(e)
    }

    override fun open(keyId: String, sealed: ByteArray): DeviceResult<ByteArray> = classify(keyId) {
        val key = keyStore.getKey(keyId, null) as SecretKey
        val ivLength = sealed[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 1, ivLength))
        cipher.doFinal(sealed, 1 + ivLength, sealed.size - 1 - ivLength)
    }

    override fun pepper(keyId: String, input: ByteArray): DeviceResult<ByteArray> = classify(keyId) {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keyStore.getKey(keyId, null) as SecretKey)
        mac.doFinal(input)
    }

    /**
     * Separates "this key is gone forever" from "the keystore said no right now". Only the first
     * may ever lead the UI to offer erasing the storage.
     */
    private fun <T> classify(keyId: String, op: () -> T): DeviceResult<T> {
        val exists = try {
            keyStore.containsAlias(keyId)
        } catch (e: Exception) {
            return DeviceResult.Unavailable(e)
        }
        if (!exists) return DeviceResult.KeyGone
        return try {
            DeviceResult.Ok(op())
        } catch (_: KeyPermanentlyInvalidatedException) {
            DeviceResult.KeyGone
        } catch (_: AEADBadTagException) {
            DeviceResult.KeyGone // the sealed header was tampered with or corrupted
        } catch (e: Exception) {
            DeviceResult.Unavailable(e)
        }
    }

    override fun destroyKey(keyId: String) {
        runCatching { keyStore.deleteEntry(keyId) }
    }

    override fun destroyAllExcept(keep: Set<String>) {
        runCatching {
            keyStore.aliases().toList()
                .filter { it.startsWith(ALL_PREFIX) && it !in keep }
                .forEach { destroyKey(it) }
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val SEAL_PREFIX = "lightneet-s-"
        const val PEPPER_PREFIX = "lightneet-p-"
        const val ALL_PREFIX = "lightneet-"
    }
}
