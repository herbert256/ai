package com.ai.ui.helpers
import com.ai.ui.report.view.*
import com.ai.ui.report.manage.*

import com.ai.data.SecondaryResult

/** Helper id-derivation shared between the result-page summary and
 *  the translation run screens so the same group of TRANSLATE rows
 *  always lands under the same key. New rows carry
 *  [SecondaryResult.translationRunId]; legacy rows fall back to a
 *  per-language synthetic id so they still cluster correctly. */
internal fun translationRunGroupingId(r: SecondaryResult): String =
    r.translationRunId ?: "lang:${r.targetLanguage ?: ""}"
