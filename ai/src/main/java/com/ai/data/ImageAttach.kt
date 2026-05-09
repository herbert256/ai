package com.ai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Resolves a content URI to a (mime, base64) pair suitable for an LLM
 * vision request, capping the long-edge dimension and re-encoding as
 * JPEG so a 12 MP phone photo doesn't show up as a 6+ MB base64 blob
 * in the request body.
 *
 * The previous flow read full bytes via openInputStream + readBytes
 * and base64-encoded them as-is, which spiked memory (raw + base64
 * string + intermediate buffers) and shipped image payloads larger
 * than the provider would accept under their per-image limit.
 *
 * [maxEdgePx] defaults to 1568 — the long edge Anthropic and OpenAI
 * both accept without scaling on their end.
 *
 * Returns null on read or decode failure.
 */
fun loadImageAsBase64(
    context: Context,
    uri: Uri,
    maxEdgePx: Int = 1568,
    jpegQuality: Int = 85
): Pair<String, String>? {
    val cr = context.contentResolver
    val sourceMime = cr.getType(uri) ?: "image/jpeg"

    // First pass: read bounds only so we can pick an inSampleSize that
    // gets us close to maxEdgePx without decoding the full bitmap.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)

    // Second pass: actual decode at sample size.
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = cr.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, decodeOpts)
    } ?: return null

    // Final exact scale for the long edge.
    val scaled = scaleToMaxEdge(raw, maxEdgePx)
    if (scaled !== raw) raw.recycle()

    val outBytes = ByteArrayOutputStream().use { bos ->
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bos)
        bos.toByteArray()
    }
    scaled.recycle()
    return "image/jpeg" to Base64.encodeToString(outBytes, Base64.NO_WRAP)
}

private fun computeSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    val longEdge = maxOf(width, height)
    while (longEdge / sample > maxEdge * 2) sample *= 2
    return sample
}

private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
    val long = maxOf(src.width, src.height)
    if (long <= maxEdge) return src
    val scale = maxEdge.toFloat() / long
    val newW = (src.width * scale).toInt().coerceAtLeast(1)
    val newH = (src.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, newW, newH, true)
}
