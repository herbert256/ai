package com.ai.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.ui.shared.*

/** Help topics that were originally inline sections on the Help
 *  home and have been promoted to their own subpages. Two effects:
 *  (a) HelpScreen renders the matching table Composable after the
 *  topic's cards loop (so the topic acts as a thin frame around
 *  the legacy table widget); (b) titleBarHelpTopic returns "" for
 *  these ids so the ❓ icon in the subpage's title bar goes back
 *  to Help home instead of opening the help-of-help meta page. */
/** Per-screen help topics for the 11 child View screens. When the
 *  user is reading one of these help pages and taps the title-bar
 *  ❓, route them to the parent View page's help (view_ai_report,
 *  the tile-grid overview) — that's the canonical "help for the
 *  View functionality" entry point and reads more naturally as
 *  the next layer up than the generic help-of-help meta page. */
private val VIEW_CHILD_HELP_TOPICS = setOf(
    "costs_view",
    "meta_view",
    "rerank_view",
    "moderation_view",
    "fan_in_view",
    "fan_in_model_view",
    "translate_view",
    "prompt_view_screen",
    "reports_view",
    "fan_out_view",
    "fan_out_pair_view",
    "icons_view",
    "report_html_preview"
)

private val HELP_HOME_SUBPAGES = setOf(
    // Existing direct subpages of Help home.
    "help_home_icons",
    "help_home_info_providers",
    "help_home_ai_providers",
    "concepts",
    // New direct subpages — added by the help-home-subpages pass.
    "help_about",
    "help_getting_started",
    "help_glossary",
    "help_costs",
    "help_privacy",
    "help_backup",
    "help_translations",
    // Sub-subpages of help_glossary. Reached from the glossary
    // subpage's own tap-throughs (rendered by the table-style
    // dispatch in HelpScreen). Same ❓-returns-to-Help-home
    // semantics — the user can step back to the glossary page
    // via the Android system back gesture.
    "help_glossary_blocks",
    "help_glossary_groupings",
    "help_glossary_operations"
)


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
    onNavigateToTopic: (String) -> Unit = {},
    /** Pop back to the bare Help home (the topic-less landing
     *  page). Wired by AppNavHost to `NavRoutes.HELP`. Surfaced on
     *  the per-topic footer so a user reading any topic page can
     *  jump back to the top-level help without using Android-back. */
    onNavigateToHelpHome: () -> Unit = {},
    /** Open the About screen — surfaced in the per-screen footer
     *  alongside the Help-home link. Defaults to a no-op so
     *  legacy callers compile. */
    onNavigateToAbout: () -> Unit = {}
) {
    BackHandler { onBack() }
    val topic = topicId?.takeIf { it.isNotBlank() }?.let { HELP_TOPICS[it] }
    // ❓ on a per-topic help page opens the meta-topic that describes
    // the help-screen UI itself (so tapping ❓ doesn't bounce back to
    // the generic Help home — which is the parent screen, not "help
    // for this screen"). The meta-topic's own page hides ❓ so it
    // doesn't loop. The bare Help home (topicId == null) leaves ❓
    // off because the home view IS the general help.
    //
    // Help-home subpages (the icons / info providers / AI providers
    // tables that used to render inline, plus the "concepts" how-it-
    // works page) get a ❓ that returns to Help home instead — the
    // user already came from there, so a second-tier "help for
    // help" page would be more confusing than useful. Empty string
    // routes through rootNavigateHelp → NavRoutes.HELP, which is
    // the topic-less landing page.
    // ❓ on a help page now always falls back to the bare Help home
    // (empty string → NavRoutes.HELP) — except for View-family pages,
    // which redirect to the View grid's help. The old "Help - Help
    // (this screen)" meta-topic (`help_topic_view`) was removed, so
    // any path that used to point at it now opens Help home instead.
    val titleBarHelpTopic = when {
        topicId.isNullOrBlank() -> null
        topicId in VIEW_CHILD_HELP_TOPICS -> "view_ai_report"
        else -> ""
    }
    // Help pages that describe a View-family screen render with the
    // same ViewScreenTitleBar the View screens themselves use — keeps
    // the visual cue consistent (you're in the View family). Per the
    // user's spec the matching View screen's name lives in the orange
    // screen-title slot; the green subject row is unused on these.
    val isViewFamilyHelp =
        topicId != null && (topicId == "view_ai_report" || topicId in VIEW_CHILD_HELP_TOPICS)
    val viewFamilyTitle = topic?.title?.removePrefix("Help - ")?.takeIf { it.isNotBlank() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        if (isViewFamilyHelp) {
            ViewScreenTitleBar(
                reportTitle = "Help",
                screenTitle = viewFamilyTitle,
                subject = null,
                helpTopic = titleBarHelpTopic ?: "view_ai_report",
                onBack = onBack
            )
        } else {
            TitleBar(helpTopic = titleBarHelpTopic, title = topic?.title ?: "Help", onBackClick = onBack)
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (topic != null) {
                topic.cards.forEach { HelpSection(it.title, it.body) }
                // The three table subpages of Help home (icons /
                // info providers / AI providers) attach their
                // legacy table widget here, below the topic's
                // Overview card. Kept out of HelpContent.kt because
                // those tables are Composables, not strings.
                //
                // help_glossary is the same shape but its dispatch
                // renders a stack of HomeSubpageLink tap-throughs to
                // the four sub-subpages (building blocks /
                // groupings / operations / retrieval) — also Composable,
                // not data.
                when (topicId) {
                    "help_home_icons" -> HelpIconTable()
                    "help_home_info_providers" -> InfoProviderTable(onNavigateToTopic)
                    "help_home_ai_providers" -> CloudProviderTable(onNavigateToTopic)
                    "help_glossary" -> {
                        HomeSubpageLink(
                            "🧱", "Building blocks",
                            "Provider · Model · Agent — the atomic units the rest of the app composes.",
                            onClick = { onNavigateToTopic("help_glossary_blocks") }
                        )
                        HomeSubpageLink(
                            "🪺", "Groupings",
                            "Flock · Swarm — how the app bundles agents for a single launch.",
                            onClick = { onNavigateToTopic("help_glossary_groupings") }
                        )
                        HomeSubpageLink(
                            "⚙️", "Operations",
                            "Report · Chat · Meta prompt · Fan-out · Rerank · Moderation · Translation — the things you actually run.",
                            onClick = { onNavigateToTopic("help_glossary_operations") }
                        )
                    }
                }
                // "Relevant Help pages" footer — populated from the
                // RELATED_HOME_HELP map. Most per-screen topics carry
                // 2–4 cross-links to the home-help reference pages
                // (Concepts, Costs, Privacy, Translations, etc.). Topics
                // with no entry get nothing — the footer renders only
                // when the list is non-empty.
                val related = RELATED_HOME_HELP[topicId].orEmpty()
                    .mapNotNull { id -> HELP_TOPICS[id]?.let { id to it.title } }
                if (related.isNotEmpty()) {
                    RelevantHelpPagesCard(related, onNavigateToTopic)
                }
            } else {
                CompactOverview(onNavigateToTopic)
            }
            // Footer pinned to every help page. On per-topic pages
            // the Help-home row is included; on Help home itself
            // (rendered by CompactOverview) the same footer is
            // appended without the Help-home row.
            HelpFooter(
                onNavigateToHelpHome = if (topic != null) onNavigateToHelpHome else null,
                onNavigateToAbout = onNavigateToAbout
            )
        }
    }
}

