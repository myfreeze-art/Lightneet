package app.lightneet.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import app.lightneet.R
import app.lightneet.core.vault.Entry
import app.lightneet.core.vault.VaultFileReader
import app.lightneet.core.vault.VaultSession
import app.lightneet.data.decodeSampled
import app.lightneet.ui.AppViewModel
import app.lightneet.ui.Screen
import app.lightneet.ui.browser.iconFor
import app.lightneet.ui.common.Gap
import app.lightneet.ui.common.PrimaryButton
import app.lightneet.ui.common.formatSize
import app.lightneet.ui.theme.LnColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_TEXT_BYTES = 2L * 1024 * 1024
private val TEXT_EXTENSIONS = setOf("txt", "md", "log", "csv", "json", "xml", "yaml", "yml", "ini", "conf", "kt", "java", "py", "js", "html", "css", "sh")

/**
 * Full-screen viewer; swipe between the files of the current folder. Everything is decoded
 * in memory — no temporary files, no external apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: AppViewModel, entryId: Long, beforeExternal: () -> Unit) {
    val session = vm.sessionOrNull() ?: return
    val all by vm.entries.collectAsState()
    val files = remember(all) { all.filter { !it.isFolder } }
    val start = files.indexOfFirst { it.id == entryId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { files.size }
    var chrome by remember { mutableStateOf(true) }
    val current = files.getOrNull(pager.currentPage)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val e = current
        if (uri != null && e != null) vm.exportSingle(e, uri)
    }
    val back = { vm.screen.value = Screen.Browser }
    BackHandler(onBack = back)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (files.isEmpty()) return@Box
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { files[it].id }, beyondViewportPageCount = 0) { page ->
            val e = files[page]
            Content(session, e, active = page == pager.currentPage, onTap = { chrome = !chrome }) {
                beforeExternal()
                exportLauncher.launch(e.name)
            }
        }
        if (chrome && current != null) {
            TopAppBar(
                modifier = Modifier.align(Alignment.TopCenter),
                title = {
                    Column {
                        Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(formatSize(current.size), style = MaterialTheme.typography.bodySmall, color = LnColors.TextMuted)
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = { beforeExternal(); exportLauncher.launch(current.name) }) {
                        Icon(Icons.Outlined.SaveAlt, stringResource(R.string.export))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.55f)),
            )
        }
    }
}

@Composable
private fun Content(session: VaultSession, e: Entry, active: Boolean, onTap: () -> Unit, onExport: () -> Unit) {
    val ext = e.name.substringAfterLast('.', "").lowercase()
    when {
        e.mimeType.startsWith("image/") -> ImagePage(session, e, onTap)
        e.mimeType.startsWith("video/") || e.mimeType.startsWith("audio/") -> MediaPage(session, e, active)
        pdfPreviewSupported(e) -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) PdfPage(session, e) { NoPreview(e, onExport) }
        (e.mimeType.startsWith("text/") || e.mimeType == "application/json" || ext in TEXT_EXTENSIONS) && e.size <= MAX_TEXT_BYTES -> TextPage(session, e)
        else -> NoPreview(e, onExport)
    }
}

@Composable
private fun ImagePage(session: VaultSession, e: Entry, onTap: () -> Unit) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, e.id) {
        val max = context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }.coerceAtMost(4096)
        value = withContext(Dispatchers.IO) { runCatching { decodeSampled(session, e, max)?.asImageBitmap() }.getOrNull() }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(e.id) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero })
            }
            .transformable(state, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img == null) CircularProgressIndicator(color = LnColors.Accent)
        else Image(
            img, e.name,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        )
    }
}

@AndroidOptIn(UnstableApi::class)
@Composable
private fun MediaPage(session: VaultSession, e: Entry, active: Boolean) {
    val context = LocalContext.current
    val player = remember(e.id) { buildPlayer(context, session, e) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(active) { if (!active) player.pause() }
    AndroidView(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        factory = { PlayerView(it).apply { this.player = player; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
    )
}

@AndroidOptIn(UnstableApi::class)
private fun buildPlayer(context: Context, session: VaultSession, e: Entry): ExoPlayer {
    val factory = DataSource.Factory { VaultDataSource(session, e.id) }
    return ExoPlayer.Builder(context).build().apply {
        setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri("lightneet://file/${e.id}")))
        prepare()
    }
}

@Composable
private fun TextPage(session: VaultSession, e: Entry) {
    val text by produceState<String?>(null, e.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = session.readAll(e.id)
                String(bytes, Charsets.UTF_8).also { bytes.fill(0) }
            }.getOrDefault("")
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val t = text
        if (t == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = LnColors.Accent)
        else Text(
            t,
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 24.dp),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NoPreview(e: Entry, onExport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Icon(iconFor(e), null, Modifier.size(56.dp), tint = LnColors.TextMuted)
        Gap(16.dp)
        Text(e.name, style = MaterialTheme.typography.titleMedium)
        Gap(4.dp)
        Text(stringResource(R.string.no_preview), color = LnColors.TextMuted)
        Gap(24.dp)
        PrimaryButton(stringResource(R.string.export), onExport, Modifier.fillMaxWidth(0.7f))
        Gap(8.dp)
        Text(stringResource(R.string.export_warning), color = LnColors.TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Feeds ExoPlayer straight from the vault. Seeking jumps to the right encrypted block, so a
 * 4 GB video starts instantly and never exists in plaintext outside RAM.
 */
@UnstableApi
private class VaultDataSource(private val session: VaultSession, private val entryId: Long) : BaseDataSource(false) {
    private var reader: VaultFileReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val r = session.openReader(entryId)
        reader = r
        uri = dataSpec.uri
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.size - position
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val n = reader!!.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        reader?.close()
        reader = null
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }
}
