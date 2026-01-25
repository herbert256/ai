package com.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.data.AiService
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Data class for provider settings in JSON export/import.
 */
data class ProviderConfigExport(
    val modelSource: String,  // "API" or "MANUAL"
    val manualModels: List<String>,
    val apiKey: String = ""   // API key for the provider
)

/**
 * Data class for agent parameters in JSON export/import (version 5+).
 */
data class AgentParametersExport(
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val systemPrompt: String? = null,
    val stopSequences: List<String>? = null,
    val seed: Int? = null,
    val responseFormatJson: Boolean = false,
    val searchEnabled: Boolean = false,
    val returnCitations: Boolean = true,
    val searchRecency: String? = null
)

/**
 * Data class for agent in JSON export/import.
 * Version 4: Basic agent info only.
 * Version 5: Added parameters field.
 */
data class AgentExport(
    val id: String,
    val name: String,
    val provider: String,  // Provider enum name (CHATGPT, CLAUDE, etc.)
    val model: String,
    val apiKey: String,
    // Parameters (version 5+)
    val parameters: AgentParametersExport? = null,
    // Legacy fields from version 3 (ignored on import, included for compatibility)
    val gamePromptId: String? = null,
    val serverPlayerPromptId: String? = null,
    val otherPlayerPromptId: String? = null
)

/**
 * Data class for the complete AI configuration export.
 * Version 5: Providers and agents with parameters.
 * Also supports importing version 3/4 (legacy formats - prompts ignored, parameters default).
 */
data class AiConfigExport(
    val version: Int = 5,
    val providers: Map<String, ProviderConfigExport>,
    val agents: List<AgentExport>,
    // Legacy field from version 3 (ignored on import)
    val prompts: List<Any>? = null
)

/**
 * Data class for API key export entry.
 */
data class ApiKeyEntry(
    val service: String,
    val apiKey: String
)

/**
 * Export AI configuration to a file and share via Android share sheet.
 * Exports providers (model config) and agents.
 */
