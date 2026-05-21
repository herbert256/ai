package com.ai.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Restriction on which of the parent report's per-agent results are
 *  fed into a chat-type Meta run. Rerank itself always uses
 *  [AllReports] — it ranks the full set by definition. */
sealed class SecondaryScope {
    object AllReports : SecondaryScope()
    data class TopRanked(val count: Int, val rerankResultId: String) : SecondaryScope()
    /** Explicit list of agent ids the user picked from the existing
     *  report. When non-empty the meta run only sees those rows, the
     *  same way [TopRanked] only sees the rerank's top-N. */
    data class Manual(val agentIds: Set<String>) : SecondaryScope()

    /** Persistable string form so the cascade-on-prompt-change path can
     *  re-run a meta with the same scope it originally ran with,
     *  instead of silently widening to AllReports and re-billing the
     *  full agent set. Format:
     *    "ALL"
     *    "TOP:<rerankResultId>:<count>"
     *    "MANUAL:<agentId1>,<agentId2>,..." */
    fun encode(): String = when (this) {
        is AllReports -> "ALL"
        is TopRanked -> "TOP:$rerankResultId:$count"
        is Manual -> "MANUAL:" + agentIds.joinToString(",")
    }

    companion object {
        /** Inverse of [encode]. Returns [AllReports] on null, blank, or
         *  malformed input — defensive fallback so a corrupt or legacy
         *  row never blocks a cascade. */
        fun decodeOrAllReports(s: String?): SecondaryScope {
            if (s.isNullOrBlank()) return AllReports
            return runCatching {
                when {
                    s == "ALL" -> AllReports
                    s.startsWith("TOP:") -> {
                        val rest = s.removePrefix("TOP:")
                        val lastColon = rest.lastIndexOf(':')
                        if (lastColon <= 0) AllReports
                        else {
                            val rerankId = rest.substring(0, lastColon)
                            val count = rest.substring(lastColon + 1).toIntOrNull() ?: return AllReports
                            TopRanked(count, rerankId)
                        }
                    }
                    s.startsWith("MANUAL:") -> {
                        val rest = s.removePrefix("MANUAL:")
                        val ids = rest.split(",").filter { it.isNotBlank() }.toSet()
                        if (ids.isEmpty()) AllReports else Manual(ids)
                    }
                    else -> AllReports
                }
            }.getOrDefault(AllReports)
        }
    }
}

/** Language filter for chat-type META multi-language fan-out. Defaults
 *  to AllPresent so existing call sites keep their behaviour; the scope
 *  picker offers Selected for "only these languages". TRANSLATE /
 *  RERANK / MODERATION ignore this. */
sealed class SecondaryLanguageScope {
    object AllPresent : SecondaryLanguageScope()
    /** [languages] holds English-name keys ("Dutch") plus the empty
     *  string for the original (untranslated) source. */
    data class Selected(val languages: Set<String>) : SecondaryLanguageScope()
}

