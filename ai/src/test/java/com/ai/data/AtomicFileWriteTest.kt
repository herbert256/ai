package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicFileWriteTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun write_creates_destination_with_content() {
        val dest = File(tmp.root, "out.json")
        val ok = dest.writeTextAtomic("{\"k\":1}")
        assertThat(ok).isTrue()
        assertThat(dest.readText()).isEqualTo("{\"k\":1}")
    }

    @Test fun write_leaves_no_tmp_file_on_success() {
        val dest = File(tmp.root, "out.json")
        dest.writeTextAtomic("hello")
        val leftovers = tmp.root.listFiles { _, name -> name.endsWith(".tmp") } ?: emptyArray()
        assertThat(leftovers).isEmpty()
    }

    @Test fun write_replaces_existing_file() {
        val dest = File(tmp.root, "out.json").apply { writeText("old") }
        dest.writeTextAtomic("new")
        assertThat(dest.readText()).isEqualTo("new")
    }

    @Test fun write_to_nonexistent_parent_fails_cleanly() {
        val dest = File(tmp.root, "missing-dir/out.json")
        val ok = dest.writeTextAtomic("payload")
        assertThat(ok).isFalse()
        assertThat(dest.exists()).isFalse()
    }
}