fun exportAiConfigToFile(context: Context, aiSettings: AiSettings) {
    // Build providers map (model source, manual models, and API key per provider)
    val providers = mapOf(
        "CHATGPT" to ProviderConfigExport(aiSettings.chatGptModelSource.name, aiSettings.chatGptManualModels, aiSettings.chatGptApiKey),
        "CLAUDE" to ProviderConfigExport(ModelSource.MANUAL.name, aiSettings.claudeManualModels, aiSettings.claudeApiKey),
        "GEMINI" to ProviderConfigExport(aiSettings.geminiModelSource.name, aiSettings.geminiManualModels, aiSettings.geminiApiKey),
        "GROK" to ProviderConfigExport(aiSettings.grokModelSource.name, aiSettings.grokManualModels, aiSettings.grokApiKey),
        "GROQ" to ProviderConfigExport(aiSettings.groqModelSource.name, aiSettings.groqManualModels, aiSettings.groqApiKey),
        "DEEPSEEK" to ProviderConfigExport(aiSettings.deepSeekModelSource.name, aiSettings.deepSeekManualModels, aiSettings.deepSeekApiKey),
        "MISTRAL" to ProviderConfigExport(aiSettings.mistralModelSource.name, aiSettings.mistralManualModels, aiSettings.mistralApiKey),
        "PERPLEXITY" to ProviderConfigExport(ModelSource.MANUAL.name, aiSettings.perplexityManualModels, aiSettings.perplexityApiKey),
        "TOGETHER" to ProviderConfigExport(aiSettings.togetherModelSource.name, aiSettings.togetherManualModels, aiSettings.togetherApiKey),
        "OPENROUTER" to ProviderConfigExport(aiSettings.openRouterModelSource.name, aiSettings.openRouterManualModels, aiSettings.openRouterApiKey),
        "SILICONFLOW" to ProviderConfigExport(ModelSource.MANUAL.name, aiSettings.siliconFlowManualModels, aiSettings.siliconFlowApiKey),
        "ZAI" to ProviderConfigExport(ModelSource.MANUAL.name, aiSettings.zaiManualModels, aiSettings.zaiApiKey),
        "DUMMY" to ProviderConfigExport(ModelSource.MANUAL.name, aiSettings.dummyManualModels, aiSettings.dummyApiKey)
    )

    // Convert agents with parameters (handle null parameters for legacy agents)
    val agents = aiSettings.agents.map { agent ->
        val params = agent.parameters
        AgentExport(
            id = agent.id,
            name = agent.name,
            provider = agent.provider.name,
            model = agent.model,
            apiKey = agent.apiKey,
            parameters = if (params != null) {
                AgentParametersExport(
                    temperature = params.temperature,
                    maxTokens = params.maxTokens,
                    topP = params.topP,
                    topK = params.topK,
                    frequencyPenalty = params.frequencyPenalty,
                    presencePenalty = params.presencePenalty,
                    systemPrompt = params.systemPrompt,
                    stopSequences = params.stopSequences,
                    seed = params.seed,
                    responseFormatJson = params.responseFormatJson,
                    searchEnabled = params.searchEnabled,
                    returnCitations = params.returnCitations,
                    searchRecency = params.searchRecency
                )
            } else {
                AgentParametersExport()  // Default empty parameters
            }
        )
    }

    val export = AiConfigExport(
        providers = providers,
        agents = agents
    )

    val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
    val json = gson.toJson(export)

    try {
        // Create file in cache/ai_analysis directory (must match FileProvider paths)
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val fileName = "ai_config_$timestamp.json"
        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val file = java.io.File(cacheDir, fileName)
        file.writeText(json)

        // Get URI via FileProvider
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        // Create share intent
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(android.content.Intent.createChooser(shareIntent, "Export AI Configuration"))
    } catch (e: Exception) {
        Toast.makeText(context, "Error exporting: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Export API keys only to clipboard as JSON array.
 */
fun exportApiKeysToClipboard(context: Context, aiSettings: AiSettings) {
    val keys = mutableListOf<ApiKeyEntry>()

    if (aiSettings.chatGptApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("ChatGPT", aiSettings.chatGptApiKey))
    }
    if (aiSettings.claudeApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Claude", aiSettings.claudeApiKey))
    }
    if (aiSettings.geminiApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Gemini", aiSettings.geminiApiKey))
    }
    if (aiSettings.grokApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Grok", aiSettings.grokApiKey))
    }
    if (aiSettings.groqApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Groq", aiSettings.groqApiKey))
    }
    if (aiSettings.deepSeekApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("DeepSeek", aiSettings.deepSeekApiKey))
    }
    if (aiSettings.mistralApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Mistral", aiSettings.mistralApiKey))
    }
    if (aiSettings.perplexityApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Perplexity", aiSettings.perplexityApiKey))
    }
    if (aiSettings.togetherApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Together", aiSettings.togetherApiKey))
    }
    if (aiSettings.openRouterApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("OpenRouter", aiSettings.openRouterApiKey))
    }
    if (aiSettings.siliconFlowApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("SiliconFlow", aiSettings.siliconFlowApiKey))
    }
    if (aiSettings.zaiApiKey.isNotBlank()) {
        keys.add(ApiKeyEntry("Z.AI", aiSettings.zaiApiKey))
    }

    val gson = Gson()
    val json = gson.toJson(keys)

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("API Keys", json)
    clipboard.setPrimaryClip(clip)

    Toast.makeText(context, "${keys.size} API keys copied to clipboard", Toast.LENGTH_SHORT).show()
}

/**
 * Import AI configuration from clipboard JSON.
 * Supports version 3/4 (legacy formats) and version 5 (current with parameters).
 * Prompts from version 3 are ignored.
 */
