package com.ai.ui.admin

internal val searchHelp: Map<String, HelpContent> = mapOf(
    "search_ai_reports_screen" to HelpContent(
        title = "Help - Search reports",
        cards = listOf(
            HelpCard("What you see", "Two keyword search modes: 🔍 Quick local search (substring) and 📂 Extended local search (tokenised). Both run entirely on the device."),
            HelpCard("How to use it", "Pick the mode that suits the question — Quick is a fast binary substring match; Extended tokenises and scores. Both are free and offline, and show matching reports with the same per-row 🔧 / 👁 icons as the dashboard.")
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
