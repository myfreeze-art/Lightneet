package app.lightneet.core.crypto

import com.goterl.lazysodium.SodiumJava

/** libsodium for desktop JVMs (used by the test-suite). */
fun jvmCrypto(): Crypto = SodiumCrypto(SodiumJava())
