package com.ai.ui.admin

internal val searchHelp: Map<String, HelpContent> = mapOf(
    "search_ai_reports_screen" to HelpContent(
        title = "Help - Search AI reports",
        cards = listOf(
            HelpCard("What you see", "Three search modes in escalating-cost order: 🔍 Quick local search (substring), 📂 Extended local search (tokenised), 🌐 Remote semantic search (cloud embeddings)."),
            HelpCard("How to use it", "Pick the mode that suits the question — local-quick is fast and free; remote-semantic uses an embedding provider and bills accordingly. Each mode shows matching reports with the same per-row 🔧 / 👁 icons as the dashboard.")
        )
    ),
    "search_local" to HelpContent(
        title = "Help - Extended local search",
        cards = listOf(
            HelpCard("Overview", "Local keyword search across saved reports. The query is split on whitespace; each token is matched case-insensitively against title + prompt + every successful agent's response body. Score = total token occurrences."),
            HelpCard("Query field", "Multi-line, up to 3 lines. Whitespace-tokenised — multi-word queries become AND-of-substrings with score summed. No regex."),
            HelpCard("Search button", "Purple. Disabled until the query is non-blank and no run is in flight. Label flips to \"Searching…\" while running."),
            HelpCard("Status line", "Single line under the button: \"Searching…\", \"No matches.\", or \"N results\"."),
            HelpCard("Result rows", "Title (white, bold), date (yyyy-MM-dd HH:mm), and the integer score on the right in blue. Tap to open the report. Top 25 only — sorted by score desc, then timestamp desc."),
            HelpCard("Title-bar icons", "Help and Home only. No trace icon — the search runs locally on the phone, there's nothing to record."),
            HelpCard("Tips", "Search runs entirely on the device — no API calls, no key required. Useful even when offline."),
            HelpCard("Pitfalls", "Tokens are matched as substrings, so very short tokens (\"ai\", \"the\") will inflate scores via incidental matches. Use longer or more distinctive terms when you need precision."),
        )
    ),
    "search_semantic" to HelpContent(
        title = "Help - Semantic search",
        cards = listOf(
            HelpCard("Overview", "Embedding-based similarity search across saved reports. The user picks an embedding-typed model from any active OpenAI-compatible provider; query and reports are embedded, scored by cosine, top 10 returned."),
            HelpCard("Empty state", "When no provider has an embedding-typed model, an inline panel points you at AI Setup → Manual model types overrides or fetching a provider whose list contains text-embedding-3-small."),
            HelpCard("Model picker", "Dropdown lists every (active OpenAI-compatible service, model marked EMBEDDING) pair. Label uses the project's \"Model name layout\" setting via modelLabel."),
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
            HelpCard("Overview", "The cheapest of the search variants — single substring match (case-insensitive) against report prompt and every successful agent response. No tokenisation, no scoring; a report is either a hit or it isn't. Results sorted by recency."),
            HelpCard("Word field", "Single-line input labelled Word. Used as one substring — short whitespace phrases work as a single literal."),
            HelpCard("Search button", "Purple. Disabled until the field is non-blank and no run is in flight. Label flips to \"Searching…\" while running."),
            HelpCard("Status line", "\"Searching…\", \"No matches.\", or \"N results\". No score is shown — every hit is binary."),
            HelpCard("Result rows", "Title and timestamp only. Tap to open the report. Sorted by timestamp desc."),
            HelpCard("Title-bar icons", "Help and Home only."),
            HelpCard("Tips", "Faster than Extended local search for narrow queries — no per-token scoring loop, no top-N truncation. Returns every hit."),
            HelpCard("Pitfalls", "Title is not searched (only prompt + responses). For a title query, use Extended local search instead."),
        )
    ),
)
