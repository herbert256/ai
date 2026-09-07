package com.ai.data

fun evaluationMeaning(kind: SecondaryKind): String = when(kind) {
    SecondaryKind.RERANK -> "Question relevance or the recorded ranking rubric. Relevance does not establish factual correctness."
    SecondaryKind.COMPARE -> "Agreement with the selected reference text under the saved rubric. A reference may itself be mistaken; this is not independent factual verification."
    SecondaryKind.TOURNAMENT -> "Pairwise answer preference under the recorded judge rubric. Ranking methods summarize the same judgments, not independent experiments."
    SecondaryKind.JUDGES -> "Agreement with the other judge models. At least two other judges and a unique plurality are required per eligible match. Agreement does not prove correctness."
    SecondaryKind.TRANSRANK -> "Review of produced translations, with equal weight per passage. Different assignments or judges are not comparable evidence of translator capability."
    SecondaryKind.MODERATION -> "Categories assigned by the recorded moderation model; these are model classifications, not verified facts."
    SecondaryKind.META -> "Analysis under the saved prompt, or an explicitly user-supplied reference. Read its assumptions and source coverage."
    SecondaryKind.TRANSLATE -> "Translation of recorded source text. Translation is separate from evaluating the original answer."
}
