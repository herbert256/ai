package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Edit report" — the full-screen overview opened by the bottom-bar ✏️ icon
 * on the Manage hub. Surfaces every editable facet of a report in one place:
 * the report icon, the short + long title, the parameters preset, the system
 * prompt, and the prompt body, plus three buttons that branch to model editing
 * and to the [ReportEditIconsScreen] / [ReportEditTitlesScreen] aggregate
 * screens.
 *
 * Drawn as a layer-on-top overlay in [ReportRunScreen] (mirrors
 * [ReportGetInfoScreen]); each pencil sets an existing [ReportsScreenState]
 * flag whose early-return overlay renders over this layer, so Back unwinds one
 * level at a time (sub-editor → overview → hub). [publishBottomBar] is false so
 * the hub keeps publishing its own bottom bar underneath.
 */
@Composable
internal fun ReportEditOverviewScreen(
    reportId: String,
    uiState: UiState,
    st: ReportsScreenState,
    editSystemPromptTrigger: () -> Unit,
    onEditModels: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val aiSettings = uiState.aiSettings

    // Only the icon needs a disk read (it isn't on UiState). Re-read on every
    // iconRefreshTick so a Find-alt pick reflects when this layer repaints.
    val icon by produceState<String?>(initialValue = null, reportId, uiState.iconRefreshTick) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId)?.icon }
    }
    val shortTitle = uiState.genericPromptTitle
    val longTitle = uiState.genericPromptTitleLong
    val prompt = uiState.genericPromptText
    val paramsLabel = aiSettings.getParametersByIds(uiState.reportParametersIds)
        .joinToString(", ") { it.name }
        .ifBlank { "*NONE" }
    val systemPromptLabel = uiState.reportSystemPromptId
        ?.let { aiSettings.getSystemPromptById(it)?.name }
        ?.takeIf { it.isNotBlank() } ?: "*NONE"

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_edit_overview",
            title = "Edit report",
            subject = shortTitle.ifBlank { "Icon, titles, parameters, prompt" },
            onBackClick = onBack,
            publishBottomBar = false
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Row 1 — report icon, big + centred, with its edit pencil at
            // the right edge (all pencils share a right-aligned column).
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    icon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon, fontSize = 56.sp, color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
                EditPencil(Modifier.align(Alignment.CenterEnd)) { st.showIconDetail.value = true }
            }

            // Rows 2 & 3 — short title then long title, centred. Each pencil
            // opens its own dedicated editor.
            CenteredEditRow(
                text = shortTitle.ifBlank { "*NONE" },
                onEdit = { st.showEditShortTitle.value = true }
            )
            CenteredEditRow(
                text = longTitle.ifBlank { "*NONE" },
                onEdit = { st.showEditLongTitle.value = true }
            )

            // Parameters + System prompt — "Label: value" with a pencil.
            LabeledEditRow(label = "Parameters", value = paramsLabel) {
                st.showEditParameters.value = true
            }
            LabeledEditRow(label = "System prompt", value = systemPromptLabel) {
                editSystemPromptTrigger()
            }

            // Prompt body card with a pencil in the header.
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.CardBackground)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Prompt", fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                    )
                    EditPencil { st.showEditPrompt.value = true }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    prompt.ifBlank { "(no prompt)" }, fontSize = 13.sp, color = Color.White,
                    lineHeight = 18.sp, maxLines = 8, overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onEditModels,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.SecondaryAccent)
            ) { Text("Edit models", maxLines = 1, softWrap = false) }
            Button(
                onClick = { st.showEditIconsList.value = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.SecondaryAccent)
            ) { Text("Edit icons", maxLines = 1, softWrap = false) }
            Button(
                onClick = { st.showEditTitlesList.value = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.SecondaryAccent)
            ) { Text("Edit titles", maxLines = 1, softWrap = false) }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** A 20sp ✏️ glyph that runs [onEdit] when tapped. */
@Composable
private fun EditPencil(modifier: Modifier = Modifier, onEdit: () -> Unit) {
    Text(com.ai.data.MetadataIconsHolder.current.edit, fontSize = 20.sp, modifier = modifier.clickable { onEdit() }.padding(4.dp))
}

/** Centred text (a title) with its edit pencil pinned to the right edge. */
@Composable
private fun CenteredEditRow(text: String, onEdit: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text, fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            // Leave room on both sides so the centred title never runs under
            // the right-aligned pencil.
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 44.dp)
        )
        EditPencil(Modifier.align(Alignment.CenterEnd), onEdit)
    }
}

/** A "Label: value" line that fills the width with the value, then an edit
 *  pencil at the trailing edge. */
@Composable
private fun LabeledEditRow(label: String, value: String, onEdit: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label: $value", fontSize = 14.sp, color = Color.White,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        EditPencil(onEdit = onEdit)
    }
}
