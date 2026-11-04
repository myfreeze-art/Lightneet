package app.lightneet.core.device

/**
 * Keys that physically cannot leave this phone (Android Keystore / StrongBox, later the iOS
 * Secure Enclave). They are NOT a way to log in — only the password opens a vault.
 *
 * Two kinds of keys are used:
 *
 *  - **Pepper key** (stable, HMAC). Mixed into the key derived from *every* password guess:
 *    `KEK = H(Argon2id(password), HMAC_device(Argon2id(password)))`. Every guess therefore needs
 *    the secure hardware of this very phone; a stolen copy of the storage — or even the header
 *    plaintext extracted with root — cannot be brute-forced on a GPU farm.
 *  - **Sealing key** (rotated on every unlock attempt, AES-GCM). Seals the header, so a copy of
 *    an older header (e.g. to reset the attempt counter) can no longer be opened.
 *
 * Both are created so that they only work while the phone is unlocked.
 */
interface DeviceBinding {
    /** Creates a fresh AES sealing key. Throws [DeviceUnavailableException] on failure. */
    fun createKey(): String

    /** Creates the stable HMAC key used to pepper password guesses. */
    fun createPepperKey(): String

    /** Throws [DeviceUnavailableException] if the hardware refuses right now. */
    fun seal(keyId: String, plaintext: ByteArray): ByteArray

    fun open(keyId: String, sealed: ByteArray): DeviceResult<ByteArray>

    /** HMAC-SHA256 of [input] under the pepper key. */
    fun pepper(keyId: String, input: ByteArray): DeviceResult<ByteArray>

    fun destroyKey(keyId: String)

    /** Deletes every Lightneet key except [keep] — removes keys orphaned by a crash mid-rotation. */
    fun destroyAllExcept(keep: Set<String>)

    /** Human-readable description for the "security status" screen. */
    val protectionLevel: ProtectionLevel
}

sealed interface DeviceResult<out T> {
    data class Ok<T>(val value: T) : DeviceResult<T>

    /** The key is permanently gone or the data was tampered with. Retrying will not help. */
    data object KeyGone : DeviceResult<Nothing>

    /** A temporary failure (keystore busy, phone locked, …). Retrying later may work. */
    data class Unavailable(val cause: Throwable?) : DeviceResult<Nothing>
}

class DeviceUnavailableException(cause: Throwable?) : IllegalStateException("secure hardware temporarily unavailable", cause)

class DeviceKeyGoneException : IllegalStateException("device key no longer exists")

enum class ProtectionLevel {
    /** Dedicated secure chip (StrongBox). Best. */
    STRONGBOX,

    /** Trusted Execution Environment. Good. */
    TEE,

    /** No hardware isolation (emulators, tests). */
    SOFTWARE,
}
