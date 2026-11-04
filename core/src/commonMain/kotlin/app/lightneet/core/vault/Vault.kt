package app.lightneet.core.vault

import app.lightneet.core.crypto.Crypto
import app.lightneet.core.crypto.opened
import app.lightneet.core.crypto.sealed
import app.lightneet.core.crypto.wipe
import app.lightneet.core.device.DeviceBinding
import app.lightneet.core.format.Header
import app.lightneet.core.format.HiddenReserve
import app.lightneet.core.format.KdfParams
import app.lightneet.core.format.PanicMode
import app.lightneet.core.format.SlotPayload
import app.lightneet.core.format.SlotType
import app.lightneet.core.storage.BlockPool
import app.lightneet.core.storage.HeaderStore
import app.lightneet.core.util.ad
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.FileSystem
import okio.Path
import kotlin.time.TimeSource

sealed interface UnlockResult {
    class Opened(val session: VaultSession) : UnlockResult

    /** [attemptsLeft] is null when there is no attempt limit. */
    class WrongPassword(val attemptsLeft: Int?) : UnlockResult

    /** The attempt limit was reached; everything has been erased. */
    data object Destroyed : UnlockResult

    data object NoVault : UnlockResult

    /** A header exists but its device key is gone (e.g. the phone's keystore was reset). */
    data object Unreadable : UnlockResult
}

enum class VaultState { NONE, READY, UNREADABLE }

class PasswordInUseException : IllegalArgumentException("This password is already used by another slot")

/**
 * Entry point to the storage on this phone. See docs/SPEC.md for the full design.
 *
 * Every unlock attempt — right or wrong — does the same work: bump the attempt counter,
 * re-seal the header under a *new* device key (destroying the old one), run Argon2id once,
 * and try all four slots. Timing therefore says nothing about which password was typed.
 */
