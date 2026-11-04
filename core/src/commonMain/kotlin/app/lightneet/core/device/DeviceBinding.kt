package app.lightneet.core.device

/**
 * A key that physically cannot leave this phone (Android Keystore / StrongBox,
 * later the iOS Secure Enclave).
 *
 * It is NOT a way to log in — only the password opens a vault. It exists for two reasons:
 *  1. A copy of the storage taken off the phone is useless: the header is sealed with a
 *     key that only the phone's secure hardware can use, so passwords cannot be brute-forced
 *     on a GPU farm.
 *  2. Destroying the key is a reliable erase. Flash memory keeps stale copies of
 *     "overwritten" pages, but a key deleted from secure hardware is gone.
 *
 * Lightneet rotates this key on every unlock attempt (see [app.lightneet.core.vault.Vault]),
 * which also defeats "copy the files, try 10 passwords, restore the files" attacks against
 * the attempt counter.
 */
interface DeviceBinding {
    /** Creates a fresh key and returns an opaque identifier for it. */
    fun createKey(): String

    fun seal(keyId: String, plaintext: ByteArray): ByteArray

    /** Returns null if the key no longer exists or the data was tampered with. */
    fun open(keyId: String, sealed: ByteArray): ByteArray?

    fun destroyKey(keyId: String)

    /** Human-readable description for the "security status" screen. */
    val protectionLevel: ProtectionLevel
}

enum class ProtectionLevel {
    /** Dedicated secure chip (StrongBox). Best. */
    STRONGBOX,

    /** Trusted Execution Environment. Good. */
    TEE,

    /** No hardware isolation (emulators, tests). */
    SOFTWARE,
}
