package app.lightneet.core.crypto

import com.goterl.lazysodium.Sodium
import com.sun.jna.NativeLong

/**
 * [Crypto] backed by libsodium through JNA. Shared verbatim by the JVM and Android targets
 * (they only differ in how the native library is loaded); iOS will get a cinterop version.
 */
internal class SodiumCrypto(private val sodium: Sodium) : Crypto {
    init {
        check(sodium.sodium_init() >= 0) { "libsodium failed to initialise" }
    }

    override fun fillRandom(target: ByteArray) {
        if (target.isNotEmpty()) sodium.randombytes_buf(target, target.size)
    }

    override fun argon2id(password: ByteArray, salt: ByteArray, opsLimit: Long, memLimitBytes: Long): ByteArray {
        require(salt.size == Crypto.SALT_BYTES)
        val out = ByteArray(Crypto.KEY_BYTES)
        val rc = sodium.crypto_pwhash(
            out, out.size.toLong(), password, password.size.toLong(), salt,
            opsLimit, NativeLong(memLimitBytes), ALG_ARGON2ID13,
        )
        check(rc == 0) { "Argon2id failed (not enough memory?)" }
        return out
    }

    override fun seal(out: ByteArray, plaintext: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray) {
        require(out.size == plaintext.size + Crypto.TAG_BYTES)
        require(nonce.size == Crypto.NONCE_BYTES && key.size == Crypto.KEY_BYTES)
        val rc = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
            out, null, plaintext, plaintext.size.toLong(), ad, ad.size.toLong(), null, nonce, key,
        )
        check(rc == 0) { "encryption failed" }
    }

    override fun open(out: ByteArray, ciphertext: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray): Boolean {
        require(out.size == ciphertext.size - Crypto.TAG_BYTES)
        require(nonce.size == Crypto.NONCE_BYTES && key.size == Crypto.KEY_BYTES)
        val rc = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
            out, null, null, ciphertext, ciphertext.size.toLong(), ad, ad.size.toLong(), nonce, key,
        )
        if (rc != 0) out.wipe()
        return rc == 0
    }

    override fun keyedHash(key: ByteArray, message: ByteArray): ByteArray {
        val out = ByteArray(32)
        check(sodium.crypto_generichash(out, out.size, message, message.size.toLong(), key, key.size) == 0)
        return out
    }

    private companion object {
        const val ALG_ARGON2ID13 = 2
    }
}
