package app.lightneet.security

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.lightneet.data.VaultHolder

/**
 * Locks the vault — wiping every key from memory — when:
 *  - the screen turns off (always, immediately);
 *  - the app goes to the background, after the vault's auto-lock delay (0 = immediately).
 *
 * The system file picker is another app, so opening it would normally lock the vault.
 * [allowExternalActivity] suspends the background rule for exactly that one trip.
 */
class AutoLock(private val app: Application, private val holder: VaultHolder) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val lockNow = Runnable { holder.lock() }
    private var externalTrip = false

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    externalTrip = false
                    holder.lock()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun allowExternalActivity() {
        externalTrip = true
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.removeCallbacks(lockNow)
        externalTrip = false
    }

    override fun onStop(owner: LifecycleOwner) {
        if (externalTrip) return
        val session = holder.session.value ?: return
        val delay = runCatching { session.settings.autoLockSeconds }.getOrDefault(0)
        if (delay <= 0) holder.lock() else handler.postDelayed(lockNow, delay * 1000L)
    }
}
