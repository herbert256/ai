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
}
