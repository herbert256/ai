package com.ai.util

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/**
 * `@ClassRule` that snapshots the app's persistent state before any
 * test in the class runs and restores it afterwards. Use on any
 * instrumented test class whose `@Before` / `@After` mutates
 * persistent state (deleteAllReports, clearTraces, resetToDefaults,
 * setAllManualPricing, etc.) — the test APK runs in the production
 * app's UID and shares its data directory, so without this guard
 * those wipes destroy real user data on the test emulator.
 *
 * Captures both SharedPreferences XML files and the relevant
 * filesDir subdirs. The snapshot lives under `cacheDir`, which
 * Android may evict but never user content.
 *
 * Usage:
 * ```
 * @RunWith(AndroidJUnit4::class)
 * class MyDestructiveTest {
 *     companion object {
 *         @ClassRule @JvmField val stateGuard = PersistentStateGuard()
 *     }
 *     @Before fun reset() { ReportStorage.deleteAllReports(context) }
 *     // …
 * }
 * ```
 */
class PersistentStateGuard : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val backup = File(ctx.cacheDir, "persistent-state-guard-${System.nanoTime()}")
            backup.deleteRecursively()
            try {
                snapshot(ctx, backup)
                base.evaluate()
            } finally {
                if (backup.exists()) {
                    runCatching { restore(ctx, backup) }
                    backup.deleteRecursively()
                }
            }
        }
    }

    private fun snapshot(ctx: Context, backup: File) {
        backup.mkdirs()
        val prefsBackup = File(backup, "shared_prefs").apply { mkdirs() }
        val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        for (name in PREFS_FILES) {
            val src = File(prefsDir, name)
            if (src.exists()) src.copyTo(File(prefsBackup, name), overwrite = true)
        }
        val filesBackup = File(backup, "files").apply { mkdirs() }
        for (sub in FILES_SUBDIRS) {
            val src = File(ctx.filesDir, sub)
            if (src.exists()) src.copyRecursively(File(filesBackup, sub), overwrite = true)
        }
    }

    private fun restore(ctx: Context, backup: File) {
        val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        val prefsBackup = File(backup, "shared_prefs")
        for (name in PREFS_FILES) {
            val tgt = File(prefsDir, name)
            val src = File(prefsBackup, name)
            if (src.exists()) src.copyTo(tgt, overwrite = true)
            else tgt.delete()
        }
        for (sub in FILES_SUBDIRS) {
            val tgt = File(ctx.filesDir, sub)
            tgt.deleteRecursively()
            val src = File(backup, "files/$sub")
            if (src.exists()) src.copyRecursively(tgt, overwrite = true)
        }
    }

    companion object {
        // Every SharedPreferences file the app writes — kept in sync with
        // SettingsPreferences.PREFS_NAME and ProviderRegistry.PREFS_NAME.
        private val PREFS_FILES = listOf("eval_prefs.xml", "provider_registry.xml")

        // Every persisted-data subdir under filesDir. Add to this when a
        // new on-disk store is introduced.
        private val FILES_SUBDIRS = listOf(
            "reports", "chat-history", "trace", "secondary",
            "datastore", "prompt_cache"
        )
    }
}
