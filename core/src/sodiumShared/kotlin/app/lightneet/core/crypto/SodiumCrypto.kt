package app.lightneet.core.crypto

import com.goterl.lazysodium.Sodium
import com.sun.jna.NativeLong
import com.sun.jna.Pointer

/**
 * [Crypto] backed by libsodium through JNA. Shared verbatim by the JVM and Android targets
 * (they only differ in how the native library is loaded); iOS will get a cinterop version.
 *
 * [SodiumNative] must already be bound to the same library as [sodium].
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

    // ---------------------------------------------------------------- keys in guarded memory

    override fun secretKey(bytes: ByteArray): SecretKey {
        try {
            return GuardedKey.create(bytes.size) { it.write(0, bytes, 0, bytes.size) }
        } finally {
            bytes.wipe()
        }
    }

    override fun randomSecretKey(): SecretKey =
        GuardedKey.create(Crypto.KEY_BYTES) { SodiumNative.randombytes_buf(it, NativeLong(Crypto.KEY_BYTES.toLong())) }

    override fun deriveKey(key: SecretKey, message: ByteArray): SecretKey = (key as GuardedKey).use { k ->
        GuardedKey.create(Crypto.KEY_BYTES) { out ->
            check(SodiumNative.crypto_generichash(out, NativeLong(Crypto.KEY_BYTES.toLong()), message, message.size.toLong(), k, NativeLong(key.size.toLong())) == 0)
        }
    }

    override fun seal(out: ByteArray, plaintext: ByteArray, ad: ByteArray, nonce: ByteArray, key: SecretKey) {
        require(out.size == plaintext.size + Crypto.TAG_BYTES)
        require(nonce.size == Crypto.NONCE_BYTES && key.size == Crypto.KEY_BYTES)
        val rc = (key as GuardedKey).use { k ->
            SodiumNative.crypto_aead_xchacha20poly1305_ietf_encrypt(out, null, plaintext, plaintext.size.toLong(), ad, ad.size.toLong(), null, nonce, k)
        }
        check(rc == 0) { "encryption failed" }
    }

    override fun open(out: ByteArray, ciphertext: ByteArray, ad: ByteArray, nonce: ByteArray, key: SecretKey): Boolean {
        require(out.size == ciphertext.size - Crypto.TAG_BYTES)
        require(nonce.size == Crypto.NONCE_BYTES && key.size == Crypto.KEY_BYTES)
        val rc = (key as GuardedKey).use { k ->
            SodiumNative.crypto_aead_xchacha20poly1305_ietf_decrypt(out, null, null, ciphertext, ciphertext.size.toLong(), ad, ad.size.toLong(), nonce, k)
        }
        if (rc != 0) out.wipe()
        return rc == 0
    }

    override fun newKeyRing(): KeyRing = GuardedKeyRing()

    override fun seal(out: ByteArray, plaintext: ByteArray, ad: ByteArray, nonce: ByteArray, ring: KeyRing, id: Long) {
        require(out.size == plaintext.size + Crypto.TAG_BYTES)
        require(nonce.size == Crypto.NONCE_BYTES)
        val rc = (ring as GuardedKeyRing).use(id) { k ->
            SodiumNative.crypto_aead_xchacha20poly1305_ietf_encrypt(out, null, plaintext, plaintext.size.toLong(), ad, ad.size.toLong(), null, nonce, k)
        }
        check(rc == 0) { "encryption failed" }
    }

    override fun open(out: ByteArray, ciphertext: ByteArray, ad: ByteArray, nonce: ByteArray, ring: KeyRing, id: Long): Boolean {
        require(out.size == ciphertext.size - Crypto.TAG_BYTES)
        require(nonce.size == Crypto.NONCE_BYTES)
        val rc = (ring as GuardedKeyRing).use(id) { k ->
            SodiumNative.crypto_aead_xchacha20poly1305_ietf_decrypt(out, null, null, ciphertext, ciphertext.size.toLong(), ad, ad.size.toLong(), nonce, k)
        }
        if (rc != 0) out.wipe()
        return rc == 0
    }

    private companion object {
        const val ALG_ARGON2ID13 = 2
    }
}

/**
 * Per-file keys in one `sodium_malloc` region: read-only except for the instant a slot is
 * written or zeroed. All access holds the ring's monitor, so the region is never written,
 * reallocated or freed under a running operation.
 */
