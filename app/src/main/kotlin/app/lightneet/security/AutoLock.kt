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
 * [allowExternalActivity] relaxes the background rule for that one trip — but only for
 * [EXTERNAL_TRIP_LIMIT_MS]. Leaving through the picker (e.g. pressing Home inside it) can no
 * longer keep the vault open indefinitely.
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
                    handler.removeCallbacks(lockNow)
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
        val session = holder.session.value ?: return
        val configured = runCatching { session.settings.autoLockSeconds }.getOrDefault(0) * 1000L
        val delay = if (externalTrip) maxOf(configured, EXTERNAL_TRIP_LIMIT_MS) else configured
        if (delay <= 0) holder.lock() else handler.postDelayed(lockNow, delay)
    }

    companion object {
        /** How long the system file picker may stay open before the vault locks anyway. */
        const val EXTERNAL_TRIP_LIMIT_MS = 2 * 60 * 1000L
    }
}
