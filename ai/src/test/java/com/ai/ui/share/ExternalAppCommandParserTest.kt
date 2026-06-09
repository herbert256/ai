package com.ai.ui.share

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [ExternalAppCommandParser] (audit U14 / T02) — the pure parser
 * extracted from AppNavHost's inline ACTION_NEW_REPORT handling. Covers route
 * selection (bare prompt → Prefill vs instruction-bearing → Confirm), the two
 * ways instructions arrive (explicit extra vs the `-- end prompt --` marker),
 * single/multi tag extraction, and the boolean flags.
 */
class ExternalAppCommandParserTest {

    private fun confirm(cmd: ExternalReportCommand): PendingExternalReport {
        assertThat(cmd).isInstanceOf(ExternalReportCommand.Confirm::class.java)
        return (cmd as ExternalReportCommand.Confirm).staged
    }

    // ---- Route selection ------------------------------------------------

    @Test
    fun barePrompt_noInstructions_noMarker_prefills_untrimmed() {
        val cmd = ExternalAppCommandParser.parse(
            prompt = "  Summarize this  ",
            instructions = null,
            title = "My title",
            systemPrompt = null
        )
        assertThat(cmd).isEqualTo(ExternalReportCommand.Prefill("My title", "  Summarize this  "))
    }

    @Test
    fun barePrompt_nullTitle_prefillsWithEmptyTitle() {
        val cmd = ExternalAppCommandParser.parse("Hello", null, null, null)
        assertThat(cmd).isEqualTo(ExternalReportCommand.Prefill("", "Hello"))
    }

    @Test
    fun marker_splitsPromptFromInstructions_andConfirms() {
        val cmd = ExternalAppCommandParser.parse(
            prompt = "Write a poem\n-- end prompt --\n<type>poem</type>",
            instructions = null,
            title = "T",
            systemPrompt = null
        )
        val staged = confirm(cmd)
        assertThat(staged.aiPrompt).isEqualTo("Write a poem")
        assertThat(staged.reportType).isEqualTo("poem")
    }

    @Test
    fun explicitInstructions_trimsPrompt_andTakesPrecedenceOverMarker() {
        // Marker is present in the prompt, but an explicit instructions extra
        // wins: the whole prompt (trimmed) is the AI prompt, not split.
        val cmd = ExternalAppCommandParser.parse(
            prompt = "  Body -- end prompt -- still body  ",
            instructions = "<type>note</type>",
            title = null,
            systemPrompt = "be terse"
        )
        val staged = confirm(cmd)
        assertThat(staged.aiPrompt).isEqualTo("Body -- end prompt -- still body")
        assertThat(staged.systemPrompt).isEqualTo("be terse")
        assertThat(staged.reportType).isEqualTo("note")
    }

    // ---- Tag extraction -------------------------------------------------

    @Test
    fun extractsSingleValueTags() {
        val instr = "<open>intro</open><close>outro</close><type>brief</type>" +
            "<email>a@b.com</email><next>share</next>"
        val staged = confirm(ExternalAppCommandParser.parse("p", instr, null, null))
        assertThat(staged.openHtml).isEqualTo("intro")
        assertThat(staged.closeHtml).isEqualTo("outro")
        assertThat(staged.reportType).isEqualTo("brief")
        assertThat(staged.email).isEqualTo("a@b.com")
        assertThat(staged.nextAction).isEqualTo("share")
    }

    @Test
    fun missingSingleTags_areNull() {
        val staged = confirm(ExternalAppCommandParser.parse("p", "<type>x</type>", null, null))
        assertThat(staged.openHtml).isNull()
        assertThat(staged.email).isNull()
        assertThat(staged.nextAction).isNull()
    }

    @Test
    fun extractsMultiValueTags_inOrder_droppingEmpties() {
        val instr = "<agent>Alice</agent><agent></agent><agent>Bob</agent>" +
            "<flock>F1</flock><swarm>S1</swarm><model>openai/gpt-4o</model><model>x/y</model>"
        val staged = confirm(ExternalAppCommandParser.parse("p", instr, null, null))
        assertThat(staged.agentNames).containsExactly("Alice", "Bob").inOrder()
        assertThat(staged.flockNames).containsExactly("F1")
        assertThat(staged.swarmNames).containsExactly("S1")
        assertThat(staged.modelSpecs).containsExactly("openai/gpt-4o", "x/y").inOrder()
    }

    @Test
    fun tagBodies_spanningNewlines_areMatchedAndTrimmed() {
        val staged = confirm(ExternalAppCommandParser.parse("p", "<open>\n  hi\n</open>", null, null))
        assertThat(staged.openHtml).isEqualTo("hi")
    }

    // ---- Boolean flags --------------------------------------------------

    @Test
    fun booleanFlags_detectPresence_caseInsensitively() {
        val staged = confirm(ExternalAppCommandParser.parse("p", "<RETURN><Edit><select>", null, null))
        assertThat(staged.hasReturn).isTrue()
        assertThat(staged.hasEdit).isTrue()
        assertThat(staged.hasSelect).isTrue()
    }

    @Test
    fun booleanFlags_absent_areFalse() {
        val staged = confirm(ExternalAppCommandParser.parse("p", "<type>x</type>", null, null))
        assertThat(staged.hasReturn).isFalse()
        assertThat(staged.hasEdit).isFalse()
        assertThat(staged.hasSelect).isFalse()
    }

    // ---- Derived willAutoGenerate predicate -----------------------------

    @Test
    fun willAutoGenerate_whenTypedWithWorkersAndNoEditOrSelect() {
        val staged = confirm(ExternalAppCommandParser.parse("p", "<type>brief</type><agent>Alice</agent>", null, null))
        assertThat(staged.willAutoGenerate).isTrue()
    }

    @Test
    fun willNotAutoGenerate_whenEditRequested() {
        val staged = confirm(ExternalAppCommandParser.parse("p", "<type>brief</type><agent>Alice</agent><edit>", null, null))
        assertThat(staged.willAutoGenerate).isFalse()
    }

    @Test
    fun willNotAutoGenerate_withoutAnyWorkers() {
        val staged = confirm(ExternalAppCommandParser.parse("p", "<type>brief</type>", null, null))
        assertThat(staged.willAutoGenerate).isFalse()
    }
}
