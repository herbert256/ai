package com.ai.ui.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Camera-capture launcher used by the AI Reports / AI Chat hubs'
 * "📸 Start with photo" entry points.
 *
 * The system camera (via [ActivityResultContracts.TakePicture])
 * writes a full-resolution JPEG into a per-call temp file under
 * `cacheDir/camera_captures/`. On success we downsample +
 * recompress (JPEG quality 85, long side ≤ 2048 px) so the
 * base64 payload stays under ~1 MB — phone cameras hand back
 * 12–48 MP files and most vision providers reject anything past
 * ~20 MB raw. The temp file is deleted after encoding so
 * cacheDir doesn't accumulate camera originals.
 *
 * Returns a `() -> Unit` the caller invokes from a button onClick.
 * When the user cancels in the camera app the launcher silently
 * returns false and nothing happens. When decoding fails (corrupt
 * file / OOM) [onError] fires with a short message.
 */
@Composable
fun rememberCameraCaptureLauncher(
    onCaptured: (mime: String, base64: String) -> Unit,
    onError: (String) -> Unit = {}
): () -> Unit {
    val context = LocalContext.current
    // Path to the file the next launch will write to. Kept across
    // recompositions so the result callback can find the bytes the
    // camera wrote. Each launch reassigns it to a fresh file so
    // cancelling and re-launching doesn't reuse a stale path.
    val pendingFile = remember { Holder<File?>(null) }
    val pendingUri = remember { Holder<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingFile.value
        pendingFile.value = null
        pendingUri.value = null
        if (file == null) return@rememberLauncherForActivityResult
        if (!success) {
            // User cancelled or camera app failed; clean up the
            // empty placeholder file the FileProvider was wired to.
            runCatching { file.delete() }
            return@rememberLauncherForActivityResult
        }
        try {
            val b64 = downsampleToBase64Jpeg(file)
            onCaptured("image/jpeg", b64)
        } catch (e: Exception) {
            onError("Failed to read photo: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { file.delete() }
        }
    }

    return {
        try {
            val dir = File(context.cacheDir, "camera_captures").also { if (!it.exists()) it.mkdirs() }
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            // Empty placeholder — the camera app appends bytes via
            // FileProvider grant. Pre-touching avoids "no such file"
            // from picky camera apps.
            file.createNewFile()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingFile.value = file
            pendingUri.value = uri
            launcher.launch(uri)
        } catch (e: Exception) {
            onError("Could not open camera: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** Tiny mutable holder so the result callback (which captures by
 *  closure) can read the file path the launcher arm wrote. */
private class Holder<T>(var value: T)

/** Decode [file], downsample so the long side is ≤ 2048 px,
 *  recompress as JPEG quality 85, return base64 (no-wrap). Throws
 *  on decode failure so the caller can route the error through
 *  [onError] uniformly. */
private fun downsampleToBase64Jpeg(file: File): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    var sampleSize = 1
    while (maxDim / sampleSize > 2048) sampleSize *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
        ?: throw IllegalStateException("Could not decode photo (empty / corrupt file)")
    return try {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } finally {
        bitmap.recycle()
    }
}

/** Convenience for the rare caller that has a [Context] in hand
 *  (not a Composable scope) and just wants to encode an arbitrary
 *  file with the same downsample/recompress rules. Currently
 *  unused outside this file but kept exported for future hubs. */
@Suppress("unused")
fun downsamplePhotoToBase64Jpeg(context: Context, file: File): String =
    downsampleToBase64Jpeg(file)
