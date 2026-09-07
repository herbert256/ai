package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.data.*
import com.ai.model.Worker
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TranslationSelectionScreen(reportId:String, initial:TranslationSelection,
    onConfirm:(TranslationSelection)->Unit,onBack:()->Unit) {
    val context=LocalContext.current
    val loaded by produceState<Pair<Report?,List<TranslationItem>>>(null to emptyList(),reportId) {
        value=withContext(Dispatchers.IO) {
            val r=ReportStorage.getReport(context,reportId)
            r to (r?.let { translatableReportItems(it,SecondaryResultStorage.listForReport(context,reportId)) } ?: emptyList())
        }
    }
    val candidates=loaded.second
    var selected by remember(reportId,candidates) { mutableStateOf(initial.itemIds ?: candidates.map { it.id }.toSet()) }
    var terms by remember(reportId) { mutableStateOf(initial.terminology) }
    var worker by remember(reportId) { mutableStateOf(initial.singleWorker) }
    var chooseWorker by remember { mutableStateOf(false) }
    BackHandler(onBack=onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TitleBar(helpTopic="translation_scope",title="Choose content to translate",subject="${selected.size} item${if(selected.size==1) "" else "s"} · then choose a language",onBackClick=onBack)
        TextButton(onClick=onBack) { Text("Back to report") }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item {
                Text("Translate this answer, selected content, or the entire report. Titles and analysis are optional.")
                Row {
                    TextButton(onClick={selected=candidates.map{it.id}.toSet()}) { Text("Entire report") }
                    TextButton(onClick={selected=emptySet()}) { Text("Clear") }
                    TextButton(onClick={selected=candidates.filter{it.kind==TranslationKind.AGENT_RESPONSE}.map{it.id}.toSet()}) { Text("Answers") }
                }
                OutlinedTextField(terms,{terms=it},label={Text("Shared terminology / style (optional)")},modifier=Modifier.fillMaxWidth(),minLines=2)
                TextButton(onClick={chooseWorker=!chooseWorker}) {
                    Text(worker?.let { "Consistent translator: ${it.provider}/${it.model}" } ?: "Translator: configured worker pool (change)")
                }
            }
            if(chooseWorker) {
                item { TextButton(onClick={worker=null;chooseWorker=false}) { Text("Use configured pool and fallbacks") } }
                items(loaded.first?.agents.orEmpty().distinctBy { it.agentId },key={it.agentId}) { agent ->
                    TextButton(onClick={
                        worker=Worker(provider=agent.provider,model=agent.model,credentialAgentId=agent.agentId,
                            frozenParameters=agent.executionConfig?.parameters,frozenEndpointUrl=agent.executionConfig?.endpointUrl)
                        chooseWorker=false
                    }) { Text("Use only ${agent.agentName} · ${agent.provider}/${agent.model}") }
                }
            }
            items(candidates,key={it.id}) { item ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(item.id in selected,{checked->selected=if(checked) selected+item.id else selected-item.id})
                    Column(Modifier.weight(1f).padding(top=10.dp)) {
                        Text(item.label)
                        Text("${item.kind.name.lowercase().replace('_',' ')} · ${item.sourceText.length} characters")
                    }
                }
            }
        }
        Button(enabled=loaded.first!=null&&selected.isNotEmpty(),onClick={onConfirm(TranslationSelection(selected,terms.trim(),worker,confirmed=true,sourceDigests=candidates.filter { it.id in selected }.associate { it.id to ReportEvidenceStore.digest(it.sourceText) }))},modifier=Modifier.fillMaxWidth()) {
            Text("Continue with ${selected.size} selected item${if(selected.size==1) "" else "s"}")
        }
    }
}
