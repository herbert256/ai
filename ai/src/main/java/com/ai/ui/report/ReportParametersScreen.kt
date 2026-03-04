package com.ai.ui.report

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AgentParameters
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

@Composable
fun ReportAdvancedParametersScreen(
    currentParameters: AgentParameters?,
    onApply: (AgentParameters?) -> Unit,
    onBack: () -> Unit
) {
    var temperature by remember { mutableStateOf(currentParameters?.temperature?.toString() ?: "") }
    var maxTokens by remember { mutableStateOf(currentParameters?.maxTokens?.toString() ?: "") }
    var topP by remember { mutableStateOf(currentParameters?.topP?.toString() ?: "") }
    var topK by remember { mutableStateOf(currentParameters?.topK?.toString() ?: "") }
    var frequencyPenalty by remember { mutableStateOf(currentParameters?.frequencyPenalty?.toString() ?: "") }
    var presencePenalty by remember { mutableStateOf(currentParameters?.presencePenalty?.toString() ?: "") }
    var systemPrompt by remember { mutableStateOf(currentParameters?.systemPrompt ?: "") }
    var seed by remember { mutableStateOf(currentParameters?.seed?.toString() ?: "") }
    var searchEnabled by remember { mutableStateOf(currentParameters?.searchEnabled ?: false) }
    var returnCitations by remember { mutableStateOf(currentParameters?.returnCitations ?: true) }
    var searchRecency by remember { mutableStateOf(currentParameters?.searchRecency ?: "") }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Advanced Parameters", onBackClick = onBack, onAiClick = onBack)
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val params = AgentParameters(
                        temperature = temperature.toFloatOrNull(), maxTokens = maxTokens.toIntOrNull(),
                        topP = topP.toFloatOrNull(), topK = topK.toIntOrNull(),
                        frequencyPenalty = frequencyPenalty.toFloatOrNull(), presencePenalty = presencePenalty.toFloatOrNull(),
                        systemPrompt = systemPrompt.takeIf { it.isNotBlank() }, seed = seed.toIntOrNull(),
                        searchEnabled = searchEnabled, returnCitations = returnCitations,
                        searchRecency = searchRecency.takeIf { it.isNotBlank() }
                    )
                    val hasAny = temperature.isNotBlank() || maxTokens.isNotBlank() || topP.isNotBlank() || topK.isNotBlank() ||
                            frequencyPenalty.isNotBlank() || presencePenalty.isNotBlank() || systemPrompt.isNotBlank() || seed.isNotBlank() ||
                            searchEnabled || !returnCitations || searchRecency.isNotBlank()
                    onApply(if (hasAny) params else null)
                },
                modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text("Apply", maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = {
                    temperature = ""; maxTokens = ""; topP = ""; topK = ""; frequencyPenalty = ""; presencePenalty = ""
                    systemPrompt = ""; seed = ""; searchEnabled = false; returnCitations = true; searchRecency = ""
                    onApply(null)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Clear all", maxLines = 1, softWrap = false) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("These parameters override individual agent settings for this report only.", color = AppColors.TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(value = temperature, onValueChange = { temperature = it }, label = { Text("Temperature (0.0 - 2.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it }, label = { Text("Max tokens") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = topP, onValueChange = { topP = it }, label = { Text("Top P (0.0 - 1.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = topK, onValueChange = { topK = it }, label = { Text("Top K") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = frequencyPenalty, onValueChange = { frequencyPenalty = it }, label = { Text("Frequency penalty (-2.0 - 2.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = presencePenalty, onValueChange = { presencePenalty = it }, label = { Text("Presence penalty (-2.0 - 2.0)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = seed, onValueChange = { seed = it }, label = { Text("Seed (for reproducibility)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = systemPrompt, onValueChange = { systemPrompt = it }, label = { Text("System prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5, colors = AppColors.outlinedFieldColors())

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().clickable { searchEnabled = !searchEnabled }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = searchEnabled, onCheckedChange = { searchEnabled = it })
                    Spacer(modifier = Modifier.width(8.dp)); Text("Enable web search (xAI, Perplexity)")
                }
                Row(modifier = Modifier.fillMaxWidth().clickable { returnCitations = !returnCitations }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = returnCitations, onCheckedChange = { returnCitations = it })
                    Spacer(modifier = Modifier.width(8.dp)); Text("Return citations (Perplexity)")
                }
                OutlinedTextField(value = searchRecency, onValueChange = { searchRecency = it }, label = { Text("Search recency (day, week, month, year)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            }
        }
    }
}
