package app.lightneet.core

import app.lightneet.core.crypto.Crypto
import app.lightneet.core.crypto.jvmCrypto
import app.lightneet.core.crypto.opened
import app.lightneet.core.crypto.sealed
import app.lightneet.core.device.DeviceBinding
import app.lightneet.core.device.ProtectionLevel
import app.lightneet.core.format.HiddenReserve
import app.lightneet.core.format.KdfParams
import app.lightneet.core.vault.Vault
import app.lightneet.core.vault.VaultSession
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.nio.file.Files

val crypto: Crypto by lazy { jvmCrypto() }

/** Stand-in for the Android Keystore: keys live in a map and can be truly destroyed. */
class FakeDeviceBinding : DeviceBinding {
    val keys = HashMap<String, ByteArray>()
    private var counter = 0

    override fun createKey(): String = "key-${counter++}".also { keys[it] = crypto.randomBytes(32) }

    override fun seal(keyId: String, plaintext: ByteArray): ByteArray {
        val nonce = crypto.randomBytes(Crypto.NONCE_BYTES)
        return nonce + crypto.sealed(plaintext, keyId.encodeToByteArray(), nonce, keys.getValue(keyId))
    }

    override fun open(keyId: String, sealed: ByteArray): ByteArray? {
        val key = keys[keyId] ?: return null
        return crypto.opened(sealed.copyOfRange(Crypto.NONCE_BYTES, sealed.size), keyId.encodeToByteArray(), sealed.copyOfRange(0, Crypto.NONCE_BYTES), key)
    }

    override fun destroyKey(keyId: String) {
        keys.remove(keyId)?.fill(0)
    }

    override val protectionLevel = ProtectionLevel.SOFTWARE
}

/** A vault on a real temp directory with tiny blocks and cheap Argon2 so tests are fast. */
class Fixture(
    val dir: Path = Files.createTempDirectory("lightneet").toOkioPath(),
    val device: FakeDeviceBinding = FakeDeviceBinding(),
) {
    val fs = FileSystem.SYSTEM
    fun vault() = Vault(fs, dir, crypto, device) { 1_700_000_000_000L }

    fun create(
        password: String = MAIN_PW,
        reserve: HiddenReserve = HiddenReserve.QUARTER,
        segments: Int = 2,
    ): Pair<Vault, VaultSession> {
        val v = vault()
        return v to v.create(password.encodeToByteArray(), FAST_KDF, reserve, segments, BLOCK, SEGMENT_BLOCKS)
    }

    companion object {
        const val BLOCK = 4096
        const val SEGMENT_BLOCKS = 16
        const val MAIN_PW = "correct horse battery staple"
        val FAST_KDF = KdfParams(opsLimit = 1, memLimitBytes = 1024 * 1024)
    }
}

fun bytes(n: Int, seed: Int = n): ByteArray = java.util.Random(seed.toLong()).let { r -> ByteArray(n).also(r::nextBytes) }

fun VaultSession.import(name: String, data: ByteArray, parent: Long = 0) =
    importFile(parent, name, "application/octet-stream", Buffer().write(data))

fun String.pw() = encodeToByteArray()
