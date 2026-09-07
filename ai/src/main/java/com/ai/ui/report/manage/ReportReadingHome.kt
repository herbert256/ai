package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A reading and decision path that leaves the existing expert views available. */
@Composable
internal fun ReportReadingHome(
    reportId: String,
    onRead: (String) -> Unit,
    onAnalysis: () -> Unit,
    onCompareReference: () -> Unit,
    onTranslate: (Set<String>?) -> Unit,
    onFullExport: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reportVersion by ReportDataVersion.versionFor(reportId).collectAsState()
    val secondaryVersion by SecondaryDataVersion.versionFor(reportId).collectAsState()
    val loaded by produceState<Pair<Report?,List<SecondaryResult>>>(null to emptyList(), reportId, reportVersion, secondaryVersion) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context,reportId) to SecondaryResultStorage.listForReport(context,reportId) }
    }
    val report = loaded.first
    val secondaries = loaded.second
    var tab by rememberSaveable(reportId) { mutableIntStateOf(0) }
    var selectionKind by rememberSaveable(reportId) { mutableStateOf("answer") }
    var selectionId by rememberSaveable(reportId) { mutableStateOf<String?>(null) }
    var selectedBody by rememberSaveable(reportId) { mutableStateOf("") }
    var rationale by rememberSaveable(reportId) { mutableStateOf("") }
    var uncertainty by rememberSaveable(reportId) { mutableStateOf("") }
    var dissent by rememberSaveable(reportId) { mutableStateOf("") }
    var sources by rememberSaveable(reportId) { mutableStateOf("") }
    var reference by rememberSaveable(reportId) { mutableStateOf("") }
    var editingReference by rememberSaveable(reportId) { mutableStateOf(false) }
    var historyId by rememberSaveable(reportId) { mutableStateOf<String?>(null) }
    var compareIds by rememberSaveable(reportId) { mutableStateOf(listOf<String>()) }
    var compareMode by rememberSaveable(reportId) { mutableStateOf(false) }
    var appendix by rememberSaveable(reportId) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val text = exportText
        exportText = null
        if (uri != null && text != null) scope.launch {
            try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                ?: throw java.io.IOException("Could not open export destination") } }
            catch (e: Exception) { error = e.message }
        }
    }
    fun editDecision(kind: String, id: String) {
        selectionKind = kind; selectionId = id; tab = 2
        selectedBody = if(kind=="answer") report?.agents?.firstOrNull { it.agentId==id }?.responseBody.orEmpty()
            else secondaries.firstOrNull { it.id==id }?.content.orEmpty()
        val saved = report?.conclusion
        rationale = saved?.rationale.orEmpty(); uncertainty = saved?.uncertainty.orEmpty()
        dissent = saved?.dissent.orEmpty(); sources = saved?.sources.orEmpty()
    }
    val handleBack = { if (historyId != null) historyId = null else if (selectionId != null) selectionId = null else onBack() }
    BackHandler(onBack=handleBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TitleBar(helpTopic="report_reading",title="Read and finish report", subject=report?.title.orEmpty(), onBackClick=handleBack)
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("Answers","Analysis","Conclusion").forEachIndexed { index, label ->
                FilterChip(selected=tab==index, onClick={ tab=index; historyId=null }, label={ Text(label) })
            }
        }
        error?.let { Text(it, color=AppColors.DangerAccent) }
        if (report == null) { Text("Loading report…"); return@Column }
        val answers = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
        LazyColumn(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if (historyId != null) {
                val agent = report.agents.firstOrNull { it.agentId == historyId }
                item { Text("Previous versions · ${agent?.agentName.orEmpty()}"); TextButton(onClick={historyId=null}) { Text("Back to answers") } }
                items(agent?.answerHistory.orEmpty().asReversed(), key={it.id}) { revision ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text("${revision.source} · ${java.util.Date(revision.savedAt)}")
                        Text("${revision.provider}/${revision.model} · version ${revision.id.take(8)}", fontSize=12.sp)
                        SelectionContainer { Text(revision.body) }
                        Text("Source question / instructions", fontSize=12.sp)
                        SelectionContainer { Text(revision.prompt, fontSize=12.sp) }
                        if (revision.citations.isNotEmpty()) Text(revision.citations.joinToString("\n"))
                    } }
                }
            } else when(tab) {
                0 -> {
                    item {
                        Text("${answers.size} answers to the same question. Read an answer to refine it; choose a conclusion when ready.")
                        TextButton(onClick={compareMode=!compareMode}) { Text(if(compareMode) "Close comparison" else "Compare two answers") }
                        TextButton(onClick={onTranslate(null)}) { Text("Translate selected content…") }
                    }
                    items(answers,key={it.agentId}) { agent ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text("${agent.agentName} · ${agent.provider}/${agent.model}")
                            if (compareMode) {
                                Row { Checkbox(checked=agent.agentId in compareIds,onCheckedChange={ checked ->
                                    compareIds = if (!checked) compareIds-agent.agentId else (compareIds+agent.agentId).takeLast(2)
                                }); Text("Compare this answer",Modifier.padding(top=12.dp)) }
                                if(agent.agentId in compareIds) SelectionContainer { Text(agent.responseBody.orEmpty()) }
                            }
                            TextButton(onClick={onRead(agent.agentId)}) { Text("Read / improve answer") }
                            TextButton(onClick={onTranslate(setOf("agent:${agent.agentId}"))}) { Text("Translate only this answer") }
                            TextButton(onClick={editDecision("answer",agent.agentId)}) { Text("Choose as my conclusion") }
                            if(agent.answerHistory.orEmpty().isNotEmpty()) TextButton(onClick={historyId=agent.agentId}) { Text("Previous versions (${agent.answerHistory.size})") }
                        } }
                    }
                }
                1 -> {
                    item {
                        Text("Evaluation is evidence under a criterion, not a fact check. Question relevance, reference agreement and a judge's preference answer different questions.")
                        TextButton(onClick=onAnalysis) { Text("Create a synthesis or new analysis") }
                        TextButton(onClick=onCompareReference) { Text("Evaluate against a saved reference") }
                        TextButton(onClick={editingReference=!editingReference}) { Text("Add my independent reference") }
                        if(editingReference) {
                            OutlinedTextField(value=reference,onValueChange={reference=it},label={Text("Reference text and source attribution")},modifier=Modifier.fillMaxWidth(),minLines=4)
                            Button(enabled=reference.isNotBlank()&&!busy,onClick={ busy=true; scope.launch {
                                try { withContext(Dispatchers.IO) {
                                    SecondaryResultStorage.create(context,reportId,SecondaryKind.META,"User","Independent reference","User reference") {
                                        it.copy(content=reference.trim(),metaPromptName="User reference",sourceSnapshotId=ReportEvidenceStore.capture(report))
                                    }
                                }; reference=""; editingReference=false }
                                catch(e:Exception){error=e.message} finally {busy=false}
                            } }) { Text("Save reference") }
                        }
                    }
                    items(secondaries.filter { it.kind==SecondaryKind.META && !it.content.isNullOrBlank() },key={it.id}) { row ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text("${row.metaPromptName ?: "Analysis"} · ${row.providerId}/${row.model}")
                            com.ai.ui.shared.ReportSourceNotice(row)
                            SelectionContainer { Text(row.content.orEmpty().take(1800)) }
                            TextButton(onClick={editDecision("meta",row.id)}) { Text("Choose as my conclusion") }
                        } }
                    }
                }
                2 -> {
                    item {
                        if(selectionId != null) {
                            Text("Record your decision. The selected result and its source versions are preserved when saved.")
                            SelectionContainer { Text(selectedBody) }
                            OutlinedTextField(rationale,{rationale=it},label={Text("Why I selected this")},modifier=Modifier.fillMaxWidth(),minLines=2)
                            OutlinedTextField(uncertainty,{uncertainty=it},label={Text("Uncertainty and limitations")},modifier=Modifier.fillMaxWidth(),minLines=2)
                            OutlinedTextField(dissent,{dissent=it},label={Text("Important disagreements")},modifier=Modifier.fillMaxWidth(),minLines=2)
                            OutlinedTextField(sources,{sources=it},label={Text("Sources / citations")},modifier=Modifier.fillMaxWidth(),minLines=2)
                            Button(enabled=rationale.isNotBlank()&&!busy,onClick={busy=true;scope.launch {
                                try { withContext(Dispatchers.IO) { ReportStorage.selectConclusion(context,reportId,selectionKind,selectionId!!,rationale,uncertainty,dissent,sources,selectedBody) };selectionId=null }
                                catch(e:Exception){error=e.message} finally{busy=false}
                            }}) { Text("Save my conclusion") }
                            TextButton(onClick={selectionId=null}) { Text("Cancel decision edit") }
                        } else {
                            val decision=report.conclusion
                            if(decision==null) Text("Choose an answer or synthesis from the other tabs, then record why you selected it.")
                            else {
                                Text("Selected by you · ${decision.sourceLabel}")
                                Text("Saved ${java.util.Date(decision.selectedAt)} · source ${decision.snapshotId.take(12)}",fontSize=12.sp)
                                Text("This conclusion keeps the selected text even if answers change later.",fontSize=12.sp)
                                SelectionContainer { Text(decision.body) }
                                Text("Reason: ${decision.rationale}")
                                Text("Uncertainty: ${decision.uncertainty.ifBlank { "Not specified" }}")
                                Text("Disagreement: ${decision.dissent.ifBlank { "Not specified" }}")
                                Text("Sources: ${decision.sources.ifBlank { "Not specified" }}")
                                TextButton(enabled=(if(decision.sourceKind=="answer") answers.any { it.agentId==decision.sourceId } else secondaries.any { it.id==decision.sourceId }),onClick={editDecision(decision.sourceKind,decision.sourceId)}) { Text("Update decision from current result") }
                                TextButton(enabled=!busy,onClick={ busy=true;scope.launch {
                                    try { withContext(Dispatchers.IO) { ReportStorage.clearConclusion(context,reportId) } }
                                    catch(e:Exception){error=e.message}finally{busy=false}
                                }}) { Text("Clear my selection") }
                                Row { Checkbox(appendix,{appendix=it});Text("Include full evidence appendix",Modifier.padding(top=12.dp)) }
                                Button(enabled=!busy,onClick={busy=true;scope.launch {
                                    try { exportText=withContext(Dispatchers.IO) { buildConclusionHtml(report,secondaries,appendix) };export.launch("report-conclusion.html") }
                                    catch(e:Exception){error=e.message}finally{busy=false}
                                }}) { Text("Export conclusion") }
                            }
                            TextButton(onClick=onFullExport) { Text("Export complete report…") }
                        }
                    }
                }
            }
        }
        TextButton(onClick=onBack,modifier=Modifier.fillMaxWidth()) { Text("Back to report") }
    }
}

