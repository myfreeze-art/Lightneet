package app.lightneet.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Swaps the launcher entry between "Lightneet" and an inconspicuous "Calculator". Both are
 * aliases of the same activity; exactly one is enabled. The choice is kept by the system
 * (component state), not in any file of ours.
 */
object LauncherDisguise {
    private const val NORMAL = "app.lightneet.Launcher"
    private const val DISGUISED = "app.lightneet.Calculator"

    fun isDisguised(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(ComponentName(context, DISGUISED)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun set(context: Context, disguised: Boolean) {
        val pm = context.packageManager
        // Enable the new entry first: for a moment there are two icons, never zero.
        val (on, off) = if (disguised) DISGUISED to NORMAL else NORMAL to DISGUISED
        pm.setComponentEnabledSetting(ComponentName(context, on), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(ComponentName(context, off), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}
