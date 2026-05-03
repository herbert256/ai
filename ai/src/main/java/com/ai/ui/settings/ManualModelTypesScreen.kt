package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.ModelType
import com.ai.model.ModelTypeOverride
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.CrudListScreen
import com.ai.ui.shared.TitleBar
import java.util.UUID

/**
 * AI Setup → Manual model types overrides. CRUD list of (provider, model, type)
 * triples that win over the autodetected type stored on ProviderConfig.modelTypes.
 *
 * Useful when the heuristic and native list APIs both miss — e.g. a provider whose
 * embedding model name doesn't contain "embed", or a vision-capable chat model
 * the user wants treated as IMAGE for routing purposes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualModelTypesScreen(
    aiSettings: Settings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSave: (Settings) -> Unit
) {
    BackHandler { onBack() }
    var editing by remember { mutableStateOf<ModelTypeOverride?>(null) }
    var addingNew by remember { mutableStateOf(false) }

    if (addingNew || editing != null) {
        ManualModelTypeEditScreen(
            initial = editing,
            aiSettings = aiSettings,
            onCancel = { editing = null; addingNew = false },
            onSave = { saved ->
                val list = aiSettings.modelTypeOverrides
                val updated = if (editing != null) list.map { if (it.id == saved.id) saved else it }
                              else list + saved
                onSave(aiSettings.withModelTypeOverrides(updated))
                editing = null
                addingNew = false
            }
        )
        return
    }

    CrudListScreen(
        title = "Manual model types",
        items = aiSettings.modelTypeOverrides,
        addLabel = "+ Add override",
        emptyMessage = "No overrides yet. Add one to force a specific type for a provider/model pair.",
        sortKey = { "${it.providerId}/${it.modelId}" },
        itemTitle = { "${it.providerId} / ${it.modelId}" },
        itemSubtitle = {
            val flags = buildString {
                if (it.supportsVision) append(" 👁")
                if (it.supportsWebSearch) append(" 🌐")
                if (it.supportsReasoning) append(" 🧠")
            }
            "→ ${it.type}$flags"
        },
        onAdd = { addingNew = true },
        onEdit = { editing = it },
        onDelete = { override ->
            onSave(aiSettings.withModelTypeOverrides(aiSettings.modelTypeOverrides.filter { it.id != override.id }))
        },
        onBack = onBack,
        onHome = onNavigateHome,
        deleteEntityType = "Override",
        deleteEntityName = { "${it.providerId}/${it.modelId}" },
        itemKey = { it.id }
    )
}

/**
 * Direct-entry wrapper around [ManualModelTypeEditScreen] for the "Add manual
 * override" link on Model Info — opens the same form pre-filled with the
 * given provider/model. If an override already exists for that pair, it's
 * loaded for editing rather than creating a duplicate; otherwise a fresh
 * entry is initialized with the current heuristic flags so the user only
 * needs to confirm or change them.
 */