internal fun buildConclusionHtml(report: Report, secondaries: List<SecondaryResult>, appendix: Boolean): String {
    val decision=report.conclusion ?: error("Select a conclusion first")
    fun esc(text:String)=ReportExportRedaction.plainText(text).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    val snapshot=ReportEvidenceStore.sources(report.id,decision.snapshotId)
    return buildString {
        append("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>Report conclusion</title><style>body{max-width:900px;margin:30px auto;padding:0 20px;font:17px/1.6 system-ui;color:#172638}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}section{border-top:1px solid #ccc;margin-top:30px}small{color:#526477}@media print{section{break-before:auto}h2,h3{break-after:avoid}}</style><body>")
        append("<h1>My conclusion</h1><p>").append(esc(snapshot?.prompt ?: "Saved question unavailable")).append("</p><small>Selected by the report owner · ")
        append(esc(decision.sourceLabel)).append(" · ").append(esc(java.util.Date(decision.selectedAt).toString())).append("</small><pre>").append(esc(decision.body)).append("</pre>")
        listOf("Why I selected this" to decision.rationale,"Uncertainty" to decision.uncertainty,"Disagreements" to decision.dissent,"Sources" to decision.sources).forEach { (label,text) ->
            append("<h2>").append(label).append("</h2><pre>").append(esc(text.ifBlank { "Not specified by the report owner" })).append("</pre>")
        }
        append("<h2>Evidence identity</h2><p>Saved source revision: <code>").append(esc(decision.snapshotId)).append("</code>. ")
        append(if(snapshot==null) "Source snapshot unavailable. The selected conclusion text is retained, but its supporting inputs cannot be verified." else "${snapshot.answers.size} source answers and ${snapshot.secondaryBodies.orEmpty().size} reference results were preserved.").append("</p>")
        if(appendix) {
            append("<section><h2>Evidence appendix — saved decision inputs</h2>")
            snapshot?.answers?.forEach { a ->append("<h3>").append(esc("${a.name} · ${a.provider}/${a.model}")).append("</h3><pre>").append(esc(a.body)).append("</pre>") }
            snapshot?.secondaryBodies.orEmpty().forEach { (id,body) ->append("<h3>Saved reference ").append(esc(id)).append("</h3><pre>").append(esc(body)).append("</pre>") }
            append("</section><section><h2>Current analysis and coverage at export</h2><p>These results are listed separately from the saved decision inputs.</p>")
            secondaries.filter { !it.content.isNullOrBlank() }.forEach { row ->
                append("<h3>").append(esc("${row.kind} · ${row.metaPromptName.orEmpty()} · ${row.providerId}/${row.model}")).append("</h3><p>")
                append(esc(evaluationMeaning(row.kind) + " " + ReportEvidenceStore.sourceDescription(report,row))).append("</p><pre>").append(esc(row.content.orEmpty())).append("</pre>")
            }
            append("</section>")
        }
        append("</body></html>")
    }
}
