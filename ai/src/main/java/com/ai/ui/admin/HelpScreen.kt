package com.ai.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.ui.shared.*


@Composable
fun HelpScreen(
    topicId: String? = null,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** Drill from one help topic into another. Wired by AppNavHost
     *  to `navController.navigate(NavRoutes.helpForTopic(id))`. Used
     *  by the home page's Info-providers table; per-topic cards
     *  don't currently navigate but the hook is here for future
     *  cross-links. */
    onNavigateToTopic: (String) -> Unit = {}
) {
    BackHandler { onBack() }
    val topic = topicId?.takeIf { it.isNotBlank() }?.let { HELP_TOPICS[it] }
    // ❓ on a per-topic help page opens the meta-topic that describes
    // the help-screen UI itself (so tapping ❓ doesn't bounce back to
    // the generic Help home — which is the parent screen, not "help
    // for this screen"). The meta-topic's own page hides ❓ so it
    // doesn't loop. The bare Help home (topicId == null) leaves ❓
    // off because the home view IS the general help.
    val titleBarHelpTopic = when {
        topicId.isNullOrBlank() -> null
        topicId == "help_topic_view" -> null
        else -> "help_topic_view"
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = titleBarHelpTopic, title = topic?.title ?: "Help", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (topic != null) {
                topic.cards.forEach { HelpSection(it.title, it.body) }
            } else {
                CompactOverview(onNavigateToTopic)
            }
        }
    }
}

@Composable
private fun CompactOverview(onNavigateToTopic: (String) -> Unit = {}) {
    HelpSection(
        "Welcome",
        "This app runs AI reports, chats, and dual chats against ${AppService.entries.size} cloud providers plus on-device models. Configure providers with API keys, then build reports, chats, or knowledge bases from the Hub."
    )
    HelpSection(
        "Per-screen help",
        "Every screen has its own help page. Tap ❓ in the icon bar of the screen you're on for guidance specific to that screen. This page is the general overview only."
    )
    HelpSection(
        "Getting started",
        "1. Settings → AI Setup → Providers — paste an API key.\n" +
            "2. Housekeeping → Refresh → Refresh all — verify keys + fetch model lists + seed default agents.\n" +
            "3. From the Hub, pick Reports / Chat / Knowledge / Models / Setup / Housekeeping."
    )
    HelpIconTable()
    InfoProviderTable(onNavigateToTopic)
    CloudProviderTable(onNavigateToTopic)
    HelpSection(
        "Privacy",
        "All data stays on this device. API keys are sent only to their provider. No telemetry."
    )
    HelpSection(
        "Copyright",
        "Copyright © Herbert Jebbink. Licensed under the GNU General Public License v2.0 — see the LICENSE file at the root of the source repository."
    )
}

@Composable
private fun HelpSection(title: String, content: String) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
            Spacer(modifier = Modifier.height(6.dp))
            Text(content, fontSize = 13.sp, color = Color(0xFFCCCCCC), lineHeight = 18.sp)
        }
    }
}

@Composable
private fun HelpIconTable() {
    val rows = listOf(
        Triple("◀", "Back", "Previous screen."),
        Triple("🏠", "Home", "Returns here from anywhere."),
        Triple("❓", "Help", "Opens topic-specific help for the current screen."),
        Triple("ℹ️", "Info", "Drills into model info or another details target."),
        Triple("📋", "Copy", "Copies the screen's main payload to the system clipboard (report text, trace JSON, chat transcript, …)."),
        Triple("📤", "Share", "Fires the Android share sheet (ACTION_SEND) with the screen's main payload as plain text."),
        Triple("🗑", "Trash", "Destructive scope-specific delete (clear stats, drop trace list, delete report). Only shown when the destructive scope is non-empty."),
        Triple("🐞", "Trace", "Opens API Traces filtered to the current scope (report / model / session). Only shown when tracing is on AND traces exist."),
        Triple("🔄", "Reload", "Re-runs the screen's fetch."),
        Triple("💬", "Chat", "Opens a chat against the current context.")
    )
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Title bar icons", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
            Spacer(modifier = Modifier.height(8.dp))
            rows.forEach { (icon, name, desc) ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(icon, fontSize = 16.sp, modifier = Modifier.width(28.dp))
                    Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.width(72.dp))
                    Text(desc, fontSize = 13.sp, color = Color(0xFFCCCCCC), lineHeight = 18.sp, modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Icons that aren't relevant to a screen are simply absent — there's nothing to disable.",
                fontSize = 12.sp, color = Color(0xFFAAAAAA), lineHeight = 16.sp
            )
        }
    }
}

