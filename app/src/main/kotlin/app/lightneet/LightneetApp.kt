package app.lightneet

import android.app.Application
import app.lightneet.data.VaultHolder
import app.lightneet.security.AutoLock

open class LightneetApp : Application() {
    lateinit var holder: VaultHolder
        private set

    lateinit var autoLock: AutoLock
        private set

    override fun onCreate() {
        super.onCreate()
        holder = createHolder()
        autoLock = AutoLock(this, holder).also { it.install() }
    }

    /** Tests replace the hardware keystore, which Robolectric cannot emulate. */
    protected open fun createHolder() = VaultHolder(this)
}
