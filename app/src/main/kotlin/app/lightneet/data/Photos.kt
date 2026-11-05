package app.lightneet.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import app.lightneet.core.vault.Entry
import app.lightneet.core.vault.VaultSession
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Encrypts a photo straight from the camera's memory buffer into the vault. Nothing is ever
 * written to the gallery, to MediaStore or to a temporary file.
 *
 * If the camera reports the picture as rotated, it is turned upright and re-encoded. That also
 * drops every EXIF tag the camera may have added (camera model, exposure, timestamps).
 */
fun encryptPhoto(session: VaultSession, parentId: Long, jpeg: ByteArray, rotationDegrees: Int, takenAt: Long): Entry {
    val upright = if (rotationDegrees % 360 != 0) rotateJpeg(jpeg, rotationDegrees) else null
    val bytes = upright ?: jpeg
    try {
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(takenAt)) + ".jpg"
        val entry = Buffer().use { buffer ->
            buffer.write(bytes)
            session.importFile(parentId, name, "image/jpeg", buffer)
        }
        runCatching { makeThumbnail(session, entry) }
        return entry
    } finally {
        upright?.fill(0)
    }
}

private fun rotateJpeg(jpeg: ByteArray, degrees: Int): ByteArray? {
    val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
    val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    if (rotated !== src) src.recycle()
    val out = ByteArrayOutputStream(jpeg.size)
    rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
    rotated.recycle()
    return out.toByteArray()
}
