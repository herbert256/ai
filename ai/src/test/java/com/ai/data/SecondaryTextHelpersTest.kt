package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [stripMetaReferenceLegend], [secondaryPromptDisplayName] and
 *  [resolveSecondaryPrompt] — pure string helpers in the secondary layer. */
class SecondaryTextHelpersTest {

    // ---- stripMetaReferenceLegend ----

    @Test fun strips_a_reference_legend() {
        val content = "Body text here.\n\n---\n\n## References\n\n[1] = OpenAI / gpt-4o"
        assertThat(stripMetaReferenceLegend(content)).isEqualTo("Body text here.")
    }

    @Test fun returns_input_unchanged_without_a_legend() {
        assertThat(stripMetaReferenceLegend("Just a normal answer.")).isEqualTo("Just a normal answer.")
    }

    @Test fun strips_from_the_last_legend_marker() {
        val content = "A\n\n---\n\n## References\n\nx\n\n---\n\n## References\n\ny"
        assertThat(stripMetaReferenceLegend(content)).isEqualTo("A\n\n---\n\n## References\n\nx")
    }

    @Test fun trims_trailing_whitespace_before_the_legend() {
        val content = "Body.   \n\n---\n\n## References\n\n[1] = x"
        assertThat(stripMetaReferenceLegend(content)).isEqualTo("Body.")
    }

    // ---- secondaryPromptDisplayName ----

    @Test fun maps_known_internal_prompt_names() {
        assertThat(secondaryPromptDisplayName("second-rerank")).isEqualTo("rerank")
        assertThat(secondaryPromptDisplayName("second-moderation")).isEqualTo("moderation")
        assertThat(secondaryPromptDisplayName("second-tournament")).isEqualTo("tournament")
    }

    @Test fun passes_through_unknown_names() {
        assertThat(secondaryPromptDisplayName("equivalent")).isEqualTo("equivalent")
        assertThat(secondaryPromptDisplayName("")).isEqualTo("")
    }

    // ---- resolveSecondaryPrompt ----

    @Test fun substitutes_all_simple_placeholders() {
        val out = resolveSecondaryPrompt(
            "Q=@QUESTION@ R=@RESULTS@ N=@COUNT@ T=@TITLE@", "what", "res", 3, "Title"
        )
        assertThat(out).isEqualTo("Q=what R=res N=3 T=Title")
    }

    @Test fun null_title_becomes_empty_string() {
        assertThat(resolveSecondaryPrompt("[@TITLE@]", "q", "", 1, null)).isEqualTo("[]")
    }

    @Test fun date_placeholder_is_replaced() {
        val out = resolveSecondaryPrompt("d=@DATE@", "q", "", 1)
        assertThat(out).doesNotContain("@DATE@")
        assertThat(out).startsWith("d=")
    }

    @Test fun repeated_placeholder_replaced_every_time() {
        assertThat(resolveSecondaryPrompt("@COUNT@-@COUNT@", "q", "", 5)).isEqualTo("5-5")
    }

    @Test fun text_without_placeholders_is_untouched() {
        assertThat(resolveSecondaryPrompt("no placeholders", "q", "r", 1)).isEqualTo("no placeholders")
    }
}
