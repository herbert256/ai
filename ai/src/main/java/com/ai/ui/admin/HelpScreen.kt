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
    val mi = LocalMetadataIcons.current
    val topic = topicId?.takeIf { it.isNotBlank() }?.let { HELP_TOPICS[it] }
    // Standard top bar for every help screen: ❓ glyph left, white
    // "Help" always, orange = this page's subject (the topic title with
    // the leading "Help …" stripped; null on the bare index → no orange
    // line). ❓ icon + "Help" → the main help page; the orange subject →
    // back to the screen that opened this help page.
    val subject = topic?.title
        ?.removePrefix("Help")?.trimStart(' ', '-', '—', '–', ':')
        ?.takeIf { it.isNotBlank() }
        ?: "How this app works, screen by screen"
    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            title = "Help",
            subject = subject,
            reportIcon = mi.help,
            onReportIconClick = onNavigateToHelpHome,
            onTitleClick = onNavigateToHelpHome,
            subjectOnClick = onBack,
            onBackClick = onBack
        )
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (topic != null) {
                // Top cross-link to this screen's per-icon help page, when
                // one exists (topic id "<screen>_icons"). Generic — every
                // screen that gains an icon page gets this link for free.
                // Suppressed on screens that have the live "<screen> - icons"
                // overlay (white ❔) — there it's redundant.
                if (!topicId.endsWith("_icons") &&
                    topicId !in com.ai.ui.shared.LEGEND_OVERLAY_TOPICS &&
                    HELP_TOPICS.containsKey("${topicId}_icons")) {
                    HomeSubpageLink(
                        mi.helpLegend, "Icons on this screen",
                        "What each bottom-bar icon does here.",
                        onClick = { onNavigateToTopic("${topicId}_icons") }
                    )
                }
                topic.cards.forEach { HelpSection(mi.iconizedText(it.title), mi.iconizedText(it.body)) }
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
                // Per-screen icon legend: standalone table on a
                // "<topic>_icons" page, or inline (with a header) under the
                // main help of a screen that has only 1–3 icons.
                if (topicId.endsWith("_icons")) {
                    SCREEN_ICON_HELP[topicId.removeSuffix("_icons")]?.let { IconHelpTable(it) }
                } else if (topicId in SCREEN_ICON_HELP && topicId !in ICON_HELP_AS_PAGE &&
                    topicId !in com.ai.ui.shared.LEGEND_OVERLAY_TOPICS) {
                    // Screens with the live icon overlay don't repeat the
                    // icon table inline on their help page.
                    IconHelpTable(SCREEN_ICON_HELP.getValue(topicId), title = "Icons on this screen")
                }
                when (topicId) {
                    "help_home_icons" -> HelpIconTable()
                    "help_home_info_providers" -> InfoProviderTable(onNavigateToTopic)
                    "help_home_ai_providers" -> CloudProviderTable(onNavigateToTopic)
                    "help_glossary" -> {
                        HomeSubpageLink(
                            mi.buildingBlocks, "Building blocks",
                            "Provider · Model · Agent — the atomic units the rest of the app composes.",
                            onClick = { onNavigateToTopic("help_glossary_blocks") }
                        )
                        HomeSubpageLink(
                            mi.groupings, "Groupings",
                            "Flock · Swarm — how the app bundles agents for a single launch.",
                            onClick = { onNavigateToTopic("help_glossary_groupings") }
                        )
                        HomeSubpageLink(
                            mi.settings, "Operations",
                            "Report · Chat · Meta prompt · Fan-out · Rerank · Moderation · Translation — the things you actually run.",
                            onClick = { onNavigateToTopic("help_glossary_operations") }
                        )
                    }
                }
                // Bottom link from a per-icon page ("<screen>_icons") back
                // to the screen's full help. Generic for any icon page.
                if (topicId.endsWith("_icons")) {
                    val base = topicId.removeSuffix("_icons")
                    if (HELP_TOPICS.containsKey(base)) {
                        HomeSubpageLink(
                            mi.book, "Full help for this screen",
                            "Everything else about this screen.",
                            onClick = { onNavigateToTopic(base) }
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
            // Breathing room below the "More information" card so it
            // doesn't sit flush against the bottom bar.
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun HelpFooter(
    onNavigateToHelpHome: (() -> Unit)?,
    onNavigateToAbout: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mi = LocalMetadataIcons.current
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("More information", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.WarningAccent)
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
                        Text("Help home", fontSize = 13.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onNavigateToAbout() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(mi.info, fontSize = 24.sp, modifier = Modifier.width(40.dp))
                    Text("About", fontSize = 13.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.SemiBold)
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
                    Text(mi.github, fontSize = 24.sp, modifier = Modifier.width(40.dp))
                    Text(
                        "GitHub: herbert256/ai",
                        fontSize = 13.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.SemiBold,
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
    val mi = LocalMetadataIcons.current
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 14.sp, modifier = Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(mi.iconizedText(title), fontSize = 13.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.SemiBold)
                Text(mi.iconizedText(blurb), fontSize = 12.sp, color = AppColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun CompactOverview(
    onNavigateToTopic: (String) -> Unit = {}
) {
    val mi = LocalMetadataIcons.current
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
                fontSize = 13.sp, color = AppColors.TextDim
            )
        },
        leadingIcon = { Text(mi.search, fontSize = 14.sp) },
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
        "Every screen has its own help page. Tap ${mi.help} in the icon bar of the screen you're on for guidance specific to that screen. This page is the general overview only."
    )
    // Tap-through subpage links — each opens its own help topic
    // prefixed "Help - …". Order is curated: About + Getting
    // started first (orientation), then the cross-cutting
    // behaviour topics, then the references (Costs / Privacy /
    // Backup / Translations), then the table-style reference
    // subpages (Icons / Info providers / AI providers).
    // Each subpage's ❓ icon routes back to Help home.
    HomeSubpageLink(
        mi.webSearchReplay, "About the app",
        "What this app does, who it's for, headline features, where to start. The orientation page.",
        onClick = { onNavigateToTopic("help_about") }
    )
    HomeSubpageLink(
        mi.rocket, "Getting started",
        "Step-by-step: add an API key → refresh model lists → first Agent → first Report. Plus common first-week pitfalls.",
        onClick = { onNavigateToTopic("help_getting_started") }
    )
    HomeSubpageLink(
        mi.openManage, "How it works",
        "Cross-screen behaviours — background sweeps, auto-reconcile, 429 / 529 retry policy, cost-aware hesitation. Anything the app does that isn't tied to one screen.",
        onClick = { onNavigateToTopic("concepts") }
    )
    HomeSubpageLink(
        mi.book, "Concepts & glossary",
        "Provider · Agent · Report · Meta · Fan-out · … — the app's vocabulary, grouped into three categories with a one-paragraph explainer each.",
        onClick = { onNavigateToTopic("help_glossary") }
    )
    HomeSubpageLink(
        mi.cost, "Costs & pricing",
        "How the app attributes a USD cost to every call — pricing-tier chain, manual overrides, where costs surface in the UI.",
        onClick = { onNavigateToTopic("help_costs") }
    )
    HomeSubpageLink(
        mi.statusLocked, "Privacy & data",
        "Local-first principle, what leaves the device, what never does. Telemetry: none. Data ownership: yours.",
        onClick = { onNavigateToTopic("help_privacy") }
    )
    HomeSubpageLink(
        mi.save, "Backup & restore",
        "What a backup zip contains, how to make one, restore semantics, version compatibility.",
        onClick = { onNavigateToTopic("help_backup") }
    )
    HomeSubpageLink(
        mi.translationRow, "Translations & multi-language",
        "How translation runs work — what gets translated, single- vs multi-model, the Speed / Mixed / Cost mode toggle, Restart-failed semantics, the self-healing background paths.",
        onClick = { onNavigateToTopic("help_translations") }
    )
    HomeSubpageLink(
        mi.palette, "Icons",
        "Legend for every title-bar / action-row / list icon in the app — what each glyph means and where it shows up.",
        onClick = { onNavigateToTopic("help_home_icons") }
    )
    HomeSubpageLink(
        mi.satellite, "Info providers",
        "External services the app fetches metadata from — model lists, pricing tiers, capability flags. Drill in to see each one's freshness rules + fallback chain.",
        onClick = { onNavigateToTopic("help_home_info_providers") }
    )
    HomeSubpageLink(
        mi.cloud, "AI providers (cloud)",
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
    val mi = LocalMetadataIcons.current
    val hits = searchHelp(query)
    if (hits.isEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("No matches", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.WarningAccent)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Nothing in any help topic matched \"$query\". Try a shorter or differently-spelled term.",
                    fontSize = 12.sp, color = AppColors.TextSecondary, lineHeight = 16.sp
                )
            }
        }
        return
    }
    Text(
        "${hits.size} match${if (hits.size == 1) "" else "es"} for \"$query\"",
        fontSize = 12.sp, color = AppColors.TextSecondary,
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
                    Text(mi.search, fontSize = 14.sp, modifier = Modifier.width(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(mi.iconizedText(hit.topicTitle), fontSize = 13.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.SemiBold)
                    if (hit.matchedCardTitle != null) {
                        Text(mi.iconizedText(hit.matchedCardTitle), fontSize = 12.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    if (hit.snippet.isNotBlank()) {
                        Text(mi.iconizedText(hit.snippet), fontSize = 12.sp, color = AppColors.TextSecondary, lineHeight = 16.sp)
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
    val mi = LocalMetadataIcons.current
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Relevant Help pages", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.WarningAccent)
            Spacer(modifier = Modifier.height(6.dp))
            related.forEach { (id, title) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onNavigateToTopic(id) }
                        .padding(vertical = 5.dp)
                ) {
                    Text(mi.arrowRight, fontSize = 13.sp, color = AppColors.InfoAccent, modifier = Modifier.width(24.dp))
                    Text(mi.iconizedText(title), fontSize = 13.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HelpSection(title: String, content: String) {
    val mi = LocalMetadataIcons.current
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(mi.iconizedText(title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.WarningAccent)
            Spacer(modifier = Modifier.height(6.dp))
            Text(mi.iconizedText(content), fontSize = 13.sp, color = AppColors.TextSecondary, lineHeight = 18.sp)
        }
    }
}

/** Generic per-screen icon legend. One flat table, big glyphs, rows in
 *  the same left-to-right / top-to-bottom sequence the icons appear in
 *  the bottom bar. Rendered standalone on a "<topic>_icons" page, or
 *  inline (with [title]) under a screen's main help when it has ≤3
 *  icons. Rows live in [SCREEN_ICON_HELP] (IconHelp.kt). */
@Composable
private fun IconHelpTable(rows: List<Triple<String, String, String>>, title: String? = null) {
    val mi = LocalMetadataIcons.current
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)) {
            if (title != null) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.WarningAccent,
                    modifier = Modifier.padding(start = 10.dp, bottom = 4.dp))
            }
            rows.forEach { (icon, name, desc) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                    Text(mi.forFactoryGlyph(icon), fontSize = 26.sp, modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(mi.iconizedText(name), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.width(94.dp))
                    Text(mi.iconizedText(desc), fontSize = 13.sp, color = AppColors.TextSecondary, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HelpIconTable() {
    val mi = LocalMetadataIcons.current
    val rows = listOf(
        Triple(mi.arrowBack, "Back", "Previous screen."),
        Triple(mi.home, "Home", "Returns here from anywhere."),
        Triple(mi.helpLegend, "Icons help", "Lists every icon on the current screen (the legend). Shown when the bar carries more than a few icons."),
        Triple(mi.help, "Help", "Opens topic-specific help for the current screen."),
        Triple(mi.info, "Info", "Drills into model info or another details target."),
        Triple(mi.copy, "Copy", "Copies the screen's main payload to the system clipboard (report text, trace JSON, chat transcript, …)."),
        Triple(mi.share, "Share", "Fires the Android share sheet (ACTION_SEND) with the screen's main payload as plain text."),
        Triple(mi.delete, "Trash", "Destructive scope-specific delete (clear stats, drop trace list, delete report). Only shown when the destructive scope is non-empty."),
        Triple(mi.traces, "Trace", "Opens API Traces filtered to the current scope (report / model / session). Only shown when tracing is on AND traces exist."),
        Triple(mi.reload, "Reload", "Re-runs the screen's fetch."),
        Triple(mi.chat, "Chat", "Opens a chat against the current context.")
    )
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Title bar icons", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.WarningAccent)
            Spacer(modifier = Modifier.height(8.dp))
            rows.forEach { (icon, name, desc) ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(icon, fontSize = 16.sp, modifier = Modifier.width(28.dp))
                    Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.width(72.dp))
                    Text(mi.iconizedText(desc), fontSize = 13.sp, color = AppColors.TextSecondary, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Icons that aren't relevant to a screen are simply absent — there's nothing to disable.",
                fontSize = 12.sp, color = AppColors.TextSecondary, lineHeight = 16.sp
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
        "info_provider_artificial_analysis" to "Independent benchmarker (key required)",
        "info_provider_llm_stats" to "Pricing + benchmark scores (key required)",
        "info_provider_requesty" to "Cross-provider router catalog (keyless)"
    )
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Info providers", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.WarningAccent)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Third-party services the app reads model metadata + pricing from. The same set that appears on Model Info → Sources. Tap a row for the details.",
                fontSize = 12.sp, color = AppColors.TextSecondary, lineHeight = 16.sp
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
                        color = AppColors.TextPrimary, modifier = Modifier.width(140.dp))
                    Text(taglines[ref.topicId].orEmpty(), fontSize = 12.sp,
                        color = AppColors.TextSecondary, lineHeight = 16.sp,
                        modifier = Modifier.weight(1f))
                    Text(">", color = AppColors.InfoAccent, fontSize = 14.sp)
                }
            }
        }
    }
}

/** One row in the directory of third-party "info
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
    "provider_fireworks" to "Open-weight inference — DeepSeek / Llama / Qwen",
    "provider_cerebras" to "Wafer-scale inference — Llama / Qwen at very high tok/s",
    "provider_sambanova" to "RDU inference — Llama / DeepSeek / Qwen",
    "provider_minimax" to "MiniMax — abab / MiniMax-M family",
    "provider_nvidia" to "NVIDIA NIM — Nemotron + 3rd-party catalog",
    "provider_replicate" to "Replicate — public model marketplace",
    "provider_huggingface" to "HF Inference API — open-weight model serving",
    "provider_deepinfra" to "DeepInfra — open-weight serverless inference",
    "provider_hyperbolic" to "Hyperbolic — open-weight + image/audio inference",
    "provider_novitaai" to "Novita.ai — open-weight serverless inference",
    "provider_nebiusaistudio" to "Nebius AI Studio — Llama / DeepSeek / Qwen",
    "provider_chutes" to "Chutes — Bittensor-backed open-weight serving",
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
            Text("Cloud providers", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.WarningAccent)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "AI services the app dispatches chat / report / embedding calls to. Tap a row for setup, models, pricing, and pitfalls specific to that provider.",
                fontSize = 12.sp, color = AppColors.TextSecondary, lineHeight = 16.sp
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
                        color = AppColors.TextPrimary, modifier = Modifier.width(160.dp))
                    Text(CLOUD_PROVIDER_TAGLINES[topicId].orEmpty(), fontSize = 12.sp,
                        color = AppColors.TextSecondary, lineHeight = 16.sp,
                        modifier = Modifier.weight(1f))
                    Text(">", color = AppColors.InfoAccent, fontSize = 14.sp)
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
    ),
    InfoProviderRef(
        topicId = "info_provider_requesty",
        displayName = "Requesty",
        hostnames = listOf("router.requesty.ai", "requesty.ai")
    ),
    InfoProviderRef(
        topicId = "info_provider_llm_stats",
        displayName = "llm-stats",
        hostnames = listOf("api.llm-stats.com", "llm-stats.com")
    )
)

/** Categories used by [com.ai.data.PricingCache] when calling the
 *  catalog sources. Anything else (Chat, Translation, etc.) is an AI
 *  call, not an info-provider call, even if the hostname matches a
 *  dual-purpose service like OpenRouter. The per-source pricing fetches
 *  carry a "pricing/<source>" category (e.g. "pricing/OpenRouter"). */
private val INFO_FETCH_CATEGORIES = setOf("OpenRouter model specs")
private fun isInfoFetchCategory(category: String?): Boolean =
    category != null && (category in INFO_FETCH_CATEGORIES || category.startsWith("pricing/"))

/** Resolve a URL to one of the 9 info providers. Matches by host
 *  first, then disambiguates via [InfoProviderRef.urlPathPrefix] for
 *  hosts shared by multiple providers (raw.githubusercontent.com).
 *  Returns null when the URL doesn't belong to any of the 9. */
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

/** Resolve a captured trace's URL + category to one of the 9
 *  providers. For dual-purpose services (OpenRouter), the category
 *  must be one of [INFO_FETCH_CATEGORIES]; otherwise a chat
 *  completion would hijack the ℹ️. */
fun infoProviderForTrace(url: String?, category: String?): InfoProviderRef? {
    val ref = infoProviderForUrl(url) ?: return null
    if (ref.requiresChatCategoryGate && !isInfoFetchCategory(category)) return null
    return ref
}