class Vault(
    private val fs: FileSystem,
    private val dir: Path,
    private val crypto: Crypto,
    private val device: DeviceBinding,
    private val now: () -> Long,
) {
    private val lock = SynchronizedObject()
    private val headers = HeaderStore(fs, dir, device)
    private var keyId: String? = null
    private var header: Header? = null
    private var session: VaultSession? = null

    init {
        fs.createDirectories(dir)
    }

    val protectionLevel get() = device.protectionLevel

    fun state(): VaultState = synchronized(lock) {
        when {
            !headers.exists() -> VaultState.NONE
            headers.read() == null -> VaultState.UNREADABLE
            else -> VaultState.READY
        }
    }

    /**
     * Creates a new storage: fills [initialSegments] pool segments with noise, then writes a
     * header whose four slots are all noise except the main one.
     */
    fun create(
        password: ByteArray,
        kdf: KdfParams,
        reserve: HiddenReserve,
        initialSegments: Int,
        blockSize: Int = DEFAULT_BLOCK_SIZE,
        segmentBlocks: Int = DEFAULT_SEGMENT_BLOCKS,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): VaultSession = synchronized(lock) {
        require(initialSegments >= 1)
        require(blockSize >= 1024 && segmentBlocks >= 8)
        closeSession()
        headers.destroy(keyId)
        fs.deleteRecursively(dir / "pool", mustExist = false)

        BlockPool(fs, dir, blockSize, segmentBlocks, crypto).use { it.grow(initialSegments, onProgress) }

        val salt = crypto.randomBytes(Crypto.SALT_BYTES)
        val h = Header(
            blockSize, segmentBlocks, reserve, kdf, salt, failedAttempts = 0, maxAttempts = 0,
            slots = Array(Header.SLOT_COUNT) { crypto.randomBytes(Header.SLOT_BYTES) },
        )
        val kek = crypto.argon2id(password, salt, kdf.opsLimit, kdf.memLimitBytes)
        val payload = newVaultPayload(SlotType.MAIN, Region(reserve, hidden = false))
        h.slots[SLOT_MAIN] = sealSlot(kek, SLOT_MAIN, h, payload)
        kek.wipe()
        header = h
        persist()
        openSession(VaultKind.MAIN, SLOT_MAIN, payload, fresh = true)
    }

    fun unlock(password: ByteArray): UnlockResult = synchronized(lock) {
        closeSession()
        val loaded = headers.read() ?: return if (headers.exists()) UnlockResult.Unreadable else UnlockResult.NoVault
        keyId = loaded.keyId
        val h = loaded.header
        header = h

        if (h.maxAttempts > 0 && h.failedAttempts >= h.maxAttempts) {
            destroyEverythingLocked()
            return UnlockResult.Destroyed
        }
        // Count the attempt *before* doing anything else, so killing the app mid-check does not help.
        h.failedAttempts++
        persist()

        val kek = crypto.argon2id(password, h.salt, h.kdf.opsLimit, h.kdf.memLimitBytes)
        try {
            val found = findSlot(kek, h)
            if (found == null) {
                if (h.maxAttempts > 0 && h.failedAttempts >= h.maxAttempts) {
                    destroyEverythingLocked()
                    return UnlockResult.Destroyed
                }
                return UnlockResult.WrongPassword(if (h.maxAttempts > 0) h.maxAttempts - h.failedAttempts else null)
            }
            h.failedAttempts = 0
            val (slot, payload) = found
            return when (payload.type) {
                SlotType.MAIN -> {
                    persist()
                    UnlockResult.Opened(openSession(VaultKind.MAIN, slot, payload, fresh = false))
                }
                SlotType.HIDDEN -> {
                    persist()
                    UnlockResult.Opened(openSession(VaultKind.HIDDEN, slot, payload, fresh = false))
                }
                SlotType.PANIC -> UnlockResult.Opened(panic(kek, h, payload))
            }
        } finally {
            kek.wipe()
        }
    }

    /**
     * The panic password looks like a normal login from the outside. Behind the scenes it
     * crypto-erases what must disappear, rotating the device key so that no stale copy of the
     * header (e.g. left behind by flash wear-levelling) can ever be opened again.
     */
    private fun panic(kek: ByteArray, h: Header, payload: SlotPayload): VaultSession = when (payload.panicMode) {
        PanicMode.DESTROY_ALL -> {
            for (i in 0 until Header.SLOT_COUNT) h.slots[i] = crypto.randomBytes(Header.SLOT_BYTES)
            payload.masterKey.wipe()
            val fresh = newVaultPayload(SlotType.MAIN, Region(h.reserve, hidden = false))
            h.slots[SLOT_MAIN] = sealSlot(kek, SLOT_MAIN, h, fresh)
            persist()
            openSession(VaultKind.MAIN, SLOT_MAIN, fresh, fresh = true)
        }
        else -> {
            h.slots[SLOT_HIDDEN] = crypto.randomBytes(Header.SLOT_BYTES)
            persist()
            val main = SlotPayload(SlotType.MAIN, null, payload.masterKey, payload.superblockA, payload.superblockB)
            openSession(VaultKind.MAIN, SLOT_MAIN, main, fresh = false)
        }
    }

    // ------------------------------------------------------------------ called by VaultSession

    internal fun changePassword(s: VaultSession, newPassword: ByteArray) = synchronized(lock) {
        val h = requireHeader()
        val kek = deriveUnused(newPassword, h, exceptSlot = s.slotIndex)
        h.slots[s.slotIndex] = sealSlot(kek, s.slotIndex, h, s.payload)
        kek.wipe()
        persist()
    }

    internal fun setPanicPassword(main: VaultSession, password: ByteArray, mode: PanicMode) = synchronized(lock) {
        val h = requireHeader()
        val kek = deriveUnused(password, h, exceptSlot = SLOT_PANIC)
        val p = SlotPayload(SlotType.PANIC, mode, main.payload.masterKey, main.payload.superblockA, main.payload.superblockB)
        h.slots[SLOT_PANIC] = sealSlot(kek, SLOT_PANIC, h, p)
        kek.wipe()
        persist()
    }

    internal fun reserveAllowsHidden(): Boolean = synchronized(lock) { requireHeader().reserve != HiddenReserve.NONE }

    internal fun createHidden(password: ByteArray) = synchronized(lock) {
        val h = requireHeader()
        val kek = deriveUnused(password, h, exceptSlot = SLOT_HIDDEN)
        val region = Region(h.reserve, hidden = true)
        val payload = newVaultPayload(SlotType.HIDDEN, region)
        BlockPool(fs, dir, h.blockSize, h.segmentBlocks, crypto).use { pool ->
            val store = IndexStore(crypto, pool, BlockCodec(pool, crypto), payload.masterKey, payload.superblockA to payload.superblockB)
            store.commit(VaultIndex(), Allocator(pool, region, store.superblockIndices().toHashSet()))
            store.close()
        }
        h.slots[SLOT_HIDDEN] = sealSlot(kek, SLOT_HIDDEN, h, payload)
        kek.wipe()
        payload.masterKey.wipe()
        persist()
    }

    internal fun clearSlot(slot: Int) = synchronized(lock) {
        requireHeader().slots[slot] = crypto.randomBytes(Header.SLOT_BYTES)
        persist()
    }

    internal fun maxAttempts(): Int = synchronized(lock) { requireHeader().maxAttempts }

    internal fun setMaxAttempts(value: Int) = synchronized(lock) {
        require(value == 0 || value in 3..100) { "limit must be 0 (off) or 3..100" }
        requireHeader().maxAttempts = value
        persist()
    }

    internal fun sessionClosed(s: VaultSession) = synchronized(lock) {
        if (session === s) session = null
    }

    fun destroyEverything() = synchronized(lock) { destroyEverythingLocked() }

    fun lock() = synchronized(lock) { closeSession() }

    // ------------------------------------------------------------------ internals

    private fun destroyEverythingLocked() {
        closeSession()
        headers.destroy(keyId)
        fs.deleteRecursively(dir / "pool", mustExist = false)
        keyId = null
        header = null
    }

    private fun closeSession() {
        val s = session ?: return
        session = null
        s.close()
    }

    private fun requireHeader(): Header = header ?: throw VaultLockedException()

    private fun persist() {
        keyId = headers.writeRotating(requireHeader(), keyId)
    }

    private fun openSession(kind: VaultKind, slot: Int, payload: SlotPayload, fresh: Boolean): VaultSession {
        val h = requireHeader()
        val pool = BlockPool(fs, dir, h.blockSize, h.segmentBlocks, crypto)
        val s = VaultSession(this, kind, slot, payload, crypto, pool, Region(h.reserve, kind == VaultKind.HIDDEN), now)
        try {
            s.initialize(fresh)
        } catch (t: Throwable) {
            s.close()
            throw t
        }
        session = s
        return s
    }

    private fun newVaultPayload(type: SlotType, region: Region): SlotPayload {
        val (a, b) = region.first(2)
        return SlotPayload(type, null, crypto.randomBytes(Crypto.KEY_BYTES), a, b)
    }

    private fun slotAd(slot: Int, h: Header) = ad("slot", slot.toLong()) + h.salt

    private fun sealSlot(kek: ByteArray, slot: Int, h: Header, payload: SlotPayload): ByteArray {
        val nonce = crypto.randomBytes(Crypto.NONCE_BYTES)
        val plain = payload.encode()
        val ct = crypto.sealed(plain, slotAd(slot, h), nonce, kek)
        plain.wipe()
        return nonce + ct
    }

    /** Tries every slot, always all four, so the time taken does not depend on which one matches. */
    private fun findSlot(kek: ByteArray, h: Header): Pair<Int, SlotPayload>? {
        var found: Pair<Int, SlotPayload>? = null
        for (i in 0 until Header.SLOT_COUNT) {
            val bytes = h.slots[i]
            val plain = crypto.opened(bytes.copyOfRange(Crypto.NONCE_BYTES, bytes.size), slotAd(i, h), bytes.copyOfRange(0, Crypto.NONCE_BYTES), kek)
            val payload = plain?.let { SlotPayload.decode(it) }
            plain?.wipe()
            if (payload != null && payload.type == ROLE[i] && found == null) found = i to payload
        }
        return found
    }

    private fun deriveUnused(password: ByteArray, h: Header, exceptSlot: Int): ByteArray {
        val kek = crypto.argon2id(password, h.salt, h.kdf.opsLimit, h.kdf.memLimitBytes)
        val hit = findSlot(kek, h)
        if (hit != null && hit.first != exceptSlot) {
            hit.second.masterKey.wipe()
            kek.wipe()
            throw PasswordInUseException()
        }
        hit?.second?.masterKey?.wipe()
        return kek
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 256 * 1024
        const val DEFAULT_SEGMENT_BLOCKS = 256

        private const val SLOT_MAIN = Header.SLOT_MAIN
        private const val SLOT_PANIC = Header.SLOT_PANIC
        private const val SLOT_HIDDEN = Header.SLOT_HIDDEN
        internal const val panicSlot = Header.SLOT_PANIC
        internal const val hiddenSlot = Header.SLOT_HIDDEN

        private val ROLE = mapOf(SLOT_MAIN to SlotType.MAIN, SLOT_PANIC to SlotType.PANIC, SLOT_HIDDEN to SlotType.HIDDEN)

        /**
         * Picks Argon2id parameters so that one derivation takes about [targetMillis] on this
         * device with [memLimitBytes] of memory. Stronger phones get more iterations.
         */
        fun calibrate(crypto: Crypto, memLimitBytes: Long, targetMillis: Long = 1000): KdfParams {
            val salt = crypto.randomBytes(Crypto.SALT_BYTES)
            val mark = TimeSource.Monotonic.markNow()
            crypto.argon2id("calibration".encodeToByteArray(), salt, 1, memLimitBytes).wipe()
            val ms = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
            return KdfParams((targetMillis / ms).coerceIn(1, 10), memLimitBytes)
        }
    }
}
