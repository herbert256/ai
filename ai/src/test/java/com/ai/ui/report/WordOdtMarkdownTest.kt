package com.ai.ui.report

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WordOdtMarkdownTest {
    @Test fun mdToBlocks_maps_headings_bullets_code_and_plain_paragraphs() {
        val blocks = mdToBlocks(
            """
            # Main **Title**

            Paragraph with [link](https://example.com) and `code`.

            - First
            - Second

            ```json
            {"k":1}
            ```
            """.trimIndent(),
            headingBase = 2
        )

        assertThat(blocks.map { it.kind }).containsExactly(
            DocBlockKind.HEADING,
            DocBlockKind.PARAGRAPH,
            DocBlockKind.BULLET,
            DocBlockKind.BULLET,
            DocBlockKind.CODE
        ).inOrder()
        assertThat(blocks[0].text).isEqualTo("Main Title")
        assertThat(blocks[0].level).isEqualTo(2)
        assertThat(blocks[1].text).isEqualTo("Paragraph with link and code.")
        assertThat(blocks[2].text).isEqualTo("First")
        assertThat(blocks[4].text).isEqualTo("""{"k":1}""")
    }

    @Test fun mdToBlocks_clamps_heading_levels() {
        val blocks = mdToBlocks("### Deep", headingBase = 6)

        assertThat(blocks.single().kind).isEqualTo(DocBlockKind.HEADING)
        assertThat(blocks.single().level).isEqualTo(6)
    }
}
