package com.ai.ui.navigation

import java.net.URLEncoder

object NavRoutes {
    const val AI = "ai"
    const val SETTINGS = "settings"
    const val SETTINGS_PROVIDER_EDIT = "settings_provider_edit/{providerId}"
    const val SETTINGS_AGENT_EDIT = "settings_agent_edit/{agentId}"
    const val SETTINGS_FLOCK_EDIT = "settings_flock_edit/{flockId}"
    const val SETTINGS_SWARM_EDIT = "settings_swarm_edit/{swarmId}"
    const val SETTINGS_INTERNAL_PROMPT_EDIT = "settings_internal_prompt_edit/{promptId}"
    const val SETTINGS_PARAMETERS = "settings_parameters"
    const val SETTINGS_SYSTEM_PROMPTS = "settings_system_prompts"
    const val SETTINGS_AGENTS = "settings_agents"
    const val SETTINGS_FLOCKS = "settings_flocks"
    const val SETTINGS_SWARMS = "settings_swarms"
    const val SETTINGS_BLOCKED_MODELS = "settings_blocked_models"
    const val SETTINGS_INACCESSIBLE_MODELS = "settings_inaccessible_models"
    const val SETTINGS_MODEL_COOLDOWNS = "settings_model_cooldowns"
    const val SETTINGS_MANUAL_MODEL_TYPES = "settings_manual_model_types"
    const val SETTINGS_TEST_EXCLUDED_MODELS = "settings_test_excluded_models"
    const val SETTINGS_MODELS_SETUP = "settings_models_setup"
    const val SETTINGS_LOGGING = "settings_logging"
    const val SETTINGS_INTERNAL_PROMPTS_BY_CATEGORY = "settings_internal_prompts/{category}"
    const val HELP = "help"
    const val HELP_FOR_TOPIC = "help/{topicId}"
    const val DOCUMENTATION = "documentation"
    const val DOCUMENTATION_MANUAL = "documentation_manual"
    const val ABOUT = "about"
    const val TRACE_LIST = "trace_list"
    const val TRACE_LIST_FOR_REPORT = "trace_list/{reportId}"
    const val TRACE_LIST_FOR_REPORT_CATEGORY = "trace_list/{reportId}/category/{category}"
    const val TRACE_LIST_FOR_MODEL = "trace_list_for_model/{provider}/{model}"
    const val TRACE_LIST_FOR_RUN = "trace_list_for_run/{runId}"
    /** Trace list pre-filtered by an arbitrary dimension — wired by the
     *  API-trace-statistics drill-downs. All four args optional. */
    const val TRACE_LIST_FILTERED =
        "trace_list_filtered?host={host}&status={status}&category={category}&model={model}&provider={provider}"
    const val TRACE_DETAIL = "trace_detail/{filename}"
    const val AI_HISTORY = "ai_history"
    const val AI_REPORTS_HUB = "ai_reports_hub"
    /** Wrapper screen with three tap-through rows that previously
     *  lived in the Reports hub's "Start" card. Routes to
     *  [AI_NEW_REPORT] for the actual form screen. */
    const val AI_NEW_REPORT_HUB = "ai_new_report_hub"
    /** Wrapper screen with four search-mode tap-throughs that used
     *  to live in the Reports hub's "Search" card. */
    const val AI_SEARCH_REPORTS = "ai_search_reports"
    /** Paginated browser of every saved report — swipe-paged
     *  HorizontalPager with auto-fit rows-per-page. */
    const val AI_ALL_REPORTS = "ai_all_reports"
    /** Standalone "AI Examples" screen — the Reports hub's example
     *  card as a full screen, shown from home when no agents exist. */
    const val AI_EXAMPLES = "ai_examples"
    const val AI_NEW_REPORT = "ai_new_report"
    const val AI_NEW_REPORT_WITH_PARAMS = "ai_new_report/{title}/{prompt}"
    const val AI_PROMPT_HISTORY = "ai_prompt_history"
    const val AI_EXAMPLE_PROMPT_PICKER = "ai_example_prompt_picker"
    /** Pattern carries an optional `initialView` query-param consumed
     *  by the AI_REPORTS composable. Existing call sites navigate with
     *  the bare string ("ai_reports"); the per-row 👁 View icon uses
     *  [aiReportView] which appends `?initialView=true` so the
     *  destination seeds [ReportsScreenNav]'s View tile-grid overlay
     *  on first composition. A second optional
     *  `initialReportsAgentId` further seeds the View tile grid's
     *  per-agent Reports sub-overlay (used by Model Info View's
     *  Last-Usage rows). */
    const val AI_REPORTS = "ai_reports?initialView={initialView}&initialReportsAgentId={initialReportsAgentId}&initialManageOverlay={initialManageOverlay}"
    /** Helper for the row-level 🔧 Manage icon — same destination
     *  the existing navigation uses. Pass [overlay] (a
     *  `ManagePickKind.arg`) to seed a Manage sub-overlay on entry,
     *  used when a report is picked from a Manage screen's 🗂️. */
    fun aiReportManage(overlay: String? = null) =
        if (overlay.isNullOrBlank()) "ai_reports"
        else "ai_reports?initialManageOverlay=$overlay"
    /** Bare entry to the reports screen (Manage, no seeds). Use this
     *  for plain `navigate` calls — navigating with the [AI_REPORTS]
     *  template constant instead passes the literal `{…}` placeholders
     *  as arg VALUES (e.g. initialReportsAgentId = "{initialReportsAgentId}",
     *  non-blank), which wrongly seeds the View grid's per-agent Reports
     *  sub-overlay. The template constant is only for the `composable`
     *  registration + popUpTo/popBackStack matching. */
    fun aiReports() = "ai_reports"
    /** Helper for the row-level 👁 View icon — seeds the View
     *  tile grid (`showViewReportScreen`) on first composition only. */
    fun aiReportView() = "ai_reports?initialView=true"
    /** Helper for Model Info View's Last-Usage rows. Seeds the
     *  View tile grid AND the Reports sub-overlay scrolled to
     *  the supplied agent id. */
    fun aiReportViewAtAgent(agentId: String) =
        "ai_reports?initialView=true&initialReportsAgentId=$agentId"
    const val AI_SEARCH = "ai_search"
    const val AI_LOCAL_SEARCH = "ai_local_search"
    const val AI_QUICK_LOCAL_SEARCH = "ai_quick_local_search"
    const val AI_REPORT_MANAGE = "ai_report_manage"
    const val AI_CHAT_MANAGE = "ai_chat_manage"
    const val AI_KNOWLEDGE = "ai_knowledge"
    const val AI_KNOWLEDGE_NEW = "ai_knowledge_new"
    const val AI_KNOWLEDGE_DETAIL = "ai_knowledge_detail/{kbId}"
    const val AI_LOCAL_SEMANTIC_SEARCH = "ai_local_semantic_search"
    const val AI_MONITOR = "ai_monitor"
    const val AI_STATISTICS = "ai_statistics"
    const val AI_LIVE_DASHBOARD = "ai_live_dashboard"
    const val AI_SPEND_USAGE = "ai_spend_usage"
    const val AI_COSTS_TIER = "ai_costs_tier"
    const val AI_STAT_REPORTS = "ai_stat_reports"
    const val AI_STAT_PROVIDERS = "ai_stat_providers"
    const val AI_STAT_MODELS = "ai_stat_models"
    const val AI_USAGE_PROVIDER = "ai_usage_provider/{providerId}"
    const val AI_TRACE_STATS = "ai_trace_stats"
    const val AI_TRACE_BREAKDOWN = "ai_trace_breakdown/{dim}"
    const val AI_LOG_STATS = "ai_log_stats"
    const val AI_COST_CONFIG = "ai_cost_config"
    const val AI_COSTS_MAINTENANCE = "ai_costs_maintenance"
    const val AI_SETUP = "ai_setup"
    const val AI_HOUSEKEEPING = "ai_housekeeping"
    const val AI_BACKUP_RESTORE = "ai_backup_restore"
    const val AI_IMPORT_EXPORT = "ai_import_export"
    const val AI_TRIM_BY_AGE = "ai_trim_by_age"
    const val AI_UPDATE_FROM_CLOUD = "ai_update_from_cloud"
    const val AI_RESET = "ai_reset"
    const val AI_RESET_RUNTIME = "ai_reset_runtime"
    const val AI_RESET_INFO_PROVIDERS = "ai_reset_info_providers"
    const val AI_RESET_CONFIGURATION = "ai_reset_configuration"
    const val AI_RESET_ASSETS = "ai_reset_assets"
    const val AI_RESET_APPLICATION = "ai_reset_application"
    const val AI_REFRESH = "ai_refresh"
    const val AI_TEST = "ai_test"
    const val AI_TEST_ALL_MODELS = "ai_test_all_models"
    const val AI_STRESS_TEST = "ai_stress_test"
    const val AI_STRESS_DASHBOARD = "ai_stress_dashboard"
    const val AI_APPLOG_LIST = "ai_applog_list"
    const val AI_APPLOG_DETAIL = "ai_applog_detail/{filename}?search={search}"
    const val AI_CHATS_HUB = "ai_chats_hub"
    const val AI_CHAT_AGENT_SELECT = "ai_chat_agent_select"
    const val AI_CHAT_WITH_AGENT = "ai_chat_with_agent/{agentId}"
    const val AI_CHAT_SEARCH = "ai_chat_search"
    const val AI_CHAT_PROVIDER = "ai_chat_provider"
    const val AI_CHAT_PARAMS = "ai_chat_params/{provider}/{model}"
    const val AI_CHAT_SESSION = "ai_chat_session/{provider}/{model}"
    const val AI_CHAT_HISTORY = "ai_chat_history"
    const val AI_CHAT_CONTINUE = "ai_chat_continue/{sessionId}"
    const val AI_MODEL_SEARCH = "ai_model_search"
    const val AI_MODEL_INFO = "ai_model_info/{provider}/{model}"
    const val AI_MODEL_INFO_VIEW = "ai_model_info_view/{provider}/{model}"
    const val AI_AGENT_VIEW = "ai_agent_view/{agentId}"
    const val AI_FLOCK_VIEW = "ai_flock_view/{flockId}"
    const val AI_SWARM_VIEW = "ai_swarm_view/{swarmId}"
    const val AI_PROVIDER_VIEW = "ai_provider_view/{provider}"
    const val AI_REPORT_INFO = "ai_report_info/{reportId}"
    const val AI_REPORT_MODEL = "ai_report_model/{reportId}/{agentId}"
    const val AI_VIEW_PICK_REPORT = "ai_view_pick_report"
    /** Filtered "pick a report" screen opened from a Manage screen's
     *  🗂️ — `{kind}` is a [ManagePickKind.arg]. */
    const val AI_MANAGE_PICK_REPORT = "ai_manage_pick_report/{kind}"
    fun aiManagePickReport(kind: String) = "ai_manage_pick_report/$kind"
    const val AI_MANUAL_OVERRIDE_ADD = "ai_manual_override_add/{provider}/{model}"
    const val AI_MANUAL_COST_OVERRIDE_ADD = "ai_manual_cost_override_add/{provider}/{model}"
    const val AI_API_TEST = "ai_api_test"
    const val AI_API_TEST_EDIT = "ai_api_test_edit"
    const val AI_DUAL_CHAT_SETUP = "ai_dual_chat_setup"
    const val AI_DUAL_CHAT_SESSION = "ai_dual_chat_session"
    const val DEVELOPER_OPTIONS = "developer_options"

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun aiReportInfo(reportId: String) = "ai_report_info/$reportId"
    fun aiReportModel(reportId: String, agentId: String) = "ai_report_model/$reportId/$agentId"

