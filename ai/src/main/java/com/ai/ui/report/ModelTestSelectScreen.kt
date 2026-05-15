package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/**
 * Provider picker shown before a fresh "Test all models" run. Lists
 * every active provider with a non-blank API key (the same candidate
 * set the engine enumerates), each with a checkbox; "Start test"
 * launches a run scoped to the checked providers. Reached from L1's
 * "Test all models" button.
 *
 * [providers] is each [AppService] paired with its configured-model
 * count, in registry order.
 */
@Composable
internal fun ModelTestSelectScreen(
    providers: List<Pair<AppService, Int>>,
    onStart: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    var selected by remember {
        mutableStateOf(providers.map { it.first.id }.toSet())
    }
    // Alphabetical by provider id (case-insensitive) — registry order
    // is a mix of priority / historical, which makes hunting for a
    // specific provider a scan job. Cheap, stable, keyed on the input
    // identity so memoised.
    val sortedProviders = remember(providers) {
        providers.sortedBy { it.first.id.lowercase() }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "test_all_models_select",
            title = "Test all models",
            onBackClick = onBack
        )

        Text(
            "Pick the providers to test — every configured model of each " +
                "checked provider gets probed with a short \"reply OK\" prompt.",
            fontSize = 12.sp, color = AppColors.TextTertiary
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (providers.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No active providers with an API key.",
                    color = AppColors.TextTertiary, fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = providers.map { it.first.id }.toSet() }) {
                    Text("Select all", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                TextButton(onClick = { selected = emptySet() }) {
                    Text("Select none", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(sortedProviders, key = { it.first.id }) { (service, modelCount) ->
                    val checked = service.id in selected
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                selected = if (checked) selected - service.id
                                else selected + service.id
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                selected = if (checked) selected - service.id
                                else selected + service.id
                            },
                            colors = CheckboxDefaults.colors(checkedColor = AppColors.Blue)
                        )
                        Text(
                            service.id,
                            fontSize = 14.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                        Text(
                            "$modelCount model${if (modelCount == 1) "" else "s"}",
                            fontSize = 11.sp, color = AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onStart(selected) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
        ) {
            Text(
                if (selected.isEmpty()) "Start test"
                else "Start test (${selected.size} provider${if (selected.size == 1) "" else "s"})",
                fontSize = 13.sp, maxLines = 1, softWrap = false
            )
        }
    }
}
