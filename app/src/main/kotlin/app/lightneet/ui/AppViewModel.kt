package app.lightneet.ui

import android.app.Application
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lightneet.LightneetApp
import app.lightneet.R
import app.lightneet.core.crypto.wipe
import app.lightneet.core.device.ProtectionLevel
import app.lightneet.core.format.HiddenReserve
import app.lightneet.core.format.PanicMode
import app.lightneet.core.vault.Entry
import app.lightneet.core.vault.PasswordInUseException
import app.lightneet.core.vault.UnlockResult
import app.lightneet.core.vault.Usage
import app.lightneet.core.vault.Vault
import app.lightneet.core.vault.VaultKind
import app.lightneet.core.vault.VaultSession
import app.lightneet.core.vault.VaultState
import app.lightneet.data.Progress
import app.lightneet.data.Transfers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Gate { LOADING, SETUP, LOCKED, UNREADABLE, OPEN }

sealed interface Screen {
    data object Browser : Screen
    data class Viewer(val entryId: Long) : Screen
    data object Settings : Screen
}

sealed interface UnlockMessage {
    data class Wrong(val attemptsLeft: Int?) : UnlockMessage
    data object Destroyed : UnlockMessage
}

data class SetupProgress(val stage: Stage, val fraction: Float) {
    enum class Stage { CALIBRATING, ALLOCATING }
}

data class SettingsSnapshot(
    val kind: VaultKind,
    val autoLockSeconds: Int,
    val maxAttempts: Int,
    val canCreateHidden: Boolean,
    val protection: ProtectionLevel,
    val usage: Usage,
)

enum class SortMode { NAME, NEWEST, LARGEST }

