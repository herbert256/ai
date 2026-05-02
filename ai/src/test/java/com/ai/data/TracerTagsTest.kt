package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Coverage for [withTracerTags] and [withTraceCategory] — the push/pop
 * helpers used to bracket every API-call flow's (reportId, category)
 * tag pair on [ApiTracer]. They replaced a bare-null-restore pattern
 * that clobbered enclosing flows; these tests exercise the
 * save/restore behaviour they're supposed to guarantee.
 */
class TracerTagsTest {
    @Before fun resetTags() {
        ApiTracer.currentReportId = null
        ApiTracer.currentCategory = null
    }
    @After fun clearTags() {
        ApiTracer.currentReportId = null
        ApiTracer.currentCategory = null
    }

    @Test fun withTracerTags_sets_both_inside_block_and_restores_on_exit() {
        ApiTracer.currentReportId = "outer-id"
        ApiTracer.currentCategory = "outer-cat"

        var observedId: String? = null
        var observedCat: String? = null
        withTracerTags(reportId = "inner-id", category = "inner-cat") {
            observedId = ApiTracer.currentReportId
            observedCat = ApiTracer.currentCategory
        }

        assertThat(observedId).isEqualTo("inner-id")
        assertThat(observedCat).isEqualTo("inner-cat")
        assertThat(ApiTracer.currentReportId).isEqualTo("outer-id")
        assertThat(ApiTracer.currentCategory).isEqualTo("outer-cat")
    }

    @Test fun withTracerTags_null_arg_leaves_that_side_untouched() {
        ApiTracer.currentReportId = "outer-id"
        ApiTracer.currentCategory = "outer-cat"

        var observedId: String? = null
        var observedCat: String? = null
        // Only category is set; reportId arg is null → leave outer.
        withTracerTags(category = "inner-cat") {
            observedId = ApiTracer.currentReportId
            observedCat = ApiTracer.currentCategory
        }

        assertThat(observedId).isEqualTo("outer-id")
        assertThat(observedCat).isEqualTo("inner-cat")
        assertThat(ApiTracer.currentReportId).isEqualTo("outer-id")
        assertThat(ApiTracer.currentCategory).isEqualTo("outer-cat")
    }

    @Test fun withTracerTags_nesting_restores_intermediate_values() {
        val seen = mutableListOf<Pair<String?, String?>>()
        withTracerTags(reportId = "a-id", category = "a-cat") {
            seen += ApiTracer.currentReportId to ApiTracer.currentCategory
            withTracerTags(reportId = "b-id", category = "b-cat") {
                seen += ApiTracer.currentReportId to ApiTracer.currentCategory
                withTracerTags(category = "c-cat") {
                    seen += ApiTracer.currentReportId to ApiTracer.currentCategory
                }
                seen += ApiTracer.currentReportId to ApiTracer.currentCategory
            }
            seen += ApiTracer.currentReportId to ApiTracer.currentCategory
        }
        seen += ApiTracer.currentReportId to ApiTracer.currentCategory

        assertThat(seen).containsExactly(
            "a-id" to "a-cat",
            "b-id" to "b-cat",
            "b-id" to "c-cat",   // c only set category
            "b-id" to "b-cat",   // restored after c exits
            "a-id" to "a-cat",   // restored after b exits
            null to null         // restored after a exits (outer null/null)
        ).inOrder()
    }

    @Test fun withTracerTags_restores_even_when_block_throws() {
        ApiTracer.currentReportId = "outer"
        ApiTracer.currentCategory = "outer-cat"

        val thrown = runCatching {
            withTracerTags(reportId = "inner", category = "inner-cat") {
                check(ApiTracer.currentReportId == "inner") { "tag visible mid-block" }
                throw IllegalStateException("boom")
            }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(ApiTracer.currentReportId).isEqualTo("outer")
        assertThat(ApiTracer.currentCategory).isEqualTo("outer-cat")
    }

    @Test fun withTracerTags_returns_block_value() {
        val result = withTracerTags(reportId = "x", category = "y") { 42 }
        assertThat(result).isEqualTo(42)
    }

    @Test fun withTracerTags_no_args_is_a_pure_save_restore() {
        ApiTracer.currentReportId = "before"
        ApiTracer.currentCategory = "before-cat"

        withTracerTags {
            // Block can mutate — exit must still restore the saved values.
            ApiTracer.currentReportId = "mutated-inside"
            ApiTracer.currentCategory = "mutated-cat"
        }

        assertThat(ApiTracer.currentReportId).isEqualTo("before")
        assertThat(ApiTracer.currentCategory).isEqualTo("before-cat")
    }

    @Test fun withTraceCategory_only_touches_category() {
        ApiTracer.currentReportId = "rid"
        ApiTracer.currentCategory = "before"

        var observedId: String? = null
        var observedCat: String? = null
        withTraceCategory("inner") {
            observedId = ApiTracer.currentReportId
            observedCat = ApiTracer.currentCategory
        }

        assertThat(observedId).isEqualTo("rid")
        assertThat(observedCat).isEqualTo("inner")
        assertThat(ApiTracer.currentReportId).isEqualTo("rid")
        assertThat(ApiTracer.currentCategory).isEqualTo("before")
    }

    @Test fun withTraceCategory_restores_on_throw() {
        ApiTracer.currentCategory = "outer"

        runCatching {
            withTraceCategory("inner") { throw RuntimeException("nope") }
        }

        assertThat(ApiTracer.currentCategory).isEqualTo("outer")
    }
}
