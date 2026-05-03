package com.ai.ui.report

internal enum class TableAlign { LEFT, CENTER, RIGHT }

internal data class MarkdownTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val alignments: List<TableAlign>
)

private val SEPARATOR_REGEX = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?\\s*$")
// Plain alphanumeric token — unlikely to appear in real model output
// and survives every regex pass the markdown converters run (no rule
// matches MDTBL or the digits-only suffix).
internal val MD_TABLE_PLACEHOLDER_REGEX = Regex("MDTBL(\\d+)")
private fun placeholderFor(idx: Int) = "MDTBL$idx"

/** Detect GFM tables in [markdown], replace each with a placeholder
 *  token, and return the modified text alongside the parsed tables.
 *  Callers (the in-app renderer and the HTML/PDF exporters) substitute
 *  back in their own format afterwards. */
internal fun parseGfmTables(markdown: String): Pair<String, List<MarkdownTable>> {
    val lines = markdown.split("\n")
    val out = StringBuilder()
    val tables = mutableListOf<MarkdownTable>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val isHeader = line.trimStart().startsWith("|") && line.indexOf('|', startIndex = line.indexOf('|') + 1) > 0
        val hasSeparator = i + 1 < lines.size && SEPARATOR_REGEX.matches(lines[i + 1])
        if (isHeader && hasSeparator) {
            val headers = splitTableRow(line)
            val alignments = parseAlignments(lines[i + 1])
            val padded = alignments + List(maxOf(0, headers.size - alignments.size)) { TableAlign.LEFT }
            i += 2
            val bodyRows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                bodyRows.add(splitTableRow(lines[i]))
                i++
            }
            tables.add(MarkdownTable(headers, bodyRows, padded.take(headers.size)))
            // Sandwich the placeholder in blank lines so the surrounding
            // markdown still flows as paragraphs and the placeholder
            // line itself doesn't get glued to a neighbour by the
            // \n→<br> step.
            out.append("\n\n").append(placeholderFor(tables.size - 1)).append("\n\n")
            continue
        }
        out.append(line).append("\n")
        i++
    }
    return out.toString() to tables
}

private fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    return s.split("|").map { it.trim() }
}

private fun parseAlignments(separator: String): List<TableAlign> = splitTableRow(separator).map { spec ->
    val left = spec.startsWith(":")
    val right = spec.endsWith(":")
    when {
        left && right -> TableAlign.CENTER
        right -> TableAlign.RIGHT
        else -> TableAlign.LEFT
    }
}

/** Strip the simplest inline markdown markers so cell text reads
 *  cleanly inside the in-app Compose table (which doesn't run the cells
 *  through the full markdown→HTML pipeline). The HTML exporter does its
 *  own escaping/inline pass via [buildExportTableHtml]. */
internal fun stripInlineMarkdown(s: String): String =
    s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")

/** Build `<table class='md-table'>...</table>` HTML for [t]. Used by
 *  the export pipeline once placeholders are ready to be substituted. */
internal fun buildExportTableHtml(t: MarkdownTable): String {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    fun alignAttr(a: TableAlign?) = when (a) {
        TableAlign.CENTER -> " style='text-align:center'"
        TableAlign.RIGHT -> " style='text-align:right'"
        else -> ""
    }
    fun renderCell(s: String): String = esc(s)
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "<em>$1</em>")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
    val sb = StringBuilder("<table class='md-table'><thead><tr>")
    for ((i, h) in t.headers.withIndex()) sb.append("<th").append(alignAttr(t.alignments.getOrNull(i))).append('>').append(renderCell(h)).append("</th>")
    sb.append("</tr></thead><tbody>")
    for (row in t.rows) {
        sb.append("<tr>")
        for ((i, cell) in row.withIndex()) sb.append("<td").append(alignAttr(t.alignments.getOrNull(i))).append('>').append(renderCell(cell)).append("</td>")
        sb.append("</tr>")
    }
    sb.append("</tbody></table>")
    return sb.toString()
}
