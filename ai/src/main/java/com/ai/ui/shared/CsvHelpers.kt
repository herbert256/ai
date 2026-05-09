package com.ai.ui.shared

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** `yyMMdd-HHmm` filename-safe timestamp shared by every export
 *  launcher that wants a per-file suffix. */
internal fun exportTimestamp(): String =
    SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())

/** RFC-4180 minimal CSV field escape: wrap in double quotes when the
 *  value contains comma / quote / newline; double up any embedded
 *  quotes. Without this, a model id containing a comma breaks the
 *  row layout and the importer's split-on-`,` silently corrupts the
 *  user's overrides. Pairs with [parseCsvRow]. */
internal fun csvField(value: String): String {
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
    return value
}

/** RFC-4180 minimal CSV row parser. Pairs with [csvField]: a field is
 *  unquoted unless it starts with `"`, in which case the closing `"`
 *  ends it and `""` decodes to a single `"`. The naive
 *  `String.split(",")` it replaces silently mangled any row containing
 *  a quoted-and-comma'd id, which was the exact case [csvField] was
 *  added to handle. Multiline values are not supported — the caller
 *  already splits the file on `\n`, and no real-world provider/model
 *  id contains a newline. */
internal fun parseCsvRow(line: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (inQuotes) {
            when {
                c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = false
                else -> sb.append(c)
            }
        } else {
            when {
                c == ',' -> { out.add(sb.toString()); sb.clear() }
                c == '"' && sb.isEmpty() -> inQuotes = true
                else -> sb.append(c)
            }
        }
        i++
    }
    out.add(sb.toString())
    return out
}
