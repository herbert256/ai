package com.ai.ui.admin

internal val searchHelp: Map<String, HelpContent> = mapOf(
    "search_ai_reports_screen" to HelpContent(
        title = "Help - Search reports",
        cards = listOf(
            HelpCard("What you see", "Search modes in escalating setup/cost order: 🔍 Quick local search (substring), 📂 Extended local search (tokenised), 🌐 Remote semantic search (cloud embeddings), and — when Experimental features is on — 📱 Local semantic search (on-device embeddings)."),
            HelpCard("How to use it", "Pick the mode that suits the question. Quick and Extended run locally with no model call; Remote semantic uses an embedding provider and bills accordingly; Local semantic needs an installed LiteRT embedder but keeps the embedding work on device. Each mode shows matching reports with the same per-row 🔧 / 👁 icons as the dashboard.")
        )
    ),
    "search_local" to HelpContent(
        title = "Help - Extended local search",
        cards = listOf(
            HelpCard("Overview", "Local keyword search across saved reports. The query is split on whitespace; each token is matched case-insensitively against title + prompt + every successful agent's response body. Score = total token occurrences."),
            HelpCard("Query field", "Multi-line, up to 3 lines. Whitespace-tokenised; a report is included if any token matches at least once, with the score summed across every match — not a strict AND of all tokens. ASCII tokens match on word boundaries (\"ai\" won't hit inside \"said\"); tokens with non-ASCII characters (café, CJK, Cyrillic) fall back to plain substring matching."),
            HelpCard("Search button", "Teal-tinted outlined button. Disabled until the query is non-blank and no run is in flight. Label flips to \"Searching…\" while running."),
            HelpCard("Status line", "Single line under the button: \"Searching…\", \"No matches.\", or \"N results\"."),
            HelpCard("Result rows", "Title (white, bold), date (yyyy-MM-dd HH:mm), and the integer score on the right in blue. Tap to open the report. Top 25 only — sorted by score desc, then timestamp desc."),
            HelpCard("Title-bar icons", "Help and Home only. No trace icon — the search runs locally on the phone, there's nothing to record."),
            HelpCard("Tips", "Search runs entirely on the device — no API calls, no key required. Useful even when offline."),
            HelpCard("Pitfalls", "Because a single token match is enough to surface a report, one common word repeated many times can outscore a report that matches every distinctive word in the query just once. Non-ASCII tokens still match as plain substrings (no word-boundary check), so very short accented/CJK/Cyrillic tokens can still over-match."),
        )
    ),
    "search_semantic" to HelpContent(
        title = "Help - Semantic search",
        cards = listOf(
            HelpCard("Overview", "Embedding-based similarity search across saved reports. The user picks an embedding-typed model from any active OpenAI-compatible or Google provider; query and reports are embedded, scored by cosine, top 10 returned."),
            HelpCard("Empty state", "When no active OpenAI-compatible or Google provider has an embedding-typed model, an inline panel points you at AI Setup → Models setup → Manual model types overrides, or fetching a provider whose list includes one (e.g. text-embedding-3-small on OpenAI, text-embedding-004 on Gemini)."),
            HelpCard("Model picker", "Dropdown lists every (active OpenAI-compatible or Google service, model marked EMBEDDING) pair. Label uses the project's \"Model name layout\" setting via modelLabel."),
            HelpCard("Query field", "Up to 3 lines, multi-line. Submitted whole — not tokenised. The text becomes a single embedding vector compared against report vectors."),
            HelpCard("Search behaviour", "Embeds the query first; then walks every report, building a representative text from title + prompt + first 2k characters of the first non-blank agent response. Cached vectors (keyed on doc id, provider, model, content hash) are reused; new ones are batched in groups of 50."),
            HelpCard("Status line", "Live progress: \"Indexing reports… i / N\" while scanning, then \"Embedding batch X / Y (Z reports)\" while sending. Final state is the result count or \"No matches.\""),
            HelpCard("Result rows", "Title, timestamp, and the cosine score (3 decimals) in blue. Top 10, sorted descending; rows with score ≤ 0 are dropped."),
            HelpCard("Title-bar icons", "Help and Home only."),
            HelpCard("Tips", "Edit a report and a fresh content hash means the next run re-embeds it automatically — caching is correct across edits, not just identity. The 50-per-batch limit fits all observed providers."),
            HelpCard("Pitfalls", "Costs scale with report count on first run for a new model; subsequent runs hit the cache. Switching embedding model invalidates the cache for that model only — vectors from the old model are still on disk and reused if you switch back."),
        )
    ),
    "search_quick" to HelpContent(
        title = "Help - Quick local search",
        cards = listOf(
            HelpCard("Overview", "The cheapest of the search variants — single substring match (case-insensitive) against report title, prompt, and every successful agent response. No tokenisation, no scoring; a report is either a hit or it isn't. Results sorted by recency."),
            HelpCard("Word field", "Single-line input labelled Word. Used as one substring — short whitespace phrases work as a single literal."),
            HelpCard("Search button", "Teal-tinted outlined button. Disabled until the field is non-blank and no run is in flight. Label flips to \"Searching…\" while running."),
            HelpCard("Status line", "\"Searching…\", \"No matches.\", or \"N results\". No score is shown — every hit is binary."),
            HelpCard("Result rows", "Title and timestamp only. Tap to open the report. Sorted by timestamp desc."),
            HelpCard("Title-bar icons", "Help and Home only."),
            HelpCard("Tips", "Faster than Extended local search for narrow queries — no per-token scoring loop, no top-N truncation. Returns every hit."),
            HelpCard("Pitfalls", "Plain substring matching with no word-boundary check — short or common substrings (\"ai\", \"the\") can match inside unrelated words and pad the hit list. There's no scoring, so a strong match sits alongside incidental ones with no way to rank them apart, and results are ordered by recency, not relevance. Use Extended local search when you need word-bounded, scored matching."),
        )
    ),
)
