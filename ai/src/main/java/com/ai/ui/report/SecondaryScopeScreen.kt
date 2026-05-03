package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.ReportAgent
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryLanguageScope
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryScope
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Inserted between the Summarize / Compare button and the model picker.
 * Always shown for these kinds — the user picks the input set (all
 * model reports / top-N from a rerank / a manual subset) and, when the
 * report has translation rows, which target languages to include.
 */
@Composable
internal fun SecondaryScopeScreen(
    kind: SecondaryKind,
    agents: List<ReportAgent>,
    reranks: List<SecondaryResult>,
    languages: List<Pair<String, String?>>, // (English, native) pairs; empty when no translations
    totalReports: Int,
    onContinue: (SecondaryScope, SecondaryLanguageScope) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val kindLabel = when (kind) {
        SecondaryKind.SUMMARIZE -> "Summarize"
        SecondaryKind.COMPARE -> "Compare"
        SecondaryKind.RERANK -> "Rerank"
        SecondaryKind.MODERATION -> "Moderation"
        SecondaryKind.TRANSLATE -> "Translate"
    }
    var scopeMode by remember { mutableStateOf(ScopeMode.ALL) }
    var countText by remember {
        mutableStateOf(minOf(3, totalReports.coerceAtLeast(1)).toString())
    }
    var selectedRerank by remember { mutableStateOf(reranks.firstOrNull()?.id ?: "") }
    var rerankDropdownOpen by remember { mutableStateOf(false) }
    val manualPicked = remember { mutableStateMapOf<String, Boolean>().apply {
        // Default: every successful agent ticked, so "Manual" is a starting
        // point the user can deselect from rather than an empty set.
        agents.forEach { put(it.agentId, true) }
    } }

    val countInt = countText.toIntOrNull()?.coerceIn(1, totalReports.coerceAtLeast(1)) ?: 0
    val canContinue = when (scopeMode) {
        ScopeMode.ALL -> true
        ScopeMode.TOP_RANKED -> countInt > 0 && selectedRerank.isNotBlank()
        ScopeMode.MANUAL -> manualPicked.values.any { it }
    }

    // Languages: empty selection set = "all present", otherwise the
    // checked subset. The original (untranslated) source is always
    // included — "All languages" means every translation language plus
    // the original; "Selected" lets the user prune translations.
    var allLanguages by remember { mutableStateOf(true) }
    val pickedLanguages = remember { mutableStateMapOf<String, Boolean>().apply {
        languages.forEach { (lang, _) -> put(lang, true) }
    } }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "$kindLabel — scope", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                "Choose which model results $kindLabel.lowercase() should look at.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            Spacer(modifier = Modifier.height(12.dp))

            ScopeOption(
                selected = scopeMode == ScopeMode.ALL,
                label = "All model reports",
                sublabel = "Use every successful result from this report ($totalReports)",
                onSelect = { scopeMode = ScopeMode.ALL }
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (reranks.isNotEmpty()) {
                ScopeOption(
                    selected = scopeMode == ScopeMode.TOP_RANKED,
                    label = "Only top ranked reports",
                    sublabel = "Restrict the input to the top-N entries of a rerank",
                    onSelect = { scopeMode = ScopeMode.TOP_RANKED }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            ScopeOption(
                selected = scopeMode == ScopeMode.MANUAL,
                label = "Manual select models",
                sublabel = "Tick exactly which model results to include",
                onSelect = { scopeMode = ScopeMode.MANUAL }
            )

            if (scopeMode == ScopeMode.TOP_RANKED) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = countText,
                            onValueChange = { newValue ->
                                countText = newValue.filter { it.isDigit() }.take(3)
                            },
                            label = { Text("Number of reports") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = AppColors.outlinedFieldColors(),
                            supportingText = {
                                Text("1 to $totalReports", fontSize = 11.sp, color = AppColors.TextTertiary)
                            }
                        )

                        Box {
                            OutlinedButton(
                                onClick = { rerankDropdownOpen = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.BorderUnfocused)
                            ) {
                                val sel = reranks.firstOrNull { it.id == selectedRerank }
                                Text(
                                    text = sel?.let { rerankLabel(it) } ?: "Pick a rerank",
                                    modifier = Modifier.weight(1f),
                                    fontSize = 13.sp,
                                    color = if (sel != null) Color.White else AppColors.TextTertiary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text("▾", color = AppColors.TextTertiary)
                            }
                            DropdownMenu(
                                expanded = rerankDropdownOpen,
                                onDismissRequest = { rerankDropdownOpen = false },
                                modifier = Modifier.background(Color(0xFF2D2D2D))
                            ) {
                                reranks.forEach { r ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                rerankLabel(r),
                                                color = if (r.id == selectedRerank) AppColors.Blue else Color.White,
                                                fontSize = 13.sp
                                            )
                                        },
                                        onClick = {
                                            selectedRerank = r.id
                                            rerankDropdownOpen = false
                                        }
                                    )
                                }
                            }
                        }
                        Text("Rank source: which rerank's top entries to use.", fontSize = 11.sp, color = AppColors.TextTertiary)
                    }
                }
            }

            if (scopeMode == ScopeMode.MANUAL) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Tick the model rows to include", fontSize = 11.sp, color = AppColors.TextTertiary)
                        agents.forEach { a ->
                            val checked = manualPicked[a.agentId] ?: false
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { manualPicked[a.agentId] = !checked },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { manualPicked[a.agentId] = it })
                                Text(
                                    a.agentName.ifBlank { "${AppService.findById(a.provider)?.displayName ?: a.provider} / ${a.model}" },
                                    fontSize = 13.sp, color = Color.White,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            if (languages.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text("Languages", fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                ScopeOption(
                    selected = allLanguages,
                    label = "All languages",
                    sublabel = "Original plus every translation present (${languages.size} translated)",
                    onSelect = { allLanguages = true }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ScopeOption(
                    selected = !allLanguages,
                    label = "Select languages",
                    sublabel = "Pick which translation languages to include alongside the original",
                    onSelect = { allLanguages = false }
                )
                if (!allLanguages) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            languages.forEach { (lang, native) ->
                                val checked = pickedLanguages[lang] ?: false
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { pickedLanguages[lang] = !checked },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = { pickedLanguages[lang] = it })
                                    val label = native?.takeIf { it.isNotBlank() && it != lang }?.let { "$lang · $it" } ?: lang
                                    Text(label, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                val scope = when (scopeMode) {
                    ScopeMode.ALL -> SecondaryScope.AllReports
                    ScopeMode.TOP_RANKED -> SecondaryScope.TopRanked(countInt, selectedRerank)
                    ScopeMode.MANUAL -> SecondaryScope.Manual(
                        manualPicked.filterValues { it }.keys.toSet()
                    )
                }
                val langScope = if (allLanguages || languages.isEmpty()) SecondaryLanguageScope.AllPresent
                else SecondaryLanguageScope.Selected(pickedLanguages.filterValues { it }.keys.toSet())
                onContinue(scope, langScope)
            },
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Continue", maxLines = 1, softWrap = false) }
    }
}

private enum class ScopeMode { ALL, TOP_RANKED, MANUAL }

private fun rerankLabel(r: SecondaryResult): String {
    val provider = AppService.findById(r.providerId)?.displayName ?: r.providerId
    val ts = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(r.timestamp))
    return "$provider · ${r.model} · $ts"
}

@Composable
private fun ScopeOption(selected: Boolean, label: String, sublabel: String, onSelect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = if (selected) AppColors.CardBackgroundAlt else AppColors.CardBackground)
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(sublabel, fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Default)
            }
        }
    }
}
