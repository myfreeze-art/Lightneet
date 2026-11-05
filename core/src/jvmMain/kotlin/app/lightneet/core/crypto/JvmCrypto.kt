package app.lightneet.core.crypto

import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.utils.LibraryLoader

/** libsodium for desktop JVMs (used by the test-suite). */
fun jvmCrypto(): Crypto {
    val sodium = SodiumJava()
    // Resolve the library exactly as SodiumJava() just did, and bind the pointer-based functions to it.
    SodiumNative.bind { LibraryLoader(listOf(it)).loadLibrary(LibraryLoader.Mode.PREFER_SYSTEM, "sodium") }
    return SodiumCrypto(sodium)
}