@Composable
fun ManualModelOverrideEntryScreen(
    aiSettings: Settings,
    providerId: String,
    modelId: String,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit
) {
    val existing = aiSettings.modelTypeOverrides.firstOrNull {
        it.providerId == providerId && it.modelId == modelId
    }
    ManualModelTypeEditScreen(
        initial = existing,
        aiSettings = aiSettings,
        initialProviderId = providerId,
        initialModelId = modelId,
        onCancel = onBack,
        onSave = { saved ->
            val list = aiSettings.modelTypeOverrides
            val updated = if (existing != null) list.map { if (it.id == saved.id) saved else it }
                          else list + saved
            onSave(aiSettings.withModelTypeOverrides(updated))
            onBack()
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualModelTypeEditScreen(
    initial: ModelTypeOverride?,
    aiSettings: Settings,
    onCancel: () -> Unit,
    onSave: (ModelTypeOverride) -> Unit,
    initialProviderId: String? = null,
    initialModelId: String? = null
) {
    BackHandler { onCancel() }

    val allProviders = remember { AppService.entries.sortedBy { it.displayName } }
    var providerId by remember { mutableStateOf(initial?.providerId ?: initialProviderId ?: allProviders.firstOrNull()?.id ?: "") }
    var modelId by remember { mutableStateOf(initial?.modelId ?: initialModelId ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: ModelType.CHAT) }
    var supportsVision by remember { mutableStateOf(initial?.supportsVision ?: false) }
    var supportsWebSearch by remember { mutableStateOf(initial?.supportsWebSearch ?: false) }
    var supportsReasoning by remember { mutableStateOf(initial?.supportsReasoning ?: false) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    val canSave = providerId.isNotBlank() && modelId.trim().isNotBlank()
    val knownModels = remember(providerId, aiSettings) {
        AppService.findById(providerId)?.let { aiSettings.getProvider(it).models.sorted() } ?: emptyList()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(
            title = if (initial == null) "Add override" else "Edit override",
            onBackClick = onCancel,
            onAiClick = onCancel
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Provider picker — ExposedDropdownMenu over all known providers.
            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = !providerExpanded }
            ) {
                OutlinedTextField(
                    value = AppService.findById(providerId)?.displayName ?: providerId,
                    onValueChange = {}, readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
                    colors = AppColors.outlinedFieldColors()
                )
                ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                    allProviders.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.displayName) },
                            onClick = {
                                if (providerId != p.id) modelId = ""
                                providerId = p.id
                                providerExpanded = false
                            }
                        )
                    }
                }
            }

            // Model id picker — readOnly dropdown over the provider's known
            // models, mirroring the Provider field. Falls back to whatever
            // modelId is currently set to (so an override for an unfetched
            // model id is preserved on edit) and shows a hint when the
            // provider has no known models yet.
            ExposedDropdownMenuBox(
                expanded = modelExpanded && knownModels.isNotEmpty(),
                onExpandedChange = { if (knownModels.isNotEmpty()) modelExpanded = !modelExpanded }
            ) {
                val provider = AppService.findById(providerId)
                OutlinedTextField(
                    value = modelId,
                    onValueChange = {}, readOnly = true,
                    label = { Text("Model") },
                    placeholder = { Text(if (knownModels.isEmpty()) "No models — fetch this provider first" else "Pick a model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, knownModels.isNotEmpty()),
                    colors = AppColors.outlinedFieldColors()
                )
                ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                    knownModels.forEach { m ->
                        val vision = provider != null && aiSettings.isVisionCapable(provider, m)
                        val websearch = provider != null && aiSettings.isWebSearchCapable(provider, m)
                        val reasoning = provider != null && aiSettings.isReasoningCapable(provider, m)
                        val suffix = buildString {
                            if (vision) append(" 👁")
                            if (websearch) append(" 🌐")
                            if (reasoning) append(" 🧠")
                        }
                        DropdownMenuItem(
                            text = { Text("$m$suffix") },
                            onClick = { modelId = m; modelExpanded = false }
                        )
                    }
                }
            }

            // Type picker.
            Text("Type", fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Two columns of FilterChips — ALL types are 9 entries, fits nicely.
                ModelType.ALL.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { t ->
                            FilterChip(
                                selected = type == t,
                                onClick = { type = t },
                                label = { Text(t, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // Capability flags — wired into Settings.isVisionCapable /
            // isWebSearchCapable so a tick here surfaces the 👁 / 🌐 badge
            // anywhere this model appears, even when ProviderConfig
            // .visionModels / .webSearchModels don't list it.
            Text("Capabilities", fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp).clickable { supportsVision = !supportsVision },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = supportsVision, onCheckedChange = { supportsVision = it })
                Spacer(modifier = Modifier.width(4.dp))
                Text("Supports vision (image input) 👁", color = Color.White, fontSize = 13.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp).clickable { supportsWebSearch = !supportsWebSearch },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = supportsWebSearch, onCheckedChange = { supportsWebSearch = it })
                Spacer(modifier = Modifier.width(4.dp))
                Text("Supports web-search tool 🌐", color = Color.White, fontSize = 13.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp).clickable { supportsReasoning = !supportsReasoning },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = supportsReasoning, onCheckedChange = { supportsReasoning = it })
                Spacer(modifier = Modifier.width(4.dp))
                Text("Supports thinking 🧠", color = Color.White, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Cancel", maxLines = 1, softWrap = false) }
            Button(
                onClick = {
                    onSave(
                        ModelTypeOverride(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            providerId = providerId,
                            modelId = modelId.trim(),
                            type = type,
                            supportsVision = supportsVision,
                            supportsWebSearch = supportsWebSearch,
                            supportsReasoning = supportsReasoning
                        )
                    )
                },
                enabled = canSave,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) { Text("Save", maxLines = 1, softWrap = false, color = Color.White) }
        }
    }
}