/** Directory card listing the seven info providers — same set as the
 *  Sources card on Model Info. Each row drills into the matching
 *  per-provider help topic via [onNavigateToTopic]. */
@Composable
private fun InfoProviderTable(onNavigateToTopic: (String) -> Unit) {
    val taglines = mapOf(
        "info_provider_huggingface" to "Model cards · context · license",
        "info_provider_openrouter" to "Aggregator catalog + per-model specs",
        "info_provider_litellm" to "BerriAI's model_prices JSON",
        "info_provider_models_dev" to "Community catalog (LiteLLM fallback)",
        "info_provider_helicone" to "Pricing-only side product",
        "info_provider_llm_prices" to "Simon Willison's curated 10-vendor table",
        "info_provider_artificial_analysis" to "Independent benchmarker (key required)"
    )
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Info providers", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Third-party services the app reads model metadata + pricing from. Same seven that appear on Model Info → Sources. Tap a row for the details.",
                fontSize = 12.sp, color = Color(0xFFAAAAAA), lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            INFO_PROVIDERS.forEach { ref ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onNavigateToTopic(ref.topicId) }
                        .padding(vertical = 6.dp)
                ) {
                    Text(ref.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = Color.White, modifier = Modifier.width(140.dp))
                    Text(taglines[ref.topicId].orEmpty(), fontSize = 12.sp,
                        color = Color(0xFFCCCCCC), lineHeight = 16.sp,
                        modifier = Modifier.weight(1f))
                    Text(">", color = AppColors.Blue, fontSize = 14.sp)
                }
            }
        }
    }
}

/** One row in the seven-strong directory of third-party "info
 *  providers" the app fetches model + pricing data from. The same
 *  set is surfaced as the Sources card on Model Info; this table is
 *  the single source of truth that the home Help directory, the
 *  Source detail page, the External Services / Refresh ℹ️ icons, and
 *  the Trace detail's ℹ️ override all consult.
 *
 *  [hostnames] match against `URI.host` of the called URL.
 *  [urlPathPrefix] disambiguates two providers that share a hostname
 *  (LiteLLM and llm-prices both live on raw.githubusercontent.com).
 *  [requiresChatCategoryGate] is true for OpenRouter — it doubles as
 *  an AppService, so a chat-completion trace shouldn't hijack the
 *  ℹ️; the resolver only matches when the trace category is one of
 *  the info-fetch categories. */
data class InfoProviderRef(
    val topicId: String,
    val displayName: String,
    val hostnames: List<String>,
    val urlPathPrefix: String? = null,
    val requiresChatCategoryGate: Boolean = false
)

/** Lookup by topic id — handy for callsites that already know which
 *  provider they want (Source detail buttons, External Services
 *  cards). */
val INFO_PROVIDERS_BY_TOPIC: Map<String, InfoProviderRef> by lazy {
    INFO_PROVIDERS.associateBy { it.topicId }
}

/** Resolve a display name (e.g. "LiteLLM", "llm-prices.com",
 *  "models.dev") to its [InfoProviderRef]. Case-insensitive. Used
 *  by callsites that surface the user-visible name and want to
 *  optionally link it to the matching help topic — Cost breakdown
 *  rows, Capabilities source labels, ModelInfoSection footers, etc. */
fun infoProviderForDisplayName(name: String?): InfoProviderRef? {
    if (name.isNullOrBlank()) return null
    return INFO_PROVIDERS.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
}

/** Topic id for a cloud-provider help page. Lowercase + strip
 *  non-alphanumerics so an [AppService.id] like "Novita.ai" or
 *  "01.AI" maps to "provider_novitaai" / "provider_01ai" without a
 *  regex collision. Returns the id even when no [HELP_TOPICS] entry
 *  exists; the lookup gracefully falls through to the home page on
 *  a missing key (user-added providers, etc.). */
