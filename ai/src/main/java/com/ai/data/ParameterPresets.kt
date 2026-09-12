package com.ai.data

import com.ai.model.Parameters

/** Optional examples: never selected automatically, and contain no personal data. */
internal fun parameterExamples(): List<Parameters> = listOf(
    Parameters("example-temperature-0", "Audit temperature 0", temperature = 0f),
    Parameters("example-temperature-1", "Audit temperature 1", temperature = 1f),
    Parameters("example-top-p", "Audit top P 0.8", topP = 0.8f),
    Parameters("example-top-k", "Audit top K 20", topK = 20),
    Parameters("example-seed", "Audit seed 42", seed = 42),
    Parameters("example-penalties", "Audit penalties 0.5", frequencyPenalty = 0.5f, presencePenalty = 0.5f),
    Parameters("example-json", "Audit JSON", responseFormatJson = true),
    Parameters("example-token-cap", "Audit max tokens 64", maxTokens = 64),
    Parameters("example-reasoning", "Audit reasoning low", reasoningEffort = "low"),
    Parameters("example-reasoning-high", "Audit reasoning high", reasoningEffort = "high"),
    Parameters("example-stop", "Audit stop END", stopSequences = listOf("END")),
    Parameters("example-combined", "Audit temp pair", temperature = 0.2f, maxTokens = 2048,
        topP = 0.8f, topK = 20, seed = 42, returnCitations = false)
)
