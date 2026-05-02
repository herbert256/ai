package com.ai.ui.admin

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TraceSharePathTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun sharedTraceCacheFile_uses_fileprovider_shared_traces_root() {
        val file = sharedTraceCacheFile(tmp.root, "trace.json")

        assertThat(file.name).isEqualTo("trace.json")
        assertThat(file.parentFile!!.name).isEqualTo("shared_traces")
        assertThat(file.parentFile!!.parentFile).isEqualTo(tmp.root)
        assertThat(file.parentFile!!.exists()).isTrue()
    }
}
