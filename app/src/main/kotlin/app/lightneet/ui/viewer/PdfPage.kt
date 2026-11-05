package app.lightneet.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.lightneet.core.vault.Entry
import app.lightneet.core.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileDescriptor

/** PDFs larger than this are offered for export instead (they would be held in RAM). */
internal const val MAX_PDF_BYTES = 64L * 1024 * 1024

/** The in-memory PDF viewer needs `memfd_create`, available from Android 11. */
internal fun pdfPreviewSupported(e: Entry) =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && e.size <= MAX_PDF_BYTES &&
        (e.mimeType == "application/pdf" || e.name.endsWith(".pdf", ignoreCase = true))

/**
 * Renders a PDF without ever writing it to storage. The decrypted bytes go into an anonymous
 * memory file (`memfd`) — it has no path, lives only in RAM and disappears when closed —
 * and [PdfRenderer] reads from it like from an ordinary file.
 */
@RequiresApi(Build.VERSION_CODES.R)
@Composable
internal fun PdfPage(session: VaultSession, e: Entry, onFailure: @Composable () -> Unit) {
    val document by produceState<Result<MemoryPdf>?>(null, e.id) {
        // NonCancellable: even if the page is swiped away mid-decryption, the memfd is closed below.
        val opened = withContext(NonCancellable + Dispatchers.IO) { runCatching { MemoryPdf.open(session, e) } }
        value = opened
        awaitDispose { opened.getOrNull()?.close() }
    }

    val doc = document
    when {
        doc == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        doc.isFailure -> onFailure()
        else -> {
            val pdf = doc.getOrThrow()
            val widthPx = LocalContext.current.resources.displayMetrics.widthPixels.coerceAtMost(2048)
            LazyColumn(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF202020)),
                contentPadding = PaddingValues(top = 72.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pdf.pageCount) { i -> PdfPageImage(pdf, i, widthPx) }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun PdfPageImage(pdf: MemoryPdf, index: Int, widthPx: Int) {
    val ratio = pdf.aspect(index)
    val image by produceState<ImageBitmap?>(null, pdf, index) {
        value = runCatching { pdf.render(index, widthPx) }.getOrNull()?.asImageBitmap()
    }
    DisposableEffect(image) { onDispose { image?.asAndroidBitmap()?.recycle() } }
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(androidx.compose.ui.graphics.Color.White), contentAlignment = Alignment.Center) {
        val img = image
        if (img == null) CircularProgressIndicator()
        else Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** A decrypted PDF held in a memfd. [PdfRenderer] is not thread-safe, so all access is serialised. */
@RequiresApi(Build.VERSION_CODES.R)
internal class MemoryPdf private constructor(private val memfd: FileDescriptor, private val renderer: PdfRenderer) : Closeable {
    private val aspects = HashMap<Int, Float>()
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    @Synchronized
    fun aspect(index: Int): Float = aspects.getOrPut(index) {
        if (closed) return 0.707f
        renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(this@MemoryPdf) {
            if (closed) return@withContext null
            renderer.openPage(index).use { page ->
                val w = widthPx
                val h = (w.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, 4 * w)
                createBitmap(w, h).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        // Drop the pages before closing, so the plaintext is released at once.
        runCatching { Os.ftruncate(memfd, 0) }
        runCatching { Os.close(memfd) }
    }

    companion object {
        fun open(session: VaultSession, e: Entry): MemoryPdf {
            val fd = Os.memfd_create("ln", OsConstants.MFD_CLOEXEC)
            try {
                session.openReader(e.id).use { r ->
                    val buf = ByteArray(256 * 1024)
                    var pos = 0L
                    while (pos < r.size) {
                        val n = r.read(pos, buf, 0, buf.size)
                        if (n <= 0) break
                        var off = 0
                        while (off < n) off += Os.write(fd, buf, off, n - off)
                        pos += n
                    }
                    buf.fill(0)
                }
                Os.lseek(fd, 0, OsConstants.SEEK_SET)
                val renderer = PdfRenderer(ParcelFileDescriptor.dup(fd))
                return MemoryPdf(fd, renderer)
            } catch (t: Throwable) {
                runCatching { Os.ftruncate(fd, 0) }
                runCatching { Os.close(fd) }
                throw t
            }
        }
    }
}
