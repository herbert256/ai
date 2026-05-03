package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupManagerRestoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun clearFilesDirForRestore_removes_existing_files_before_restore() {
        val filesDir = tmp.newFolder("files")
        File(filesDir, "reports/old-report.json").apply {
            parentFile!!.mkdirs()
            writeText("stale report")
        }
        File(filesDir, "trace/old-trace.json").apply {
            parentFile!!.mkdirs()
            writeText("stale trace")
        }

        BackupManager.clearFilesDirForRestore(filesDir)

        assertThat(filesDir.exists()).isTrue()
        assertThat(filesDir.listFiles()?.toList()).isEmpty()
    }

    @Test fun clearFilesDirForRestore_creates_missing_files_dir() {
        val filesDir = File(tmp.root, "missing-files")

        BackupManager.clearFilesDirForRestore(filesDir)

        assertThat(filesDir.exists()).isTrue()
        assertThat(filesDir.isDirectory).isTrue()
    }

    @Test fun clearFilesDirForRestore_preserves_local_model_dirs() {
        val filesDir = tmp.newFolder("files")
        // User has on-device LLM + embedder model bundles installed; both excluded
        // from backup, so the restore wipe must keep them in place.
        File(filesDir, "local_llms/gemma-3-1b.task").apply {
            parentFile!!.mkdirs()
            writeText("multi-GB llm bundle stand-in")
        }
        File(filesDir, "local_models/embed.tflite").apply {
            parentFile!!.mkdirs()
            writeText("embedder bundle stand-in")
        }
        // Plus some real backed-up content that should still be wiped:
        File(filesDir, "reports/old.json").apply {
            parentFile!!.mkdirs()
            writeText("stale report")
        }

        BackupManager.clearFilesDirForRestore(filesDir)

        assertThat(File(filesDir, "local_llms/gemma-3-1b.task").exists()).isTrue()
        assertThat(File(filesDir, "local_models/embed.tflite").exists()).isTrue()
        assertThat(File(filesDir, "reports").exists()).isFalse()
    }
}
