package app.lightneet.core.crypto

/**
 * The only cryptographic primitives Lightneet uses. Implementations must be
 * thin wrappers around libsodium — nothing here is home-grown.
 *
 *  - Argon2id (crypto_pwhash) for turning a password into a key.
 *  - XChaCha20-Poly1305-IETF for authenticated encryption. The 192-bit nonce
 *    makes random nonces safe, so we never have to track counters on disk.
 *  - Keyed BLAKE2b (crypto_generichash) for deriving sub-keys.
 *
 * Long-lived keys (master keys, index keys, the KEK) are [SecretKey]s and per-file keys live
 * in a [KeyRing]: both are kept in libsodium's guarded memory rather than in JVM arrays.
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

    /** Moves [bytes] into guarded memory and wipes the array. */
    fun secretKey(bytes: ByteArray): SecretKey

    /** A fresh random key, generated directly in guarded memory. */
    fun randomSecretKey(): SecretKey

    /** Keyed BLAKE2b whose 32-byte output goes straight into guarded memory. */
    fun deriveKey(key: SecretKey, message: ByteArray): SecretKey

    fun seal(out: ByteArray, plaintext: ByteArray, ad: ByteArray, nonce: ByteArray, key: SecretKey)

    fun open(out: ByteArray, ciphertext: ByteArray, ad: ByteArray, nonce: ByteArray, key: SecretKey): Boolean

    /** An empty set of per-file keys in guarded memory. */
    fun newKeyRing(): KeyRing

    fun seal(out: ByteArray, plaintext: ByteArray, ad: ByteArray, nonce: ByteArray, ring: KeyRing, id: Long)

    fun open(out: ByteArray, ciphertext: ByteArray, ad: ByteArray, nonce: ByteArray, ring: KeyRing, id: Long): Boolean

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 24
        const val TAG_BYTES = 16
        const val SALT_BYTES = 16
    }
}

/**
 * A key the JVM never holds. It lives in memory from `sodium_malloc`: locked with `mlock` so it
 * is never swapped out, excluded from core dumps, fenced by guard pages, read-only after
 * creation, and zeroed by `sodium_free` on [close]. Code can use it, but not read it — except
 * [copyInto], which exists solely to serialise a master key into an encrypted slot.
 */
abstract class SecretKey : AutoCloseable {
    abstract val size: Int

    /** True after [close]; using a closed key throws instead of touching freed memory. */
    abstract val isClosed: Boolean

    /** Copies the key into [dest]. Only for sealing it into a slot; the caller wipes [dest]. */
    internal abstract fun copyInto(dest: ByteArray, offset: Int = 0)

    /** Idempotent. */
    abstract override fun close()
}

/**
 * Many 32-byte keys, addressed by id, in one region of guarded memory. A vault can hold
 * thousands of files; one guarded allocation per file key would cost several pages each, so
 * they share a single region that grows by doubling. Removing a key zeroes its slot.
 */
abstract class KeyRing : AutoCloseable {
    /** Moves [key] into the ring under [id] and wipes the array. */
    abstract fun put(id: Long, key: ByteArray)

    /** Generates a random key for [id] directly in guarded memory. */
    abstract fun putRandom(id: Long)

    /** Zeroes and forgets the key of [id], if any. */
    abstract fun remove(id: Long)

    abstract operator fun contains(id: Long): Boolean

    abstract val size: Int

    /** Copies one key out, only to serialise it into the encrypted index. The caller wipes it. */
    internal abstract fun copyOut(id: Long): ByteArray

    /** Idempotent. */
    abstract override fun close()
}

/** Overwrites the array with zeros. Best effort: the JVM may have made copies we cannot reach. */
fun ByteArray.wipe() = fill(0)

fun CharArray.wipe() = fill('\u0000')

/** Derives an independent 32-byte key for one purpose from a master key. */
fun Crypto.subkey(master: ByteArray, label: String): ByteArray =
    keyedHash(master, ("lightneet/v1/$label").encodeToByteArray())

fun Crypto.sealed(plaintext: ByteArray, ad: ByteArray, nonce: ByteArray, key: SecretKey): ByteArray =
    ByteArray(plaintext.size + Crypto.TAG_BYTES).also { seal(it, plaintext, ad, nonce, key) }

fun Crypto.opened(ciphertext: ByteArray, ad: ByteArray, nonce: ByteArray, key: SecretKey): ByteArray? {
    if (ciphertext.size < Crypto.TAG_BYTES) return null
    val out = ByteArray(ciphertext.size - Crypto.TAG_BYTES)
    return if (open(out, ciphertext, ad, nonce, key)) out else null
}

fun Crypto.sealed(plaintext: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray =
    ByteArray(plaintext.size + Crypto.TAG_BYTES).also { seal(it, plaintext, ad, nonce, key) }

fun Crypto.opened(ciphertext: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
    if (ciphertext.size < Crypto.TAG_BYTES) return null
    val out = ByteArray(ciphertext.size - Crypto.TAG_BYTES)
    return if (open(out, ciphertext, ad, nonce, key)) out else null
}