/**
 * All UI state. Holds no plaintext beyond what is on screen; [onLocked] drops everything,
 * including the thumbnail cache.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ln = app as LightneetApp
    private val holder = ln.holder
    private val transfers = Transfers(app.contentResolver)

    private val _gate = MutableStateFlow(Gate.LOADING)
    val gate: StateFlow<Gate> = _gate.asStateFlow()

    val unlockBusy = MutableStateFlow(false)
    val unlockMessage = MutableStateFlow<UnlockMessage?>(null)
    val setupProgress = MutableStateFlow<SetupProgress?>(null)

    val screen = MutableStateFlow<Screen>(Screen.Browser)
    val folderId = MutableStateFlow(Entry.ROOT_ID)
    val entries = MutableStateFlow<List<Entry>>(emptyList())
    val breadcrumbs = MutableStateFlow<List<Entry>>(emptyList())
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    val sortMode = MutableStateFlow(SortMode.NAME)
    val gridView = MutableStateFlow(true)
    val kind = MutableStateFlow(VaultKind.MAIN)

    val progress = MutableStateFlow<Progress?>(null)
    val settings = MutableStateFlow<SettingsSnapshot?>(null)

    /** Files shared to Lightneet from other apps, waiting for the vault to be unlocked. */
    val pendingShared = MutableStateFlow<List<Uri>>(emptyList())

    /** After an import: originals the user may want removed from the phone. */
    val offerDeleteOriginals = MutableStateFlow<List<Uri>>(emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var operation: Job? = null
    private val thumbnails = LruCache<Long, ImageBitmap>(300)

    private val session: VaultSession? get() = holder.session.value

    init {
        viewModelScope.launch {
            holder.session.collect { if (it == null && _gate.value == Gate.OPEN) onLocked() }
        }
        refreshGate()
    }

    private fun refreshGate() = viewModelScope.launch {
        val state = withContext(Dispatchers.IO) { holder.vault.state() }
        _gate.value = when (state) {
            VaultState.NONE -> Gate.SETUP
            VaultState.READY -> if (session != null) Gate.OPEN else Gate.LOCKED
            VaultState.UNREADABLE -> Gate.UNREADABLE
        }
    }

    // ------------------------------------------------------------------ setup & unlock

    fun create(password: ByteArray, segments: Int, reserve: HiddenReserve) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                setupProgress.value = SetupProgress(SetupProgress.Stage.CALIBRATING, 0f)
                val kdf = Vault.calibrate(holder.crypto, holder.kdfMemoryBytes, targetMillis = 1000)
                setupProgress.value = SetupProgress(SetupProgress.Stage.ALLOCATING, 0f)
                val s = holder.vault.create(password, kdf, reserve, segments) { done, total ->
                    setupProgress.value = SetupProgress(SetupProgress.Stage.ALLOCATING, done.toFloat() / total)
                }
                onOpened(s)
            } catch (e: Exception) {
                say(R.string.error_generic, e.message ?: "")
            } finally {
                password.wipe()
                setupProgress.value = null
            }
        }
    }

    fun unlock(password: ByteArray) {
        if (unlockBusy.value) {
            password.wipe()
            return
        }
        unlockBusy.value = true
        unlockMessage.value = null
        viewModelScope.launch(Dispatchers.Default) {
            try {
                when (val r = holder.vault.unlock(password)) {
                    is UnlockResult.Opened -> onOpened(r.session)
                    is UnlockResult.WrongPassword -> unlockMessage.value = UnlockMessage.Wrong(r.attemptsLeft)
                    UnlockResult.Destroyed -> {
                        unlockMessage.value = UnlockMessage.Destroyed
                        _gate.value = Gate.SETUP
                    }
                    UnlockResult.NoVault -> _gate.value = Gate.SETUP
                    UnlockResult.Unreadable -> _gate.value = Gate.UNREADABLE
                }
            } catch (e: Exception) {
                say(R.string.error_generic, e.message ?: "")
            } finally {
                password.wipe()
                unlockBusy.value = false
            }
        }
    }

    private fun onOpened(s: VaultSession) {
        holder.opened(s)
        kind.value = s.kind
        val st = s.settings
        sortMode.value = SortMode.entries.getOrElse(st.sortMode) { SortMode.NAME }
        gridView.value = st.gridView
        folderId.value = Entry.ROOT_ID
        screen.value = Screen.Browser
        refresh()
        _gate.value = Gate.OPEN
    }

    fun lock() = holder.lock()

    private fun onLocked() {
        operation?.cancel()
        _gate.value = Gate.LOCKED
        screen.value = Screen.Browser
        folderId.value = Entry.ROOT_ID
        entries.value = emptyList()
        breadcrumbs.value = emptyList()
        selection.value = emptySet()
        settings.value = null
        progress.value = null
        offerDeleteOriginals.value = emptyList()
        thumbnails.evictAll()
    }

    /** The device key is gone (e.g. after a keystore reset); the data can never be read again. */
    fun eraseUnreadable() {
        viewModelScope.launch(Dispatchers.IO) {
            holder.vault.destroyEverything()
            _gate.value = Gate.SETUP
        }
    }

    // ------------------------------------------------------------------ browsing

    fun refresh() {
        val s = session ?: return
        runCatching {
            val list = s.children(folderId.value)
            entries.value = sort(list)
            breadcrumbs.value = if (folderId.value == Entry.ROOT_ID) emptyList() else s.path(folderId.value)
        }
    }

    private fun sort(list: List<Entry>): List<Entry> {
        val byMode = when (sortMode.value) {
            SortMode.NAME -> list.sortedBy { it.name.lowercase() }
            SortMode.NEWEST -> list.sortedByDescending { it.createdAt }
            SortMode.LARGEST -> list.sortedByDescending { it.size }
        }
        return byMode.sortedByDescending { it.isFolder }
    }

    fun openFolder(id: Long) {
        folderId.value = id
        selection.value = emptySet()
        refresh()
    }

    /** Returns false if already at the root. */
    fun up(): Boolean {
        val crumbs = breadcrumbs.value
        if (crumbs.isEmpty()) return false
        openFolder(crumbs.last().parentId)
        return true
    }

    fun open(entry: Entry) {
        if (entry.isFolder) openFolder(entry.id) else screen.value = Screen.Viewer(entry.id)
    }

    fun setSort(mode: SortMode) {
        sortMode.value = mode
        background { it.updateSettings { st -> st.copy(sortMode = mode.ordinal) } }
        refresh()
    }

    fun toggleGrid() {
        gridView.value = !gridView.value
        val v = gridView.value
        background { it.updateSettings { st -> st.copy(gridView = v) } }
    }

    fun toggleSelect(id: Long) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun selectAll() {
        selection.value = entries.value.map { it.id }.toSet()
    }

    fun entry(id: Long): Entry? = session?.entry(id)

    fun sessionOrNull(): VaultSession? = session

    suspend fun thumbnail(entry: Entry): ImageBitmap? {
        if (!entry.hasThumbnail) return null
        thumbnails.get(entry.id)?.let { return it }
        val s = session ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = s.thumbnail(entry.id) ?: return@runCatching null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bytes.fill(0)
                bmp?.asImageBitmap()
            }.getOrNull()
        }?.also { thumbnails.put(entry.id, it) }
    }

    // ------------------------------------------------------------------ changes

    fun createFolder(name: String) = background { it.createFolder(folderId.value, name); refreshOnMain() }

    fun rename(id: Long, name: String) = background { it.rename(id, name); refreshOnMain() }

    fun deleteSelected() {
        val ids = selection.value.toList()
        selection.value = emptySet()
        longOperation(getApplication<Application>().getString(R.string.progress_deleting)) { s ->
            s.delete(ids)
            ids.forEach { thumbnails.remove(it) }
        }
    }

    fun moveSelected(targetFolder: Long) {
        val ids = selection.value.toList()
        selection.value = emptySet()
        background {
            try {
                it.move(ids, targetFolder)
            } catch (_: IllegalArgumentException) {
                say(R.string.error_move_into_self)
            }
            refreshOnMain()
        }
    }

    fun folders(): List<Entry> = session?.entries()?.filter { it.isFolder }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val parent = folderId.value
        longOperation(null) { s ->
            val result = transfers.import(s, parent, uris) { progress.value = it }
            if (result.failed.isNotEmpty()) say(R.string.import_failed, result.failed.joinToString())
            else say(R.string.import_done, result.imported.size)
            offerDeleteOriginals.value = result.sources
        }
    }

    fun importPendingShared() {
        val uris = pendingShared.value
        pendingShared.value = emptyList()
        import(uris)
    }

    fun deleteOriginals() {
        val uris = offerDeleteOriginals.value
        offerDeleteOriginals.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            val n = transfers.deleteOriginals(uris)
            say(R.string.originals_deleted, n, uris.size)
        }
    }

    fun exportSingle(entry: Entry, target: Uri) = longOperation(null) { s ->
        transfers.exportFile(s, entry, target) { progress.value = it }
        say(R.string.export_done, 1)
    }

    fun exportSelected(tree: Uri) {
        val s = session ?: return
        val chosen = selection.value.mapNotNull { s.entry(it) }
        selection.value = emptySet()
        longOperation(null) { session ->
            val n = transfers.exportToTree(session, chosen, tree) { progress.value = it }
            say(R.string.export_done, n)
        }
    }

    fun cancelOperation() {
        operation?.cancel()
    }

    // ------------------------------------------------------------------ settings

    fun loadSettings() = background { s ->
        settings.value = SettingsSnapshot(
            kind = s.kind,
            autoLockSeconds = s.settings.autoLockSeconds,
            maxAttempts = s.maxAttempts,
            canCreateHidden = s.canCreateHiddenVault,
            protection = s.protectionLevel,
            usage = s.usage(),
        )
    }

    fun setAutoLock(seconds: Int) = background { it.updateSettings { st -> st.copy(autoLockSeconds = seconds) }; loadSettingsNow(it) }

    fun setMaxAttempts(n: Int) = background { it.maxAttempts = n; loadSettingsNow(it) }

    fun changePassword(pw: ByteArray) = passwordOp(pw, R.string.done_password_changed) { s, p -> s.changePassword(p) }

    fun setPanic(pw: ByteArray, mode: PanicMode) = passwordOp(pw, R.string.done_panic_set) { s, p -> s.setPanicPassword(p, mode) }

    fun createHidden(pw: ByteArray) = passwordOp(pw, R.string.done_hidden_created) { s, p -> s.createHiddenVault(p) }

    fun clearPanic() = background { it.clearPanicPassword(); say(R.string.done_panic_cleared) }

    fun destroyHidden() = background {
        it.destroyHiddenVault()
        holder.forget()
    }

    fun destroyEverything() {
        viewModelScope.launch(Dispatchers.IO) {
            session?.destroyEverything() ?: holder.vault.destroyEverything()
            withContext(Dispatchers.Main) {
                onLocked()
                _gate.value = Gate.SETUP
                holder.forget()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun passwordOp(pw: ByteArray, @StringRes done: Int, op: (VaultSession, ByteArray) -> Unit) {
        val label = getApplication<Application>().getString(R.string.progress_deriving)
        longOperation(label) { s ->
            try {
                op(s, pw)
                say(done)
            } catch (_: PasswordInUseException) {
                say(R.string.error_password_in_use)
            } finally {
                pw.wipe()
            }
            loadSettingsNow(s)
        }
    }

    private fun loadSettingsNow(s: VaultSession) {
        settings.value = settings.value?.copy(
            autoLockSeconds = s.settings.autoLockSeconds,
            maxAttempts = s.maxAttempts,
            usage = s.usage(),
        )
    }

    private fun background(block: suspend (VaultSession) -> Unit) {
        val s = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (s.isOpen) say(R.string.error_generic, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** One cancellable long-running operation at a time, with a progress bar. */
    private fun longOperation(label: String?, block: suspend (VaultSession) -> Unit) {
        val s = session ?: return
        if (operation?.isActive == true) {
            say(R.string.error_busy)
            return
        }
        progress.value = Progress(label ?: "", 0, -1)
        operation = viewModelScope.launch(Dispatchers.IO) {
            try {
                block(s)
            } catch (_: CancellationException) {
                // Cancelled by the user or by locking; partial imports were rolled back by the core.
            } catch (e: Exception) {
                if (s.isOpen) say(R.string.error_generic, e.message ?: e.javaClass.simpleName)
            } finally {
                progress.value = null
                refreshOnMain()
            }
        }
    }

    private suspend fun refreshOnMain() = withContext(Dispatchers.Main) { refresh() }

    private fun say(@StringRes id: Int, vararg args: Any) {
        _messages.trySend(getApplication<Application>().getString(id, *args))
    }
}