fun providerHelpTopicId(serviceId: String): String =
    "provider_" + serviceId.lowercase().filter { it.isLetterOrDigit() }

/** One-line taglines for the [CloudProviderTable] directory on the
 *  home Help page. Keyed by topic id (the same string the row click
 *  navigates to). Built-in providers only; user-added providers fall
 *  through to an empty subtitle. */
private val CLOUD_PROVIDER_TAGLINES: Map<String, String> = mapOf(
    "provider_openai" to "ChatGPT, GPT-5 / o-series — Chat Completions + Responses API",
    "provider_anthropic" to "Claude — `/v1/messages` format, web search tool",
    "provider_google" to "Gemini — `:generateContent` path, `?key=` auth",
    "provider_xai" to "Grok — Elon Musk's xAI; cost in ticks (÷10¹⁰)",
    "provider_groq" to "LPU inference — fast Llama / Mixtral / Whisper",
    "provider_deepseek" to "DeepSeek-V3 / R1 — reasoning + coding from China",
    "provider_mistral" to "Mistral / Codestral / Pixtral — `random_seed` field",
    "provider_perplexity" to "Sonar — search-grounded answers + citations",
    "provider_together" to "Together AI — open-weight catalog; bare-array `/models`",
    "provider_openrouter" to "Aggregator — proxies dozens of upstream providers",
    "provider_siliconflow" to "SiliconCloud — Qwen / DeepSeek mirror (China)",
    "provider_zai" to "Zhipu AI — GLM family (China)",
    "provider_moonshot" to "Moonshot AI — Kimi long-context (China)",
    "provider_cohere" to "Command-R/A — RAG-tuned + native rerank endpoint",
    "provider_ai21" to "Jamba — hybrid SSM/Transformer family",
    "provider_dashscope" to "Alibaba Qwen — international mirror of DashScope",
    "provider_fireworks" to "Open-weight inference — DeepSeek / Llama / Qwen",
    "provider_cerebras" to "Wafer-scale inference — Llama / Qwen at very high tok/s",
    "provider_sambanova" to "RDU inference — Llama / DeepSeek / Qwen",
    "provider_baichuan" to "Baichuan-AI — China-region general-purpose models",
    "provider_stepfun" to "StepFun — Step-2 / Step-3 long-context (China)",
    "provider_minimax" to "MiniMax — abab / MiniMax-M family",
    "provider_nvidia" to "NVIDIA NIM — Nemotron + 3rd-party catalog",
    "provider_replicate" to "Replicate — public model marketplace",
    "provider_huggingface" to "HF Inference API — open-weight model serving",
    "provider_lambda" to "Lambda Labs — Hermes / Llama on H100",
    "provider_lepton" to "Lepton AI — Llama / Mistral / Gemma serverless",
    "provider_01ai" to "01.AI — Yi family (Kai-Fu Lee, China)",
    "provider_doubao" to "ByteDance Doubao — Volcano Engine, China",
    "provider_reka" to "Reka — multimodal Reka-Core / Flash / Edge",
    "provider_writer" to "Writer — Palmyra enterprise-tuned models",
    "provider_cloudflareworkersai" to "Workers AI — replace `YOUR_ACCOUNT_ID` in URL",
    "provider_deepinfra" to "DeepInfra — open-weight serverless inference",
    "provider_hyperbolic" to "Hyperbolic — open-weight + image/audio inference",
    "provider_novitaai" to "Novita.ai — open-weight serverless inference",
    "provider_featherlessai" to "Featherless.ai — HF model serverless host",
    "provider_liquidai" to "Liquid AI — LFM-7B/40B foundation models",
    "provider_llamaapi" to "Meta Llama API — official Llama 3.x / 4.x access",
    "provider_krutrim" to "Ola Krutrim — India-region open-weight inference",
    "provider_nebiusaistudio" to "Nebius AI Studio — Llama / DeepSeek / Qwen",
    "provider_chutes" to "Chutes — Bittensor-backed open-weight serving",
    "provider_inferencenet" to "Inference.net — open-weight serverless inference"
)

/** Directory card listing every registered cloud provider. Mirrors
 *  [InfoProviderTable]: tagline subtitle + clickable row drilling
 *  into the per-provider help page. Hidden when the registry is
 *  empty (cold-startup edge case). User-added providers render with
 *  an empty subtitle and route to the home page if no help entry
 *  exists for their derived topic id. */