fun importAiConfigFromClipboard(context: Context, currentSettings: AiSettings): AiSettings? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = clipboard.primaryClip

    if (clipData == null || clipData.itemCount == 0) {
        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        return null
    }

    val json = clipData.getItemAt(0).text?.toString()
    if (json.isNullOrBlank()) {
        Toast.makeText(context, "No text in clipboard", Toast.LENGTH_SHORT).show()
        return null
    }

    return try {
        val gson = Gson()
        val export = gson.fromJson(json, AiConfigExport::class.java)

        if (export.version !in 3..5) {
            Toast.makeText(context, "Unsupported configuration version: ${export.version}. Expected version 3, 4, or 5.", Toast.LENGTH_LONG).show()
            return null
        }

        // Import agents with parameters (prompt fields from version 3 are ignored)
        val agents = export.agents.mapNotNull { agentExport ->
            val provider = try {
                AiService.valueOf(agentExport.provider)
            } catch (e: IllegalArgumentException) {
                null  // Skip agents with unknown providers
            }
            provider?.let {
                AiAgent(
                    id = agentExport.id,
                    name = agentExport.name,
                    provider = it,
                    model = agentExport.model,
                    apiKey = agentExport.apiKey,
                    parameters = agentExport.parameters?.let { p ->
                        AiAgentParameters(
                            temperature = p.temperature,
                            maxTokens = p.maxTokens,
                            topP = p.topP,
                            topK = p.topK,
                            frequencyPenalty = p.frequencyPenalty,
                            presencePenalty = p.presencePenalty,
                            systemPrompt = p.systemPrompt,
                            stopSequences = p.stopSequences,
                            seed = p.seed,
                            responseFormatJson = p.responseFormatJson,
                            searchEnabled = p.searchEnabled,
                            returnCitations = p.returnCitations,
                            searchRecency = p.searchRecency
                        )
                    } ?: AiAgentParameters()
                )
            }
        }

        // Import provider settings
        var settings = currentSettings.copy(
            agents = agents
        )

        // Update provider model sources, manual models, and API keys
        export.providers["CHATGPT"]?.let { p ->
            settings = settings.copy(
                chatGptModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                chatGptManualModels = p.manualModels,
                chatGptApiKey = p.apiKey
            )
        }
        export.providers["CLAUDE"]?.let { p ->
            settings = settings.copy(
                claudeManualModels = p.manualModels,
                claudeApiKey = p.apiKey
            )
        }
        export.providers["GEMINI"]?.let { p ->
            settings = settings.copy(
                geminiModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                geminiManualModels = p.manualModels,
                geminiApiKey = p.apiKey
            )
        }
        export.providers["GROK"]?.let { p ->
            settings = settings.copy(
                grokModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                grokManualModels = p.manualModels,
                grokApiKey = p.apiKey
            )
        }
        export.providers["GROQ"]?.let { p ->
            settings = settings.copy(
                groqModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                groqManualModels = p.manualModels,
                groqApiKey = p.apiKey
            )
        }
        export.providers["DEEPSEEK"]?.let { p ->
            settings = settings.copy(
                deepSeekModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                deepSeekManualModels = p.manualModels,
                deepSeekApiKey = p.apiKey
            )
        }
        export.providers["MISTRAL"]?.let { p ->
            settings = settings.copy(
                mistralModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                mistralManualModels = p.manualModels,
                mistralApiKey = p.apiKey
            )
        }
        export.providers["PERPLEXITY"]?.let { p ->
            settings = settings.copy(
                perplexityManualModels = p.manualModels,
                perplexityApiKey = p.apiKey
            )
        }
        export.providers["TOGETHER"]?.let { p ->
            settings = settings.copy(
                togetherModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                togetherManualModels = p.manualModels,
                togetherApiKey = p.apiKey
            )
        }
        export.providers["OPENROUTER"]?.let { p ->
            settings = settings.copy(
                openRouterModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                openRouterManualModels = p.manualModels,
                openRouterApiKey = p.apiKey
            )
        }
        export.providers["SILICONFLOW"]?.let { p ->
            settings = settings.copy(
                siliconFlowManualModels = p.manualModels,
                siliconFlowApiKey = p.apiKey
            )
        }
        export.providers["ZAI"]?.let { p ->
            settings = settings.copy(
                zaiManualModels = p.manualModels,
                zaiApiKey = p.apiKey
            )
        }
        export.providers["DUMMY"]?.let { p ->
            settings = settings.copy(
                dummyManualModels = p.manualModels,
                dummyApiKey = p.apiKey
            )
        }

        // Count imported API keys
        val importedApiKeys = export.providers.values.count { it.apiKey.isNotBlank() }
        Toast.makeText(context, "Imported ${agents.size} agents, $importedApiKeys API keys", Toast.LENGTH_SHORT).show()
        settings
    } catch (e: JsonSyntaxException) {
        Toast.makeText(context, "Invalid AI configuration format", Toast.LENGTH_SHORT).show()
        null
    } catch (e: Exception) {
        Toast.makeText(context, "Error importing configuration: ${e.message}", Toast.LENGTH_SHORT).show()
        null
    }
}

/**
 * Import AI configuration from a file URI.
 * Supports version 3/4 (legacy formats) and version 5 (current with parameters).
 * Prompts from version 3 are ignored.
 */