internal class GuardedKeyRing : KeyRing() {
    private val slots = HashMap<Long, Int>()
    private val free = ArrayDeque<Int>()
    private var capacity = 0
    private var region: Pointer? = null
    private var closed = false

    override val size: Int @Synchronized get() = slots.size

    @Synchronized
    fun <T> use(id: Long, block: (Pointer) -> T): T {
        check(!closed) { "key ring was closed" }
        val slot = slots[id] ?: throw IllegalStateException("no key for entry $id")
        return block(region!!.share(slot.toLong() * KEY))
    }

    @Synchronized
    override fun put(id: Long, key: ByteArray) {
        try {
            require(key.size == KEY)
            write(id) { it.write(0, key, 0, KEY) }
        } finally {
            key.wipe()
        }
    }

    @Synchronized
    override fun putRandom(id: Long) = write(id) { SodiumNative.randombytes_buf(it, NativeLong(KEY.toLong())) }

    @Synchronized
    override fun remove(id: Long) {
        if (closed) return
        val slot = slots.remove(id) ?: return
        val r = region!!
        writable(r) { r.setMemory(slot.toLong() * KEY, KEY.toLong(), 0) }
        free.addLast(slot)
    }

    @Synchronized
    override fun contains(id: Long) = !closed && id in slots

    @Synchronized
    override fun copyOut(id: Long): ByteArray = use(id) { it.getByteArray(0, KEY) }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        region?.let(SodiumNative::sodium_free) // zeroes every slot
        region = null
        slots.clear()
        free.clear()
    }

    private fun write(id: Long, fill: (Pointer) -> Unit) {
        check(!closed) { "key ring was closed" }
        val slot = slots[id] ?: (free.removeFirstOrNull() ?: nextSlot()).also { slots[id] = it }
        val r = region!!
        writable(r) { fill(r.share(slot.toLong() * KEY)) }
    }

    /** Returns a never-used slot, doubling the region (copy, then free the old one) when full. */
    private fun nextSlot(): Int {
        val used = slots.size + free.size
        if (used == capacity) {
            val grown = if (capacity == 0) INITIAL else capacity * 2
            val next = SodiumNative.sodium_malloc(NativeLong(grown.toLong() * KEY)) ?: throw OutOfMemoryError("sodium_malloc")
            next.setMemory(0, grown.toLong() * KEY, 0)
            // Copy natively (0 + old = old): the keys never pass through the JVM heap.
            region?.let { old ->
                SodiumNative.sodium_add(next, old, NativeLong(capacity.toLong() * KEY))
                SodiumNative.sodium_free(old)
            }
            check(SodiumNative.sodium_mprotect_readonly(next) == 0)
            region = next
            capacity = grown
        }
        return used
    }

    private inline fun writable(r: Pointer, block: () -> Unit) {
        check(SodiumNative.sodium_mprotect_readwrite(r) == 0)
        try {
            block()
        } finally {
            check(SodiumNative.sodium_mprotect_readonly(r) == 0)
        }
    }

    private companion object {
        const val KEY = Crypto.KEY_BYTES
        const val INITIAL = 64
    }
}

/**
 * A key in `sodium_malloc` memory, read-only after [create]. Every use holds the object's
 * monitor, so [close] can never free the memory under a running operation.
 */
internal class GuardedKey private constructor(private var ptr: Pointer?, override val size: Int) : SecretKey() {

    fun <T> use(block: (Pointer) -> T): T = synchronized(this) {
        block(ptr ?: throw IllegalStateException("key was closed"))
    }

    override val isClosed: Boolean get() = synchronized(this) { ptr == null }

    override fun copyInto(dest: ByteArray, offset: Int) = use { it.read(0, dest, offset, size) }

    override fun close() = synchronized(this) {
        val p = ptr ?: return
        ptr = null
        SodiumNative.sodium_free(p) // zeroes, munlocks and unmaps, guard pages included
    }

    companion object {
        fun create(size: Int, fill: (Pointer) -> Unit): GuardedKey {
            val p = SodiumNative.sodium_malloc(NativeLong(size.toLong())) ?: throw OutOfMemoryError("sodium_malloc($size)")
            try {
                fill(p)
                check(SodiumNative.sodium_mprotect_readonly(p) == 0) { "mprotect failed" }
            } catch (t: Throwable) {
                SodiumNative.sodium_free(p)
                throw t
            }
            return GuardedKey(p, size)
        }
    }
}
