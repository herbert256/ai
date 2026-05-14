package com.ai.data

import java.text.BreakIterator

/**
 * Pull the first emoji grapheme cluster out of an LLM response.
 * Used by the icon-prompt and report_icon-prompt dispatchers so the
 * saved value is always exactly one glyph — no matter how chatty
 * the model is around it.
 *
 * Why grapheme clusters: many emojis are multi-codepoint sequences
 * (ZWJ family emojis, regional-indicator flag pairs, skin-tone
 * modifiers). A naive `take(2)` slices them mid-sequence. The
 * platform [BreakIterator] knows the boundary rules; we scan the
 * clusters in order and return the first one whose lead codepoint
 * sits in a known emoji block.
 *
 * The codepoint check covers the bulk of blocks LLMs actually
 * return — Miscellaneous Symbols / Pictographs, Transport, Food,
 * Hand gestures, Supplemental Symbols, Dingbats, Misc Technical
 * (⌚⏳⌛), Enclosed Alphanumeric Supplement, Regional Indicators,
 * Mahjong / Domino / Playing Card blocks. It is intentionally not
 * a full Unicode emoji-property implementation — Android's
 * [Character] doesn't expose the property pre-API-31 anyway.
 *
 * Keycap emoji (0️⃣–9️⃣, #️⃣, *️⃣) are a special case: their grapheme
 * cluster leads with a plain ASCII char, so the lead-codepoint
 * check misses them. They are recognised separately by the
 * COMBINING ENCLOSING KEYCAP mark (U+20E3) in the cluster.
 */
fun extractFirstEmoji(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val it = BreakIterator.getCharacterInstance()
    it.setText(text)
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        if (start in 0 until text.length) {
            val firstCp = text.codePointAt(start)
            val cluster = text.substring(start, end)
            if (isLikelyEmojiCodePoint(firstCp) || cluster.contains('⃣')) {
                return cluster
            }
        }
        start = end
        end = it.next()
    }
    return null
}

private fun isLikelyEmojiCodePoint(cp: Int): Boolean =
    cp in 0x1F300..0x1FAFF ||  // Miscellaneous Symbols / Pictographs, Transport, Supplemental Symbols, Symbols & Pictographs Extended-A
    cp in 0x2600..0x27BF ||    // Misc symbols (☀ ☂ ☎ ❤ ✨) + Dingbats (✅ ✔ ✖)
    cp in 0x1F1E6..0x1F1FF ||  // Regional Indicator Letters (flag halves — full flag is two of these as one grapheme cluster)
    cp in 0x2300..0x23FF ||    // Misc Technical (⌚ ⌛ ⏳ ⏰)
    cp in 0x1F000..0x1F02F ||  // Mahjong / Domino tiles
    cp in 0x1F0A0..0x1F0FF ||  // Playing cards
    cp in 0x1F100..0x1F1FF     // Enclosed Alphanumeric Supplement