    fun traceDetail(filename: String) = "trace_detail/$filename"
    fun aiAppLogDetail(filename: String, search: String = "") =
        "ai_applog_detail/$filename?search=${encode(search)}"
    fun traceListForReport(reportId: String) = "trace_list/$reportId"
    fun traceListForReportCategory(reportId: String, category: String) =
        "trace_list/$reportId/category/${encode(category)}"
    fun settingsProviderEdit(providerId: String) = "settings_provider_edit/$providerId"
    fun settingsAgentEdit(agentId: String) = "settings_agent_edit/$agentId"
    fun settingsFlockEdit(flockId: String) = "settings_flock_edit/$flockId"
    fun settingsSwarmEdit(swarmId: String) = "settings_swarm_edit/$swarmId"
    fun settingsInternalPromptEdit(promptId: String) = "settings_internal_prompt_edit/$promptId"
    fun settingsInternalPromptsByCategory(category: String) = "settings_internal_prompts/${encode(category)}"
    fun traceListForModel(provider: String, model: String) = "trace_list_for_model/$provider/${encode(model)}"
    fun traceListForRun(runId: String) = "trace_list_for_run/${encode(runId)}"
    /** Build a filtered trace-list route, encoding only the present args. */
    fun traceListFiltered(host: String? = null, status: String? = null,
                          category: String? = null, model: String? = null,
                          provider: String? = null): String {
        val q = listOfNotNull(
            host?.let { "host=${encode(it)}" },
            status?.let { "status=${encode(it)}" },
            category?.let { "category=${encode(it)}" },
            model?.let { "model=${encode(it)}" },
            provider?.let { "provider=${encode(it)}" },
        ).joinToString("&")
        return "trace_list_filtered" + if (q.isEmpty()) "" else "?$q"
    }
    fun aiTraceBreakdown(dim: String) = "ai_trace_breakdown/$dim"
    fun aiUsageProvider(providerId: String) = "ai_usage_provider/${encode(providerId)}"
    fun aiModelInfo(provider: String, model: String) = "ai_model_info/$provider/${encode(model)}"
    fun aiModelInfoView(provider: String, model: String) = "ai_model_info_view/$provider/${encode(model)}"
    fun aiAgentView(agentId: String) = "ai_agent_view/$agentId"
    fun aiFlockView(flockId: String) = "ai_flock_view/$flockId"
    fun aiSwarmView(swarmId: String) = "ai_swarm_view/$swarmId"
    fun aiProviderView(providerId: String) = "ai_provider_view/$providerId"
    fun aiManualOverrideAdd(provider: String, model: String) = "ai_manual_override_add/$provider/${encode(model)}"
    fun aiManualCostOverrideAdd(provider: String, model: String) = "ai_manual_cost_override_add/$provider/${encode(model)}"
    fun aiChatContinue(sessionId: String) = "ai_chat_continue/$sessionId"
    fun aiKnowledgeDetail(kbId: String) = "ai_knowledge_detail/$kbId"
    fun aiChatWithAgent(agentId: String) = "ai_chat_with_agent/$agentId"
    fun aiChatParams(provider: String, model: String) = "ai_chat_params/$provider/${encode(model)}"
    fun aiChatSession(provider: String, model: String) = "ai_chat_session/$provider/${encode(model)}"
    fun aiNewReportWithParams(title: String, prompt: String) = "ai_new_report/${encode(title)}/${encode(prompt)}"
    fun helpForTopic(topicId: String) = "help/${encode(topicId)}"
}
