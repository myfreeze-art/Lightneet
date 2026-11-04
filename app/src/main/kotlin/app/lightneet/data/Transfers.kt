package app.lightneet.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.lightneet.core.vault.Entry
import app.lightneet.core.vault.VaultFileReader
import app.lightneet.core.vault.VaultSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.buffer
import okio.sink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class Progress(val label: String, val done: Long, val total: Long)

/** Moves files between Android's storage (via the system picker) and the vault. */
class Transfers(private val resolver: ContentResolver) {

    class ImportResult(val imported: List<Entry>, val failed: List<String>, val sources: List<Uri>)

    suspend fun import(
        session: VaultSession,
        parentId: Long,
        uris: List<Uri>,
        onProgress: (Progress) -> Unit,
    ): ImportResult {
        val imported = ArrayList<Entry>()
        val failed = ArrayList<String>()
        val ok = ArrayList<Uri>()
        val ctx = currentCoroutineContext()
        for ((i, uri) in uris.withIndex()) {
            val (name, size) = describe(uri)
            val label = if (uris.size > 1) "${i + 1}/${uris.size} · $name" else name
            try {
                val mime = resolver.getType(uri) ?: guessMime(name)
                val input = resolver.openInputStream(uri) ?: throw IllegalStateException("cannot open")
                val entry = input.source().use { src ->
                    session.importFile(parentId, name, mime, src) { done ->
                        ctx.ensureActive()
                        onProgress(Progress(label, done, size))
                    }
                }
                imported += entry
                ok += uri
                runCatching { makeThumbnail(session, entry) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                failed += name
            }
        }
        return ImportResult(imported, failed, ok)
    }

    /**
     * Asks the source app to delete the originals. Flash storage may still hold old copies
     * of the data — the UI says so — but this removes them from the gallery and file managers.
     */
    fun deleteOriginals(uris: List<Uri>): Int = uris.count { uri ->
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false) ||
            runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    /** Exports one file to a document the user created in the picker. */
    suspend fun exportFile(session: VaultSession, entry: Entry, target: Uri, onProgress: (Progress) -> Unit) {
        val ctx = currentCoroutineContext()
        val out = resolver.openOutputStream(target, "wt") ?: throw IllegalStateException("cannot write")
        out.sink().buffer().use { sink ->
            session.exportTo(entry.id, sink) { done ->
                ctx.ensureActive()
                onProgress(Progress(entry.name, done, entry.size))
            }
        }
    }

    /** Exports entries (folders recursively) into a directory tree the user picked. */
    suspend fun exportToTree(session: VaultSession, entries: List<Entry>, tree: Uri, onProgress: (Progress) -> Unit): Int {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var count = 0
        suspend fun write(e: Entry, parent: Uri) {
            if (e.isFolder) {
                val dir = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, e.name) ?: return
                session.children(e.id).forEach { write(it, dir) }
            } else {
                val doc = DocumentsContract.createDocument(resolver, parent, e.mimeType.ifEmpty { "application/octet-stream" }, e.name) ?: return
                exportFile(session, e, doc, onProgress)
                count++
            }
        }
        entries.forEach { write(it, root) }
        return count
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return name to size
    }

    companion object {
        fun guessMime(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}

// ------------------------------------------------------------------------------ thumbnails

private const val THUMB_PX = 320

/** Builds an encrypted thumbnail for images and videos; nothing is ever written in plaintext. */
fun makeThumbnail(session: VaultSession, entry: Entry) {
    val bitmap = when {
        entry.mimeType.startsWith("image/") -> decodeSampled(session, entry, THUMB_PX)
        entry.mimeType.startsWith("video/") -> videoFrame(session, entry)
        else -> null
    } ?: return
    val square = centerCrop(bitmap, THUMB_PX)
    val out = ByteArrayOutputStream()
    square.compress(Bitmap.CompressFormat.JPEG, 82, out)
    square.recycle()
    if (square !== bitmap) bitmap.recycle()
    val bytes = out.toByteArray()
    session.setThumbnail(entry.id, bytes)
    bytes.fill(0)
}

/** Decodes an image from the vault straight into a bitmap no larger than needed. */
fun decodeSampled(session: VaultSession, entry: Entry, maxPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    session.openReader(entry.id).use { BitmapFactory.decodeStream(VaultInputStream(it), null, bounds) }
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    val bmp = session.openReader(entry.id).use {
        BitmapFactory.decodeStream(VaultInputStream(it), null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val rotation = runCatching {
        session.openReader(entry.id).use { r ->
            when (ExifInterface(VaultInputStream(r)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
    }.getOrDefault(0f)
    if (rotation == 0f) return bmp
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    if (rotated !== bmp) bmp.recycle()
    return rotated
}

private fun videoFrame(session: VaultSession, entry: Entry): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return session.openReader(entry.id).use { reader ->
        try {
            retriever.setDataSource(VaultMediaDataSource(reader))
            retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_PX * 2, THUMB_PX * 2)
        } finally {
            retriever.release()
        }
    }
}

private fun centerCrop(src: Bitmap, size: Int): Bitmap {
    val side = minOf(src.width, src.height)
    val x = (src.width - side) / 2
    val y = (src.height - side) / 2
    val scale = size.toFloat() / side
    return Bitmap.createBitmap(src, x, y, side, side, Matrix().apply { postScale(scale, scale) }, true)
}

// ------------------------------------------------------------------------------ adapters

/** Streams a vault file to Android APIs that want an [InputStream]. Plaintext stays in memory. */
class VaultInputStream(private val reader: VaultFileReader) : InputStream() {
    private var pos = 0L
    private var mark = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = reader.read(pos, b, off, len)
        if (n > 0) pos += n
        return n
    }

    override fun skip(n: Long): Long {
        val step = minOf(n, reader.size - pos).coerceAtLeast(0)
        pos += step
        return step
    }

    override fun available(): Int = (reader.size - pos).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    override fun markSupported() = true
    override fun mark(readlimit: Int) { mark = pos }
    override fun reset() { pos = mark }
}

/** Lets [MediaMetadataRetriever] read an encrypted video with random access. */
class VaultMediaDataSource(private val reader: VaultFileReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        var done = 0
        while (done < size) {
            val n = reader.read(position + done, buffer, offset + done, size - done)
            if (n <= 0) break
            done += n
        }
        return if (done == 0 && size > 0) -1 else done
    }

    override fun getSize(): Long = reader.size
    override fun close() = Unit
}
