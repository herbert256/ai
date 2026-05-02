package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryScope
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Inserted between the Summarize / Compare button and the model picker
 * when the parent report has at least one rerank result. Lets the user
 * narrow the input set to the top-N entries of a chosen rerank — useful
 * when the report has many models and you only want a summary / compare
 * over the strongest answers.
 *
 * Rerank itself never sees this screen; it ranks the full set by
 * definition.
 */
@Composable
internal fun SecondaryScopeScreen(
    kind: SecondaryKind,
    reranks: List<SecondaryResult>,
    totalReports: Int,
    onContinue: (SecondaryScope) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val kindLabel = when (kind) {
        SecondaryKind.SUMMARIZE -> "Summarize"
        SecondaryKind.COMPARE -> "Compare"
        SecondaryKind.RERANK -> "Rerank" // never reached but exhaustive
        SecondaryKind.MODERATION -> "Moderation" // also never reached — moderation never enters the scope screen
        SecondaryKind.TRANSLATE -> "Translate" // never reached — translation has its own flow
    }
    var topOnly by remember { mutableStateOf(false) }
    var countText by remember {
        mutableStateOf(minOf(3, totalReports.coerceAtLeast(1)).toString())
    }
    var selectedRerank by remember { mutableStateOf(reranks.firstOrNull()?.id ?: "") }
    var rerankDropdownOpen by remember { mutableStateOf(false) }

    val countInt = countText.toIntOrNull()?.coerceIn(1, totalReports.coerceAtLeast(1)) ?: 0
    val canContinue = !topOnly || (countInt > 0 && selectedRerank.isNotBlank())

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "$kindLabel — scope", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "Choose which model results $kindLabel.lowercase() should look at.",
            fontSize = 12.sp, color = AppColors.TextTertiary
        )
        Spacer(modifier = Modifier.height(12.dp))

        ScopeOption(
            selected = !topOnly,
            label = "All model reports",
            sublabel = "Use every successful result from this report ($totalReports)",
            onSelect = { topOnly = false }
        )
        Spacer(modifier = Modifier.height(8.dp))
        ScopeOption(
            selected = topOnly,
            label = "Only top ranked reports",
            sublabel = "Restrict the input to the top-N entries of a rerank",
            onSelect = { topOnly = true }
        )

        if (topOnly) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { newValue ->
                            // Strip non-digits so the field can't be put in
                            // an unparseable state.
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

        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = {
                val scope = if (topOnly) SecondaryScope.TopRanked(countInt, selectedRerank)
                else SecondaryScope.AllReports
                onContinue(scope)
            },
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Continue", maxLines = 1, softWrap = false) }
    }
}

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
