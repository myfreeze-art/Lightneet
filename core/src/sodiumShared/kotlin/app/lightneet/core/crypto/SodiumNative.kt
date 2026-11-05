package app.lightneet.core.crypto

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer

/**
 * libsodium functions that take keys by *pointer*. lazysodium only offers `byte[]` versions,
 * and JNA copies every `byte[]` into a temporary native buffer that is freed without being
 * zeroed. With these, a [SecretKey] goes from guarded memory to libsodium without a copy.
 *
 * Bound by JNA direct mapping to the same libsodium that lazysodium loaded.
 */
internal object SodiumNative {
    private var bound = false

    @Synchronized
    fun bind(register: (Class<*>) -> Unit) {
        if (bound) return
        register(SodiumNative::class.java)
        check(sodium_init() >= 0) { "libsodium failed to initialise" }
        bound = true
    }

    fun bindByName(name: String) = bind { Native.register(it, name) }

    @JvmStatic external fun sodium_init(): Int

    @JvmStatic external fun sodium_malloc(size: NativeLong): Pointer?

    @JvmStatic external fun sodium_free(ptr: Pointer)

    @JvmStatic external fun sodium_mprotect_readonly(ptr: Pointer): Int

    @JvmStatic external fun sodium_mprotect_readwrite(ptr: Pointer): Int

    /** `a += b` as little-endian numbers. With `a` all zeros this is a native copy of `b`. */
    @JvmStatic external fun sodium_add(a: Pointer, b: Pointer, len: NativeLong)

    @JvmStatic external fun randombytes_buf(buf: Pointer, size: NativeLong)

    @JvmStatic external fun crypto_generichash(out: Pointer, outlen: NativeLong, input: ByteArray, inlen: Long, key: Pointer, keylen: NativeLong): Int

    @JvmStatic external fun crypto_aead_xchacha20poly1305_ietf_encrypt(
        c: ByteArray, clen: LongArray?, m: ByteArray, mlen: Long, ad: ByteArray, adlen: Long, nsec: ByteArray?, npub: ByteArray, k: Pointer,
    ): Int

    @JvmStatic external fun crypto_aead_xchacha20poly1305_ietf_decrypt(
        m: ByteArray, mlen: LongArray?, nsec: ByteArray?, c: ByteArray, clen: Long, ad: ByteArray, adlen: Long, npub: ByteArray, k: Pointer,
    ): Int
}