@Composable
private fun CloudProviderTable(onNavigateToTopic: (String) -> Unit) {
    val services = AppService.entries
    if (services.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Cloud providers", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "AI services the app dispatches chat / report / embedding calls to. Tap a row for setup, models, pricing, and pitfalls specific to that provider.",
                fontSize = 12.sp, color = Color(0xFFAAAAAA), lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            services.forEach { svc ->
                val topicId = providerHelpTopicId(svc.id)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onNavigateToTopic(topicId) }
                        .padding(vertical = 6.dp)
                ) {
                    Text(svc.id, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = Color.White, modifier = Modifier.width(160.dp))
                    Text(CLOUD_PROVIDER_TAGLINES[topicId].orEmpty(), fontSize = 12.sp,
                        color = Color(0xFFCCCCCC), lineHeight = 16.sp,
                        modifier = Modifier.weight(1f))
                    Text(">", color = AppColors.Blue, fontSize = 14.sp)
                }
            }
        }
    }
}

internal val INFO_PROVIDERS: List<InfoProviderRef> = listOf(
    InfoProviderRef(
        topicId = "info_provider_huggingface",
        displayName = "HuggingFace",
        hostnames = listOf("huggingface.co")
    ),
    InfoProviderRef(
        topicId = "info_provider_openrouter",
        displayName = "OpenRouter",
        hostnames = listOf("openrouter.ai"),
        requiresChatCategoryGate = true
    ),
    InfoProviderRef(
        topicId = "info_provider_litellm",
        displayName = "LiteLLM",
        hostnames = listOf("raw.githubusercontent.com"),
        urlPathPrefix = "/BerriAI/litellm/"
    ),
    InfoProviderRef(
        topicId = "info_provider_models_dev",
        displayName = "models.dev",
        hostnames = listOf("models.dev")
    ),
    InfoProviderRef(
        topicId = "info_provider_helicone",
        displayName = "Helicone",
        hostnames = listOf("www.helicone.ai", "helicone.ai")
    ),
    InfoProviderRef(
        topicId = "info_provider_llm_prices",
        displayName = "llm-prices.com",
        hostnames = listOf("raw.githubusercontent.com"),
        urlPathPrefix = "/simonw/llm-prices/"
    ),
    InfoProviderRef(
        topicId = "info_provider_artificial_analysis",
        displayName = "Artificial Analysis",
        hostnames = listOf("artificialanalysis.ai")
    )
)

/** Categories used by [com.ai.data.PricingCache] when calling the
 *  catalog sources. Anything else (Chat, Translation, etc.) is an AI
 *  call, not an info-provider call, even if the hostname matches a
 *  dual-purpose service like OpenRouter. */
private val INFO_FETCH_CATEGORIES = setOf("Pricing fetch", "OpenRouter model specs")

/** Resolve a URL to one of the 7 info providers. Matches by host
 *  first, then disambiguates via [InfoProviderRef.urlPathPrefix] for
 *  hosts shared by multiple providers (raw.githubusercontent.com).
 *  Returns null when the URL doesn't belong to any of the 7. */
fun infoProviderForUrl(url: String?): InfoProviderRef? {
    if (url.isNullOrBlank()) return null
    val (host, path) = try {
        val uri = java.net.URI(url)
        (uri.host ?: "") to (uri.rawPath ?: "")
    } catch (_: Exception) { "" to "" }
    if (host.isBlank()) return null
    return INFO_PROVIDERS.firstOrNull { ref ->
        ref.hostnames.any { it.equals(host, ignoreCase = true) } &&
            (ref.urlPathPrefix == null || path.startsWith(ref.urlPathPrefix))
    }
}

/** Resolve a captured trace's URL + category to one of the 7
 *  providers. For dual-purpose services (OpenRouter), the category
 *  must be one of [INFO_FETCH_CATEGORIES]; otherwise a chat
 *  completion would hijack the ℹ️. */
fun infoProviderForTrace(url: String?, category: String?): InfoProviderRef? {
    val ref = infoProviderForUrl(url) ?: return null
    if (ref.requiresChatCategoryGate && category !in INFO_FETCH_CATEGORIES) return null
    return ref
}

