package app.lightneet.data

import android.app.ActivityManager
import android.content.Context
import app.lightneet.core.crypto.Crypto
import app.lightneet.core.crypto.androidCrypto
import app.lightneet.core.device.DeviceBinding
import app.lightneet.core.device.KeystoreDeviceBinding
import app.lightneet.core.vault.Vault
import app.lightneet.core.vault.VaultSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Process-wide owner of the [Vault] and the currently open [VaultSession].
 *
 * Storage lives in `noBackupFilesDir`, which Android never includes in any backup.
 */
class VaultHolder(
    context: Context,
    val crypto: Crypto = androidCrypto(),
    device: DeviceBinding = KeystoreDeviceBinding(),
) {
    val vault = Vault(
        fs = FileSystem.SYSTEM,
        dir = context.noBackupFilesDir.toOkioPath() / "vault",
        crypto = crypto,
        device = device,
        now = System::currentTimeMillis,
    )

    private val _session = MutableStateFlow<VaultSession?>(null)
    val session: StateFlow<VaultSession?> = _session.asStateFlow()

    /**
     * Argon2id memory: as much as the phone can comfortably spare. More memory makes every
     * guess more expensive for an attacker.
     */
    val kdfMemoryBytes: Long = run {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val gib = info.totalMem / (1024L * 1024 * 1024)
        when {
            gib >= 6 -> 256L
            gib >= 3 -> 128L
            else -> 64L
        } * 1024 * 1024
    }

    fun opened(session: VaultSession) {
        _session.value = session
    }

    /**
     * Called after a session has been locked. The app uses it to end the whole process, so that
     * no stray copy of a key or of decrypted data (JNA buffers, Strings, Bitmaps) survives in
     * memory once the vault is locked.
     */
    var afterLock: () -> Unit = {}

    /** Wipes keys from memory and forgets the session. Safe to call from any thread. */
    fun lock() {
        val wasOpen = _session.value != null
        vault.lock()
        _session.value = null
        if (wasOpen) afterLock()
    }

    fun forget() {
        _session.value = null
    }
}
