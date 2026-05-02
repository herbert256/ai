package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmbeddingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun cache_key_includes_report_content() {
        val filesDir = tmp.newFolder("files")
        val originalVector = listOf(0.1, 0.2, 0.3)
        val editedVector = listOf(0.4, 0.5, 0.6)

        EmbeddingsStore.put(filesDir, "report-1", "OPENAI", "text-embedding-3-small", "original content", originalVector)

        assertThat(EmbeddingsStore.get(filesDir, "report-1", "OPENAI", "text-embedding-3-small", "original content"))
            .isEqualTo(originalVector)
        assertThat(EmbeddingsStore.get(filesDir, "report-1", "OPENAI", "text-embedding-3-small", "edited content"))
            .isNull()

        EmbeddingsStore.put(filesDir, "report-1", "OPENAI", "text-embedding-3-small", "edited content", editedVector)

        assertThat(EmbeddingsStore.get(filesDir, "report-1", "OPENAI", "text-embedding-3-small", "original content"))
            .isEqualTo(originalVector)
        assertThat(EmbeddingsStore.get(filesDir, "report-1", "OPENAI", "text-embedding-3-small", "edited content"))
            .isEqualTo(editedVector)
    }
}
