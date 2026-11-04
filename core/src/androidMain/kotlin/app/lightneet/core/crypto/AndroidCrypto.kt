package app.lightneet.core.crypto

import com.goterl.lazysodium.SodiumAndroid

/** libsodium bundled as native .so files for every Android ABI. */
fun androidCrypto(): Crypto = SodiumCrypto(SodiumAndroid())
