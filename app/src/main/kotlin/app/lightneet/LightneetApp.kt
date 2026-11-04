package app.lightneet

import android.app.Application
import android.content.Intent
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.lightneet.data.VaultHolder
import app.lightneet.security.AutoLock
import app.lightneet.security.RestartActivity

open class LightneetApp : Application() {
    lateinit var holder: VaultHolder
        private set

    lateinit var autoLock: AutoLock
        private set

    override fun onCreate() {
        super.onCreate()
        // The tiny ":restart" helper process must not touch the vault at all.
        if (!isMainProcess()) return
        holder = createHolder()
        holder.afterLock = ::endProcess
        autoLock = AutoLock(this, holder).also { it.install() }
    }

    protected open fun isMainProcess() = Application.getProcessName() == packageName

    /** Tests replace the hardware keystore, which Robolectric cannot emulate. */
    protected open fun createHolder() = VaultHolder(this)

    /**
     * After locking, the process is terminated: the kernel reclaims all of its memory, including
     * copies of secrets we cannot reach (native buffers made by JNA, immutable Strings, Bitmaps).
     * If the app is on screen, a helper process immediately reopens it at the lock screen.
     */
    protected open fun endProcess() {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (foreground) {
            startActivity(
                Intent(this, RestartActivity::class.java)
                    .putExtra(RestartActivity.EXTRA_PID, Process.myPid())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            Process.killProcess(Process.myPid())
        }
    }
}
