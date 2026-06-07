package com.ai.ui.helpers

import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [translationRunGroupingId] — keeps a group of TRANSLATE rows under one key:
 *  the runId when present, else a per-language synthetic id for legacy rows. */
class TranslationGroupingTest {
    private fun sr(runId: String?, language: String?) = SecondaryResult(
        id = "i", reportId = "r", kind = SecondaryKind.TRANSLATE,
        providerId = "p", model = "m", agentName = "a", timestamp = 0, content = null,
        translationRunId = runId, targetLanguage = language
    )

    @Test fun uses_the_run_id_when_present() {
        assertThat(translationRunGroupingId(sr(runId = "run-1", language = "French"))).isEqualTo("run-1")
    }

    @Test fun falls_back_to_language_for_legacy_rows() {
        assertThat(translationRunGroupingId(sr(runId = null, language = "German"))).isEqualTo("lang:German")
    }

    @Test fun falls_back_to_empty_language_when_both_missing() {
        assertThat(translationRunGroupingId(sr(runId = null, language = null))).isEqualTo("lang:")
    }
}
