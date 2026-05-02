package com.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-trip coverage for [SecondaryResultStorage] using a real
 * Context. Per-report sub-directories under filesDir/secondary/.
 */
@RunWith(AndroidJUnit4::class)
class SecondaryResultStorageInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val reportA = "report-a-id"
    private val reportB = "report-b-id"

    @Before fun cleanState() {
        SecondaryResultStorage.init(context)
        SecondaryResultStorage.deleteAllForReport(context, reportA)
        SecondaryResultStorage.deleteAllForReport(context, reportB)
    }

    @Test fun create_then_save_persists_content_for_get() {
        val placeholder = SecondaryResultStorage.create(
            context, reportA, SecondaryKind.SUMMARIZE, "UNIT", "m1", "Provider / m1"
        )
        assertThat(placeholder.content).isNull()

        SecondaryResultStorage.save(
            context,
            placeholder.copy(content = "summary text", durationMs = 1234)
        )

        val reloaded = SecondaryResultStorage.get(context, reportA, placeholder.id)
        assertThat(reloaded).isNotNull()
        assertThat(reloaded!!.content).isEqualTo("summary text")
        assertThat(reloaded.durationMs).isEqualTo(1234)
        assertThat(reloaded.kind).isEqualTo(SecondaryKind.SUMMARIZE)
    }

    @Test fun listForReport_returns_only_that_reports_results() {
        SecondaryResultStorage.create(context, reportA, SecondaryKind.SUMMARIZE, "UNIT", "m", "n1")
        SecondaryResultStorage.create(context, reportA, SecondaryKind.COMPARE, "UNIT", "m", "n2")
        SecondaryResultStorage.create(context, reportB, SecondaryKind.SUMMARIZE, "UNIT", "m", "n3")

        val a = SecondaryResultStorage.listForReport(context, reportA).map { it.agentName }.toSet()
        val b = SecondaryResultStorage.listForReport(context, reportB).map { it.agentName }.toSet()
        assertThat(a).containsExactly("n1", "n2")
        assertThat(b).containsExactly("n3")
    }

    @Test fun listForReport_filters_by_kind_when_provided() {
        SecondaryResultStorage.create(context, reportA, SecondaryKind.SUMMARIZE, "UNIT", "m", "s")
        SecondaryResultStorage.create(context, reportA, SecondaryKind.COMPARE, "UNIT", "m", "c")
        val onlySummaries = SecondaryResultStorage.listForReport(context, reportA, SecondaryKind.SUMMARIZE)
        assertThat(onlySummaries).hasSize(1)
        assertThat(onlySummaries[0].kind).isEqualTo(SecondaryKind.SUMMARIZE)
    }

    @Test fun delete_removes_only_target_id() {
        val a = SecondaryResultStorage.create(context, reportA, SecondaryKind.SUMMARIZE, "UNIT", "m", "x")
        val b = SecondaryResultStorage.create(context, reportA, SecondaryKind.SUMMARIZE, "UNIT", "m", "y")
        SecondaryResultStorage.delete(context, reportA, a.id)
        val remaining = SecondaryResultStorage.listForReport(context, reportA).map { it.id }
        assertThat(remaining).containsExactly(b.id)
    }

    @Test fun deleteAllForReport_clears_directory() {
        SecondaryResultStorage.create(context, reportA, SecondaryKind.SUMMARIZE, "UNIT", "m", "x")
        SecondaryResultStorage.create(context, reportA, SecondaryKind.COMPARE, "UNIT", "m", "y")
        SecondaryResultStorage.deleteAllForReport(context, reportA)
        assertThat(SecondaryResultStorage.listForReport(context, reportA)).isEmpty()
    }

    @Test fun translateSourceKind_targetId_round_trip_through_save() {
        val placeholder = SecondaryResultStorage.create(
            context, reportA, SecondaryKind.TRANSLATE, "UNIT", "m", "Translate: prompt"
        )
        SecondaryResultStorage.save(
            context,
            placeholder.copy(
                content = "vertaling",
                translateSourceKind = "PROMPT",
                translateSourceTargetId = "prompt"
            )
        )
        val reloaded = SecondaryResultStorage.get(context, reportA, placeholder.id)!!
        assertThat(reloaded.translateSourceKind).isEqualTo("PROMPT")
        assertThat(reloaded.translateSourceTargetId).isEqualTo("prompt")
    }

    @Test fun translatedFromSecondaryId_round_trip() {
        val source = SecondaryResultStorage.create(
            context, reportA, SecondaryKind.SUMMARIZE, "UNIT", "m", "src"
        )
        SecondaryResultStorage.save(
            context,
            source.copy(content = "src content", translatedFromSecondaryId = "src-id-from-translation")
        )
        val reloaded = SecondaryResultStorage.get(context, reportA, source.id)!!
        assertThat(reloaded.translatedFromSecondaryId).isEqualTo("src-id-from-translation")
    }
}
