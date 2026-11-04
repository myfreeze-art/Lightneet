package app.lightneet.core.crypto

/**
 * The only cryptographic primitives Lightneet uses. Implementations must be
 * thin wrappers around libsodium — nothing here is home-grown.
 *
 *  - Argon2id (crypto_pwhash) for turning a password into a key.
 *  - XChaCha20-Poly1305-IETF for authenticated encryption. The 192-bit nonce
 *    makes random nonces safe, so we never have to track counters on disk.
 *  - Keyed BLAKE2b (crypto_generichash) for deriving sub-keys.
 */
interface Crypto {
    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { fillRandom(it) }

    fun fillRandom(target: ByteArray)

    /** Argon2id13 with a 32-byte output. Throws if libsodium refuses (e.g. out of memory). */
    fun argon2id(password: ByteArray, salt: ByteArray, opsLimit: Long, memLimitBytes: Long): ByteArray

    /**
     * Encrypts [plaintext] into [out], which must be exactly `plaintext.size + TAG_BYTES` long.
     */
    fun seal(out: ByteArray, plaintext: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray)

    /**
     * Decrypts [ciphertext] into [out] (`ciphertext.size - TAG_BYTES` long).
     * Returns false if authentication fails; [out] contents are then undefined and get wiped.
     */
    fun open(out: ByteArray, ciphertext: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray): Boolean

    /** Keyed BLAKE2b with a 32-byte output. */
    fun keyedHash(key: ByteArray, message: ByteArray): ByteArray

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 24
        const val TAG_BYTES = 16
        const val SALT_BYTES = 16
    }
}

/** Overwrites the array with zeros. Best effort: the JVM may have made copies we cannot reach. */
fun ByteArray.wipe() = fill(0)

fun CharArray.wipe() = fill('\u0000')

/** Derives an independent 32-byte key for one purpose from a master key. */
fun Crypto.subkey(master: ByteArray, label: String): ByteArray =
    keyedHash(master, ("lightneet/v1/$label").encodeToByteArray())

fun Crypto.sealed(plaintext: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray =
    ByteArray(plaintext.size + Crypto.TAG_BYTES).also { seal(it, plaintext, ad, nonce, key) }

fun Crypto.opened(ciphertext: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
    if (ciphertext.size < Crypto.TAG_BYTES) return null
    val out = ByteArray(ciphertext.size - Crypto.TAG_BYTES)
    return if (open(out, ciphertext, ad, nonce, key)) out else null
}
