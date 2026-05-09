package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for [withTracerTags] and [withTraceCategory] — the push/pop
 * helpers used to bracket every API-call flow's (reportId, category)
 * tag pair on [ApiTracer]. They replaced a bare-null-restore pattern
 * that clobbered enclosing flows; these tests exercise the
 * save/restore behaviour they're supposed to guarantee.
 *
 * Tags are now backed by a ThreadLocal (no public setter), so initial
 * "outer" state is established with an outer withTracerTags call
 * instead of mutating ApiTracer directly.
 */
class TracerTagsTest {

    @Test fun withTracerTags_sets_both_inside_block_and_restores_on_exit() {
        var observedId: String? = null
        var observedCat: String? = null
        var afterInnerId: String? = null
        var afterInnerCat: String? = null
        withTracerTags(reportId = "outer-id", category = "outer-cat") {
            withTracerTags(reportId = "inner-id", category = "inner-cat") {
                observedId = ApiTracer.currentReportId
                observedCat = ApiTracer.currentCategory
            }
            afterInnerId = ApiTracer.currentReportId
            afterInnerCat = ApiTracer.currentCategory
        }

        assertThat(observedId).isEqualTo("inner-id")
        assertThat(observedCat).isEqualTo("inner-cat")
        assertThat(afterInnerId).isEqualTo("outer-id")
        assertThat(afterInnerCat).isEqualTo("outer-cat")
    }

    @Test fun withTracerTags_null_arg_leaves_that_side_untouched() {
        var observedId: String? = null
        var observedCat: String? = null
        withTracerTags(reportId = "outer-id", category = "outer-cat") {
            // Only category is set; reportId arg is null → leave outer.
            withTracerTags(category = "inner-cat") {
                observedId = ApiTracer.currentReportId
                observedCat = ApiTracer.currentCategory
            }
        }

        assertThat(observedId).isEqualTo("outer-id")
        assertThat(observedCat).isEqualTo("inner-cat")
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
        var afterId: String? = null
        var afterCat: String? = null
        withTracerTags(reportId = "outer", category = "outer-cat") {
            val thrown = runCatching {
                withTracerTags(reportId = "inner", category = "inner-cat") {
                    check(ApiTracer.currentReportId == "inner") { "tag visible mid-block" }
                    throw IllegalStateException("boom")
                }
            }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
            afterId = ApiTracer.currentReportId
            afterCat = ApiTracer.currentCategory
        }

        assertThat(afterId).isEqualTo("outer")
        assertThat(afterCat).isEqualTo("outer-cat")
    }

    @Test fun withTracerTags_returns_block_value() {
        val result = withTracerTags(reportId = "x", category = "y") { 42 }
        assertThat(result).isEqualTo(42)
    }

    @Test fun withTraceCategory_only_touches_category() {
        var observedId: String? = null
        var observedCat: String? = null
        var afterCat: String? = null
        withTracerTags(reportId = "rid", category = "before") {
            withTraceCategory("inner") {
                observedId = ApiTracer.currentReportId
                observedCat = ApiTracer.currentCategory
            }
            afterCat = ApiTracer.currentCategory
        }

        assertThat(observedId).isEqualTo("rid")
        assertThat(observedCat).isEqualTo("inner")
        assertThat(afterCat).isEqualTo("before")
    }

    @Test fun withTraceCategory_restores_on_throw() {
        var afterCat: String? = null
        withTracerTags(category = "outer") {
            runCatching {
                withTraceCategory("inner") { throw RuntimeException("nope") }
            }
            afterCat = ApiTracer.currentCategory
        }

        assertThat(afterCat).isEqualTo("outer")
    }
}
