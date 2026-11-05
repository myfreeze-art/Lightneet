package app.lightneet.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lightneet.R
import app.lightneet.ui.AppViewModel
import app.lightneet.ui.Screen
import app.lightneet.ui.theme.LnColors

/**
 * Takes photos straight into the vault. CameraX hands over the JPEG as an in-memory buffer
 * (`OnImageCapturedCallback`), which is encrypted and wiped; no file is ever created.
 */
@Composable
fun CameraScreen(vm: AppViewModel, beforeExternal: () -> Unit) {
    val context = LocalContext.current
    val back = { vm.screen.value = Screen.Browser }
    BackHandler(onBack = back)

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) {
            vm.toast(R.string.camera_denied)
            back()
        }
    }
    LaunchedEffect(Unit) {
        if (!granted) {
            beforeExternal()
            ask.launch(Manifest.permission.CAMERA)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) Viewfinder(vm)
        IconButton(onClick = back, modifier = Modifier.safeDrawingPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), tint = Color.White)
        }
    }
}

@Composable
private fun Viewfinder(vm: AppViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val taken by vm.photosTaken.collectAsState()
    var front by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    DisposableEffect(front) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                val previewUseCase = androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, selector, previewUseCase, capture)
            }.onFailure { vm.toast(R.string.camera_failed, it.message ?: it.javaClass.simpleName) }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView({ preview }, Modifier.weight(1f).fillMaxWidth())
        Column(
            Modifier.fillMaxWidth().background(Color.Black).safeDrawingPadding().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (taken > 0) pluralStringResource(R.plurals.photos_saved, taken, taken) else stringResource(R.string.camera_hint),
                color = LnColors.TextMuted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp))
                val shutter = stringResource(R.string.camera_shutter)
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(if (busy) LnColors.TextMuted else Color.White)
                        .semantics { contentDescription = shutter }
                        .clickable(enabled = !busy) {
                            busy = true
                            capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val rotation = image.imageInfo.rotationDegrees
                                    val jpeg = image.use { it.jpegBytes() }
                                    vm.savePhoto(jpeg, rotation)
                                    busy = false
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    busy = false
                                    vm.toast(R.string.camera_failed, exception.message ?: exception.imageCaptureError.toString())
                                }
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 3.dp)
                }
                IconButton(onClick = { front = !front }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Cameraswitch, stringResource(R.string.camera_switch), tint = Color.White)
                }
            }
        }
    }
}

/** Copies the JPEG out of the camera buffer and zeroes the buffer, if it is writable. */
private fun ImageProxy.jpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    buffer.rewind()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (!buffer.isReadOnly) {
        buffer.rewind()
        while (buffer.hasRemaining()) buffer.put(0)
    }
    return bytes
}