@Composable
private fun HelpFooter(
    onNavigateToHelpHome: (() -> Unit)?,
    onNavigateToAbout: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("More information", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Orange)
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onNavigateToHelpHome != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onNavigateToHelpHome() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(com.ai.R.drawable.brand_glyph),
                            contentDescription = "Help home",
                            modifier = Modifier.size(40.dp).padding(end = 6.dp)
                        )
                        Text("Help home", fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onNavigateToAbout() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ℹ️", fontSize = 24.sp, modifier = Modifier.width(40.dp))
                    Text("About", fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/herbert256/ai")
                            )
                        )
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🐙", fontSize = 24.sp, modifier = Modifier.width(40.dp))
                    Text(
                        "GitHub: herbert256/ai",
                        fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    )
                }
            }
        }
    }
}

/** Tap-through card used on Help home to jump to a topic-style
 *  subpage. Same chrome as a HelpSection (Card + 14 dp padding),
 *  but the body is a single description line under a clickable
 *  title and the whole Row carries the click handler. */
@Composable
private fun HomeSubpageLink(icon: String, title: String, blurb: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 14.sp, modifier = Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Text(blurb, fontSize = 12.sp, color = AppColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun CompactOverview(
    onNavigateToTopic: (String) -> Unit = {}
) {
    // Help-home search box — case-insensitive substring search
    // across every topic title + every card title + every card
    // body. Non-blank query suppresses the rest of the home
    // content so the result list stays in the top viewport. The
    // per-topic pages don't carry their own search; users go back
    // to Help home to search.
    var query by remember { mutableStateOf("") }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = {
            Text(
                "Search help (try \"translation\", \"cost\", \"agent\"…)",
                fontSize = 13.sp, color = Color(0xFF888888)
            )
        },
        leadingIcon = { Text("🔍", fontSize = 14.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    if (query.isNotBlank()) {
        SearchResults(query, onNavigateToTopic)
        return
    }
    HelpSection(
        "Welcome",
        "This app runs AI reports, chats, and dual chats against ${AppService.entries.size} cloud providers. Configure providers with API keys, then build reports and chats from the Hub."
    )
    HelpSection(
        "Per-screen help",
        "Every screen has its own help page. Tap ❓ in the icon bar of the screen you're on for guidance specific to that screen. This page is the general overview only."
    )
    // Tap-through subpage links — each opens its own help topic
    // prefixed "Help - …". Order is curated: About + Getting
    // started first (orientation), then the cross-cutting
    // behaviour topics, then the references (Costs / Privacy /
    // Backup / Translations), then the table-style reference
    // subpages (Icons / Info providers / AI providers).
    // Each subpage's ❓ icon routes back to Help home.
    HomeSubpageLink(
        "🧭", "About the app",
        "What this app does, who it's for, headline features, where to start. The orientation page.",
        onClick = { onNavigateToTopic("help_about") }
    )
    HomeSubpageLink(
        "🚀", "Getting started",
        "Step-by-step: add an API key → refresh model lists → first Agent → first Report. Plus common first-week pitfalls.",
        onClick = { onNavigateToTopic("help_getting_started") }
    )
    HomeSubpageLink(
        "🔧", "How it works",
        "Cross-screen behaviours — background sweeps, auto-reconcile, 429 / 529 retry policy, cost-aware hesitation. Anything the app does that isn't tied to one screen.",
        onClick = { onNavigateToTopic("concepts") }
    )
    HomeSubpageLink(
        "📖", "Concepts & glossary",
        "Provider · Agent · Report · Meta · Fan-out · … — the app's vocabulary, grouped into three categories with a one-paragraph explainer each.",
        onClick = { onNavigateToTopic("help_glossary") }
    )
    HomeSubpageLink(
        "💰", "Costs & pricing",
        "How the app attributes a USD cost to every call — pricing-tier chain, manual overrides, where costs surface in the UI.",
        onClick = { onNavigateToTopic("help_costs") }
    )
    HomeSubpageLink(
        "🔒", "Privacy & data",
        "Local-first principle, what leaves the device, what never does. Telemetry: none. Data ownership: yours.",
        onClick = { onNavigateToTopic("help_privacy") }
    )
    HomeSubpageLink(
        "💾", "Backup & restore",
        "What a backup zip contains, how to make one, restore semantics, version compatibility.",
        onClick = { onNavigateToTopic("help_backup") }
    )
    HomeSubpageLink(
        "🌐", "Translations & multi-language",
        "How translation runs work — what gets translated, single- vs multi-model, the Speed / Mixed / Cost mode toggle, Restart-failed semantics, the self-healing background paths.",
        onClick = { onNavigateToTopic("help_translations") }
    )
    HomeSubpageLink(
        "🎨", "Icons",
        "Legend for every title-bar / action-row / list icon in the app — what each glyph means and where it shows up.",
        onClick = { onNavigateToTopic("help_home_icons") }
    )
    HomeSubpageLink(
        "🛰", "Info providers",
        "External services the app fetches metadata from — model lists, pricing tiers, capability flags. Drill in to see each one's freshness rules + fallback chain.",
        onClick = { onNavigateToTopic("help_home_info_providers") }
    )
    HomeSubpageLink(
        "☁️", "AI providers (cloud)",
        "Every cloud LLM / embedder / reranker the app can talk to. Drill in for endpoint, auth, model-list freshness.",
        onClick = { onNavigateToTopic("help_home_ai_providers") }
    )
    HelpSection(
        "Copyright",
        "Copyright © Herbert Groot Jebbink. Licensed under the GNU General Public License v2.0 — see the LICENSE file at the root of the source repository."
    )
    // The About + GitHub footer is appended by HelpScreen via
    // HelpFooter (no Help-home row on this page since we ARE the
    // home).
}

/** Single result row in the Help-home search panel. [matchedCardTitle]
 *  is null when the topic's title alone matched (no specific card
 *  was the better hit). [snippet] is ~120 chars of body text around
 *  the needle, used as a preview line under the link. */
private data class HelpSearchHit(
    val topicId: String,
    val topicTitle: String,
    val matchedCardTitle: String?,
    val snippet: String,
    val score: Int
)

/** Walk every topic + every card and score (title weight 3, card-
 *  title match weight 2, card-body match weight 1). Multiple cards
 *  from the same topic collapse to the best-scoring one so the
 *  results list stays unique-by-topic. Top 12 sorted desc. */
private fun searchHelp(q: String): List<HelpSearchHit> {
    val needle = q.trim().lowercase()
    if (needle.isBlank()) return emptyList()
    return HELP_TOPICS.entries.mapNotNull { (id, content) ->
        val topicTitleMatch = content.title.lowercase().contains(needle)
        val cardHits = content.cards.mapNotNull { card ->
            val titleMatch = card.title.lowercase().contains(needle)
            val bodyMatch = card.body.lowercase().contains(needle)
            if (!titleMatch && !bodyMatch) null
            else HelpSearchHit(
                topicId = id,
                topicTitle = content.title,
                matchedCardTitle = card.title,
                snippet = snippetAround(card.body, needle),
                score = (if (topicTitleMatch) 3 else 0) +
                        (if (titleMatch) 2 else 0) +
                        (if (bodyMatch) 1 else 0)
            )
        }
        when {
            cardHits.isNotEmpty() -> cardHits.maxBy { it.score }
            topicTitleMatch -> HelpSearchHit(
                topicId = id,
                topicTitle = content.title,
                matchedCardTitle = null,
                snippet = content.cards.firstOrNull()?.body?.take(120).orEmpty(),
                score = 3
            )
            else -> null
        }
    }
        .sortedByDescending { it.score }
        .take(12)
}

/** Carve a ~120-char window around the first occurrence of [needle]
 *  in [body], padded with ellipses when the window starts / ends
 *  mid-sentence. Falls back to the head of [body] when the needle
 *  isn't actually in the body (the topic title was the match). */
private fun snippetAround(body: String, needle: String): String {
    val window = 120
    val haystack = body.lowercase()
    val at = haystack.indexOf(needle)
    if (at < 0) return body.take(window) + (if (body.length > window) "…" else "")
    val halfPad = (window - needle.length).coerceAtLeast(40) / 2
    val from = (at - halfPad).coerceAtLeast(0)
    val to = (at + needle.length + halfPad).coerceAtMost(body.length)
    val head = if (from > 0) "…" else ""
    val tail = if (to < body.length) "…" else ""
    return head + body.substring(from, to) + tail
}

/** Renders the search hits — one card per topic with the topic
 *  title, the matched card's title (if any), and a preview snippet
 *  with the needle approximately centred. Empty result list renders
 *  a single "no matches" card so the user knows the search ran. */
@Composable
private fun SearchResults(query: String, onNavigateToTopic: (String) -> Unit) {
    val hits = searchHelp(query)
    if (hits.isEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("No matches", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Orange)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Nothing in any help topic matched \"$query\". Try a shorter or differently-spelled term.",
                    fontSize = 12.sp, color = Color(0xFFCCCCCC), lineHeight = 16.sp
                )
            }
        }
        return
    }
    Text(
        "${hits.size} match${if (hits.size == 1) "" else "es"} for \"$query\"",
        fontSize = 12.sp, color = Color(0xFFAAAAAA),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
    )
    hits.forEach { hit ->
        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onNavigateToTopic(hit.topicId) }
                    .padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text("🔍", fontSize = 14.sp, modifier = Modifier.width(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(hit.topicTitle, fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                    if (hit.matchedCardTitle != null) {
                        Text(hit.matchedCardTitle, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    if (hit.snippet.isNotBlank()) {
                        Text(hit.snippet, fontSize = 12.sp, color = Color(0xFFCCCCCC), lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

/** "Relevant Help pages" footer — rendered at the bottom of a per-
 *  topic page when [RELATED_HOME_HELP] has an entry for that topic.
 *  Same chrome as [HomeSubpageLink] but more compact (smaller right
 *  arrow, no body line) so it doesn't compete with the topic's own
 *  cards. Each row navigates via [onNavigateToTopic]. */
@Composable
private fun RelevantHelpPagesCard(
    related: List<Pair<String, String>>,
    onNavigateToTopic: (String) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Relevant Help pages", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Orange)
            Spacer(modifier = Modifier.height(6.dp))
            related.forEach { (id, title) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onNavigateToTopic(id) }
                        .padding(vertical = 5.dp)
                ) {
                    Text("→", fontSize = 13.sp, color = AppColors.Blue, modifier = Modifier.width(24.dp))
                    Text(title, fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HelpSection(title: String, content: String) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Orange)
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
            Text("Title bar icons", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Orange)
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
            Text("Info providers", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Orange)
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
    "provider_cohere" to "Command-R/A — enterprise chat + native rerank endpoint",
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
            Text("Cloud providers", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Orange)
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

