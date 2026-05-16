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
import com.ai.data.SecondaryLanguageScope
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryScope
import com.ai.model.InternalPrompt
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/**
 * Inserted between a Meta-prompt button (or Summarize / Compare button
 * — same flow either way) and the model picker. The user picks the
 * input set (all model reports / top-N from a rerank / a manual
 * subset) and, when the report has translation rows, which target
 * languages to include.
 */
@Composable
internal fun SecondaryScopeScreen(
    metaPrompt: InternalPrompt,
    agents: List<ReportAgent>,
    reranks: List<SecondaryResult>,
    languages: List<Pair<String, String?>>, // (English, native) pairs; empty when no translations
    totalReports: Int,
    onContinue: (SecondaryScope, SecondaryLanguageScope) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val kindLabel = metaPrompt.name
    val isMetaCategory = metaPrompt.category == "meta"
    // Rerank and moderation always operate on the full set of agents
    // — there's no per-row scope concept (a rerank ranks them all; a
    // moderation classifies them all). The screen still surfaces the
    // language picker for these categories so the user can pick a
    // translation to operate on, but it hides the scope-subset card.
    val isRerank = metaPrompt.category == "rerank"
    val isModeration = metaPrompt.category == "moderation"
    val isLanguageOnly = isRerank || isModeration
    // fan_out prompts also pick a subset of report-models as
    // "sources" (the answerer set is always the full successful list).
    // Top-Ranked / Manual scope therefore make sense for both meta and
    // fan_out. Language fan-out, however, stays meta-only — fan out
    // always runs on the original.
    val supportsSubsetScope = isMetaCategory || metaPrompt.category == "fan_out"
    var scopeMode by remember { mutableStateOf(ScopeMode.ALL) }
    var countText by remember {
        mutableStateOf(minOf(3, totalReports.coerceAtLeast(1)).toString())
    }
    var selectedRerank by remember { mutableStateOf(reranks.firstOrNull()?.id ?: "") }
    var rerankDropdownOpen by remember { mutableStateOf(false) }
    // Re-key on the agents identity so a fresh batch (different agent
    // ids) reseeds the map. The previous unkeyed remember kept stale
    // entries when the parent recomposed with a different `agents`
    // list — the .keys returned at confirm time then leaked deleted
    // agentIds into the persisted scope.
    val manualPicked = remember(agents.map { it.agentId }) {
        mutableStateMapOf<String, Boolean>().apply {
            // Default: every successful agent ticked, so "Manual" is a
            // starting point the user can deselect from rather than an
            // empty set.
            agents.forEach { put(it.agentId, true) }
        }
    }

    val countInt = countText.toIntOrNull()?.coerceIn(1, totalReports.coerceAtLeast(1)) ?: 0
    val canContinue = when (scopeMode) {
        ScopeMode.ALL -> true
        ScopeMode.TOP_RANKED -> countInt > 0 && selectedRerank.isNotBlank()
        ScopeMode.MANUAL -> manualPicked.values.any { it }
    }

    // Languages: empty selection set = "all present", otherwise the
    // checked subset. "All languages" means every translation language
    // plus the original; "Selected" lets the user prune translations
    // AND opt the original out via its own checkbox at the top of the
    // list. The original is keyed as the empty string in the Selected
    // set per SecondaryLanguageScope's doc comment.
    var allLanguages by remember { mutableStateOf(true) }
    var pickedOriginal by remember { mutableStateOf(true) }
    val pickedLanguages = remember { mutableStateMapOf<String, Boolean>().apply {
        languages.forEach { (lang, _) -> put(lang, true) }
    } }
    // Fan-out / rerank / moderation are single-language: they each
    // operate on one (source body, prompt) set at a time. Empty
    // string = Original; otherwise an English-name key from
    // `languages`. Independent of the meta-mode multi-select state
    // above so the two UIs don't share `remember`.
    val isFanOut = metaPrompt.category == "fan_out"
    val isSingleLanguage = isFanOut || isLanguageOnly
    var fanOutPickedLanguage by remember(isSingleLanguage) { mutableStateOf("") }
    // Initiator / responder model pickers used to live on this
    // screen for fan_out; they're back on the Run page (above the
    // prompt) so the Scope step stays focused on scope + language.

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "secondary_scope",
            title = if (metaPrompt.category == "fan_out") "Fan Out - scope" else "Scope",
            subject = kindLabel,
            onBackClick = onBack
        )
        com.ai.ui.shared.HardcodedSubjectRow(kindLabel)
        // Primary CTA hoisted to the top — anchored just below the
        // green subject row so the "advance" affordance stays one
        // tap away regardless of how far the scope list scrolls.
        // Behaviour / colors / gating unchanged from the original
        // bottom-of-page version.
        Button(
            onClick = {
                val scope = when (scopeMode) {
                    ScopeMode.ALL -> SecondaryScope.AllReports
                    ScopeMode.TOP_RANKED -> SecondaryScope.TopRanked(countInt, selectedRerank)
                    ScopeMode.MANUAL -> SecondaryScope.Manual(
                        manualPicked.filterValues { it }.keys.toSet()
                    )
                }
                val langScope = when {
                    languages.isEmpty() -> SecondaryLanguageScope.AllPresent
                    isSingleLanguage -> {
                        // Fan-out / rerank / moderation each take
                        // exactly one language; the runners read the
                        // single entry of Selected. Empty string =
                        // Original (untranslated).
                        SecondaryLanguageScope.Selected(setOf(fanOutPickedLanguage))
                    }
                    allLanguages -> SecondaryLanguageScope.AllPresent
                    else -> {
                        // The set holds English-name keys for translations
                        // and "" (the empty string) for the original — see
                        // SecondaryLanguageScope.Selected's doc comment.
                        val picked = pickedLanguages.filterValues { it }.keys.toMutableSet()
                        if (pickedOriginal) picked.add("")
                        SecondaryLanguageScope.Selected(picked)
                    }
                }
                onContinue(scope, langScope)
            },
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Continue", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Rerank and moderation always operate on every successful
            // agent — there's no per-row scope to pick. Skip the
            // explainer + the All/TopRanked/Manual card entirely so the
            // user lands directly on the language picker below.
            if (!isLanguageOnly) {
                Text(
                    "Choose which model results to feed into ${kindLabel}.",
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
                // Top-Ranked scope makes sense for chat / fan out prompts
                // (both pick a subset of report-models as input). Rerank
                // and moderation runs always operate on the full set.
                if (supportsSubsetScope && reranks.isNotEmpty()) {
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
            } else if (languages.isEmpty()) {
                // No translations on this report — there is literally
                // nothing to pick on this screen. Tell the user so the
                // empty body isn't confusing; the Continue button at
                // the top advances to the model picker.
                Text(
                    "No translations on this report. Continue to pick a model for $kindLabel.",
                    fontSize = 12.sp, color = AppColors.TextTertiary
                )
            }

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
                                // agentName carries "Provider / Model"
                                // verbatim; rebuild from a.provider /
                                // a.model so the Model name layout
                                // setting wins.
                                val agentProv = AppService.findById(a.provider)?.id ?: a.provider
                                Text(
                                    com.ai.ui.shared.modelLabel(agentProv, a.model, separator = " / "),
                                    fontSize = 13.sp, color = Color.White,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Translation language picker: meta, fan_out, rerank, and
            // moderation each surface it when the report has translation
            // rows so the user can target the run at a specific
            // language instead of always falling back to the original.
            // Meta allows multi-select; fan_out / rerank / moderation
            // are single-select (one run = one source language).
            if ((isMetaCategory || isSingleLanguage) && languages.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text("Languages", fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                if (isSingleLanguage) {
                    // Single-language: pick which source bodies / prompt
                    // feed the run. Original first, then each translation.
                    val hint = when {
                        isFanOut -> "Fan-out runs on one language at a time. Pick which source bodies to feed each pair."
                        isRerank -> "Rerank scores one language's bodies. Pick which set to rank."
                        else -> "Moderation classifies one language's bodies. Pick which set to classify."
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(hint, fontSize = 11.sp, color = AppColors.TextTertiary)
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { fanOutPickedLanguage = "" },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = fanOutPickedLanguage == "", onClick = { fanOutPickedLanguage = "" })
                                Text("Original", fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            languages.forEach { (lang, native) ->
                                val selected = fanOutPickedLanguage == lang
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { fanOutPickedLanguage = lang },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = selected, onClick = { fanOutPickedLanguage = lang })
                                    val label = native?.takeIf { it.isNotBlank() && it != lang }?.let { "$lang · $it" } ?: lang
                                    Text(label, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                } else {
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
                                // Original (untranslated) source — first row.
                                // Tick = include in the run, untick = run only
                                // on the chosen translations.
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { pickedOriginal = !pickedOriginal },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = pickedOriginal, onCheckedChange = { pickedOriginal = it })
                                    Text("Original", fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
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
        }

        // (Continue button hoisted to the top — see above.)
    }
}

private enum class ScopeMode { ALL, TOP_RANKED, MANUAL }

private fun rerankLabel(r: SecondaryResult): String {
    val provider = AppService.findById(r.providerId)?.id ?: r.providerId
    return "$provider · ${r.model}"
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
