package com.ai.data

/** Shared prose for human-readable exports; JSON bundles also carry the structured fields. */
fun conclusionExportText(report: Report): String? = report.conclusion?.let { decision ->
    val snapshot = ReportEvidenceStore.sources(report.id,decision.snapshotId)
    buildString {
        appendLine("Selected by the report owner: ${decision.sourceLabel}")
        appendLine("Saved: ${java.util.Date(decision.selectedAt)}")
        appendLine("Source revision: ${decision.snapshotId}")
        appendLine("Question: ${snapshot?.prompt ?: "Saved question unavailable"}")
        appendLine(); appendLine(decision.body)
        listOf("Reason" to decision.rationale,"Uncertainty" to decision.uncertainty,"Disagreements" to decision.dissent,"Sources" to decision.sources).forEach { (label,text) ->
            appendLine();appendLine("$label: ${text.ifBlank { "Not specified by the report owner" }}")
        }
        appendLine()
        appendLine(if(snapshot==null) "Supporting source snapshot unavailable; only the selected conclusion is retained."
            else "This decision preserves ${snapshot.answers.size} answer versions and ${snapshot.secondaryBodies.orEmpty().size} reference versions. Later report changes do not update it.")
    }
}
