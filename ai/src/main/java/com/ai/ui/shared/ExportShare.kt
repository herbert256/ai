package com.ai.ui.shared

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/** Stage [body]'s output as `<fileName>` under `cacheDir/exports/`,
 *  then fire `Intent.ACTION_SEND` with a `FileProvider` URI so the
 *  user picks any installed destination — Email, Drive, Files,
 *  Slack, the system file picker, etc. The cacheDir entry is a
 *  staging file the receiving app reads once; Android may prune it
 *  later but the share consumer has the bytes by then.
 *
 *  Replaces the older SAF `CreateDocument` launcher pattern, which
 *  could only write to a SAF-picked filesystem location and never
 *  let the user route the export into a messaging / mail app.
 *
 *  [mimeType] drives the chooser's app filter (`application/zip`,
 *  `application/json`, `text/csv`, …). [chooserTitle] is the
 *  system chooser's "Share via" header. */
fun shareExport(
    context: Context,
    fileName: String,
    mimeType: String,
    chooserTitle: String,
    body: (OutputStream) -> Unit
) {
    val dir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
    val file = File(dir, fileName)
    // Stage into <name>.part and atomic-rename so a crash mid-body()
    // doesn't leave a truncated cache file. The share chooser only
    // ever sees a fully-written file; a half-written one would be
    // pulled by the receiver before body() actually finished.
    val staging = File(dir, "$fileName.part")
    try {
        staging.outputStream().use { body(it) }
        if (file.exists()) file.delete()
        if (!staging.renameTo(file)) {
            staging.delete()
            throw java.io.IOException("Failed to rename staged share export $fileName")
        }
    } catch (e: Exception) {
        staging.takeIf { it.exists() }?.delete()
        throw e
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

/** Convenience overload for the common case where the export
 *  content is a ready-baked UTF-8 string (every JSON / CSV
 *  bundle). Force UTF-8 — the default platform charset on a
 *  non-UTF-8 device would mangle non-ASCII content (Chinese /
 *  Cyrillic / emoji in agent or provider names). */
fun shareExportText(
    context: Context,
    fileName: String,
    mimeType: String,
    chooserTitle: String,
    content: String
) = shareExport(context, fileName, mimeType, chooserTitle) {
    it.write(content.toByteArray(Charsets.UTF_8))
}
