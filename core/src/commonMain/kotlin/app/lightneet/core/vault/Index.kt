@file:OptIn(ExperimentalSerializationApi::class)

package app.lightneet.core.vault

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked

/**
 * One file or folder. Names, sizes, dates and types live only here, inside the encrypted
 * index — nothing about them is visible on disk.
 */
@Serializable
data class Entry(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val parentId: Long,
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val isFolder: Boolean,
    @ProtoNumber(5) val size: Long = 0,
    @ProtoNumber(6) val mimeType: String = "",
    @ProtoNumber(7) val createdAt: Long = 0,
    @ProtoNumber(8) val modifiedAt: Long = 0,
    /** Per-file random key. Deleting a file forgets its key. */
    @ProtoNumber(9) internal val key: ByteArray = ByteArray(0),
    @ProtoNumber(10) @ProtoPacked internal val blocks: List<Long> = emptyList(),
    @ProtoNumber(11) @ProtoPacked internal val thumbnailBlocks: List<Long> = emptyList(),
    @ProtoNumber(12) internal val thumbnailSize: Int = 0,
) {
    val hasThumbnail: Boolean get() = thumbnailSize > 0

    internal fun ownedBlocks(): Sequence<Long> = blocks.asSequence() + thumbnailBlocks.asSequence()

    companion object {
        const val ROOT_ID = 0L
    }
}

/** Per-vault preferences. The main and the hidden vault each have their own. */
@Serializable
data class VaultSettings(
    /** Seconds the app may stay in the background before locking. 0 = lock immediately. */
    @ProtoNumber(1) val autoLockSeconds: Int = 0,
    @ProtoNumber(2) val sortMode: Int = 0,
    @ProtoNumber(3) val gridView: Boolean = true,
)

@Serializable
internal data class VaultIndex(
    @ProtoNumber(1) val nextId: Long = 1,
    @ProtoNumber(2) val entries: List<Entry> = emptyList(),
    @ProtoNumber(3) val settings: VaultSettings = VaultSettings(),
) {
    fun encode(): ByteArray = ProtoBuf.encodeToByteArray(serializer(), this)

    companion object {
        fun decode(bytes: ByteArray): VaultIndex = ProtoBuf.decodeFromByteArray(serializer(), bytes)
    }
}
