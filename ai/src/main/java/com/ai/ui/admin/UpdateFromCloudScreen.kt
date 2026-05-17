package com.ai.ui.admin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS = "update_from_cloud"
private const val KEY_URI = "apk_uri"
private const val DRIVE_PKG = "com.google.android.apps.docs"

/**
 * Housekeeping → "Update from cloud" — install the app's latest
 * APK from a user-picked storage location (typically a Drive sync
 * folder). One-time setup picks the file via the system's Storage
 * Access Framework picker; every subsequent tap reads the latest
 * contents of that same file (Drive's DocumentsProvider re-fetches
 * the cloud copy on each read) and fires the system Install dialog.
 *
 * Why SAF and not the Drive REST API: the picker URI is stable
 * across edits of the underlying file, and Drive's local
 * DocumentsProvider already handles cloud refresh on read — no
 * OAuth, no Drive SDK, no extra dependencies. Works equally well
 * with any storage provider (local files, Dropbox if the user has
 * that, Drive, …).
 */
@Composable
fun UpdateFromCloudScreen(
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var apkUriString by remember { mutableStateOf(prefs.getString(KEY_URI, null)) }
    var lastStatus by remember { mutableStateOf<String?>(null) }
    // 5-second polling tick — drives the Source-file card to
    // re-query the DocumentsProvider so a freshly-synced APK
    // surfaces its new mtime without the user touching anything.
    var sourceTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            sourceTick++
        }
    }

    val driveInstalled = remember { isPackageInstalled(context, DRIVE_PKG) }
    val installedVersion = remember { thisAppVersion(context) }

    // SAF picker — persists the URI for the picked APK so subsequent
    // taps can re-read it without prompting again. Drive's
    // DocumentsProvider re-fetches the file on read, so a freshly
    // synced APK shows up on the next "Install" tap automatically.
    val pickApk = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist a read grant that survives process death.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't support persistable grants;
                // fall through — re-pick will be required next session.
            }
            val str = uri.toString()
            prefs.edit().putString(KEY_URI, str).apply()
            apkUriString = str
            lastStatus = "Source file set."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "update_from_cloud", title = "Update from cloud", onBackClick = onBack)

        // Primary action lives at the top so the user can install
        // without scrolling once the source file is picked. Repeats
        // the same enabled/onClick logic the old bottom button had —
        // the bottom row keeps only the "Pick / Change source" affordance.
        Button(
            onClick = {
                val uriStr = apkUriString
                if (uriStr == null) {
                    Toast.makeText(context, "Pick a source file first", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val ok = installFromUri(context, Uri.parse(uriStr))
                lastStatus = if (ok) "Update launched — confirm in the system dialog."
                             else "Couldn't read the source file — re-pick required."
            },
            enabled = apkUriString != null,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Update", maxLines = 1, softWrap = false) }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Install the app's latest APK from a file you've already synced to this phone " +
                    "(typically your Drive folder). Pick the file once; every Update tap installs " +
                    "whatever's currently there. The system Install dialog still appears for confirmation.",
                color = AppColors.TextSecondary, fontSize = 13.sp
            )

            // Drive status hint — only surfaced when Drive is MISSING.
            // The card is purely a "you might want this" nudge; once
            // Drive is on the device there's nothing actionable to
            // say, so we hide it to keep the screen tight.
            if (!driveInstalled) {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Google Drive app", color = AppColors.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "✗ not installed",
                            color = AppColors.Orange, fontSize = 13.sp
                        )
                        Text(
                            "Drive isn't required — the picker can browse any storage. " +
                                "Install Drive if you want to pull from a Drive-synced folder.",
                            color = AppColors.TextTertiary, fontSize = 11.sp
                        )
                    }
                }
            }

            // Currently-pointed-at source file. produceState would be
            // cleaner but the metadata is cheap — read on every recompose
            // (re-reads are <1 ms via ContentResolver.query).
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Source file", color = AppColors.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    val uriStr = apkUriString
                    if (uriStr == null) {
                        Text("(none picked yet)", color = AppColors.TextTertiary, fontSize = 13.sp)
                    } else {
                        val info = remember(uriStr, sourceTick) { queryDocumentInfo(context, Uri.parse(uriStr)) }
                        if (info == null) {
                            Text(
                                "(source no longer accessible — re-pick required)",
                                color = AppColors.Orange, fontSize = 13.sp
                            )
                        } else {
                            Text(info.displayName, color = Color.White, fontSize = 13.sp)
                            Text(
                                "${formatBytes(info.size)} · modified ${formatDate(info.lastModified)}",
                                color = AppColors.TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Installed version — compare against the source-file's
            // modified time to give the user a sense of whether
            // installing would change anything.
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Installed version", color = AppColors.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(installedVersion, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "built " + com.ai.ui.admin.readBundledBuildStamp(context),
                        color = AppColors.TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "installed " + com.ai.ui.admin.formatAppInstalledTime(context),
                        color = AppColors.TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }

            lastStatus?.let { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)) {
                    Text(
                        msg, color = AppColors.Green, fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        // Bottom action row — Pick / Change source only. The Install
        // CTA moved to the top of the screen so it's reachable
        // without scrolling.
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                // Filter MIME types — DocumentsProvider implementations
                // vary on what they report for APKs (some say
                // "application/vnd.android.package-archive", some
                // "application/octet-stream", Drive often says the
                // latter). Pass both + a wildcard fallback so the user
                // can always pick.
                pickApk.launch(arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream",
                    "*/*"
                ))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text(if (apkUriString == null) "Pick APK source…" else "Change source file…", maxLines = 1, softWrap = false) }
    }
}

private data class DocInfo(val displayName: String, val size: Long, val lastModified: Long)

/** Query a SAF URI for display name + size + last-modified. Returns
 *  null when the URI is no longer accessible (provider gone, file
 *  deleted, permission revoked). */
private fun queryDocumentInfo(context: Context, uri: Uri): DocInfo? {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            val mtimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            DocInfo(
                displayName = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "?" else "?",
                size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L,
                lastModified = if (mtimeIdx >= 0 && !cursor.isNull(mtimeIdx)) cursor.getLong(mtimeIdx) else 0L
            )
        }
    } catch (_: Exception) { null }
}

/** Read the APK bytes from [sourceUri] into our cache dir, then fire
 *  the system PackageInstaller. Returns false when the source can't
 *  be opened (typical cause: persisted URI no longer valid). The
 *  system Install dialog handles the rest. */
private fun installFromUri(context: Context, sourceUri: Uri): Boolean {
    val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
    val cacheFile = File(updatesDir, "update.apk")
    try {
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return false
    } catch (_: Exception) {
        return false
    }
    val apkUri: Uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
    } catch (_: Exception) {
        return false
    }
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    return try {
        context.startActivity(installIntent)
        true
    } catch (_: Exception) {
        false
    }
}

private fun isPackageInstalled(context: Context, pkg: String): Boolean = try {
    context.packageManager.getPackageInfo(pkg, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

private fun thisAppVersion(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.versionName ?: "?"
} catch (_: Exception) { "?" }

private fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "? B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

private fun formatDate(epochMillis: Long): String =
    if (epochMillis <= 0) "?"
    else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))