fun importAiConfigFromFile(context: Context, uri: Uri, currentSettings: AiSettings): AiSettings? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        if (inputStream == null) {
            Toast.makeText(context, "Could not open file", Toast.LENGTH_SHORT).show()
            return null
        }

        val json = BufferedReader(InputStreamReader(inputStream)).use { reader ->
            reader.readText()
        }

        if (json.isBlank()) {
            Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show()
            return null
        }

        val gson = Gson()
        val export = gson.fromJson(json, AiConfigExport::class.java)

        if (export.version !in 3..5) {
            Toast.makeText(context, "Unsupported configuration version: ${export.version}. Expected version 3, 4, or 5.", Toast.LENGTH_LONG).show()
            return null
        }

        // Import agents with parameters (prompt fields from version 3 are ignored)
        val agents = export.agents.mapNotNull { agentExport ->
            val provider = try {
                AiService.valueOf(agentExport.provider)
            } catch (e: IllegalArgumentException) {
                null  // Skip agents with unknown providers
            }
            provider?.let {
                AiAgent(
                    id = agentExport.id,
                    name = agentExport.name,
                    provider = it,
                    model = agentExport.model,
                    apiKey = agentExport.apiKey,
                    parameters = agentExport.parameters?.let { p ->
                        AiAgentParameters(
                            temperature = p.temperature,
                            maxTokens = p.maxTokens,
                            topP = p.topP,
                            topK = p.topK,
                            frequencyPenalty = p.frequencyPenalty,
                            presencePenalty = p.presencePenalty,
                            systemPrompt = p.systemPrompt,
                            stopSequences = p.stopSequences,
                            seed = p.seed,
                            responseFormatJson = p.responseFormatJson,
                            searchEnabled = p.searchEnabled,
                            returnCitations = p.returnCitations,
                            searchRecency = p.searchRecency
                        )
                    } ?: AiAgentParameters()
                )
            }
        }

        // Import provider settings
        var settings = currentSettings.copy(
            agents = agents
        )

        // Update provider model sources, manual models, and API keys
        export.providers["CHATGPT"]?.let { p ->
            settings = settings.copy(
                chatGptModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                chatGptManualModels = p.manualModels,
                chatGptApiKey = p.apiKey
            )
        }
        export.providers["CLAUDE"]?.let { p ->
            settings = settings.copy(
                claudeManualModels = p.manualModels,
                claudeApiKey = p.apiKey
            )
        }
        export.providers["GEMINI"]?.let { p ->
            settings = settings.copy(
                geminiModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                geminiManualModels = p.manualModels,
                geminiApiKey = p.apiKey
            )
        }
        export.providers["GROK"]?.let { p ->
            settings = settings.copy(
                grokModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                grokManualModels = p.manualModels,
                grokApiKey = p.apiKey
            )
        }
        export.providers["GROQ"]?.let { p ->
            settings = settings.copy(
                groqModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                groqManualModels = p.manualModels,
                groqApiKey = p.apiKey
            )
        }
        export.providers["DEEPSEEK"]?.let { p ->
            settings = settings.copy(
                deepSeekModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                deepSeekManualModels = p.manualModels,
                deepSeekApiKey = p.apiKey
            )
        }
        export.providers["MISTRAL"]?.let { p ->
            settings = settings.copy(
                mistralModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                mistralManualModels = p.manualModels,
                mistralApiKey = p.apiKey
            )
        }
        export.providers["PERPLEXITY"]?.let { p ->
            settings = settings.copy(
                perplexityManualModels = p.manualModels,
                perplexityApiKey = p.apiKey
            )
        }
        export.providers["TOGETHER"]?.let { p ->
            settings = settings.copy(
                togetherModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                togetherManualModels = p.manualModels,
                togetherApiKey = p.apiKey
            )
        }
        export.providers["OPENROUTER"]?.let { p ->
            settings = settings.copy(
                openRouterModelSource = try { ModelSource.valueOf(p.modelSource) } catch (e: Exception) { ModelSource.API },
                openRouterManualModels = p.manualModels,
                openRouterApiKey = p.apiKey
            )
        }
        export.providers["SILICONFLOW"]?.let { p ->
            settings = settings.copy(
                siliconFlowManualModels = p.manualModels,
                siliconFlowApiKey = p.apiKey
            )
        }
        export.providers["ZAI"]?.let { p ->
            settings = settings.copy(
                zaiManualModels = p.manualModels,
                zaiApiKey = p.apiKey
            )
        }
        export.providers["DUMMY"]?.let { p ->
            settings = settings.copy(
                dummyManualModels = p.manualModels,
                dummyApiKey = p.apiKey
            )
        }

        // Count imported API keys
        val importedApiKeys = export.providers.values.count { it.apiKey.isNotBlank() }
        Toast.makeText(context, "Imported ${agents.size} agents, $importedApiKeys API keys", Toast.LENGTH_SHORT).show()
        settings
    } catch (e: JsonSyntaxException) {
        Toast.makeText(context, "Invalid AI configuration format", Toast.LENGTH_SHORT).show()
        null
    } catch (e: Exception) {
        Toast.makeText(context, "Error importing configuration: ${e.message}", Toast.LENGTH_SHORT).show()
        null
    }
}

/**
 * Dialog for importing AI configuration from clipboard.
 * @deprecated Use file picker with importAiConfigFromFile instead.
 */
@Composable
fun ImportAiConfigDialog(
    currentSettings: AiSettings,
    onImport: (AiSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Import AI Configuration",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "This will import AI configuration from the clipboard.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "The clipboard should contain a JSON configuration exported from this app (version 3, 4, or 5).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                Text(
                    text = "Warning: This will replace your agents, API keys, and provider settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9800)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val imported = importAiConfigFromClipboard(context, currentSettings)
                    if (imported != null) {
                        onImport(imported)
                    }
                }
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
