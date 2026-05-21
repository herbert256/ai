package com.ai.ui.admin

/** Help content for every per-screen ❓ topic in the app. Pure data
 *  so the HelpScreen rendering layer stays compact — HelpScreen.kt
 *  loads the right [HelpContent] for the topic id and walks its
 *  [HelpCard] list through [HelpSection].
 *
 *  Both data classes are `internal` so the rendering composables in
 *  the same package can use them directly, but they stay invisible
 *  to other packages — outside callers go through [HelpScreen]. */
internal data class HelpCard(val title: String, val body: String)
internal data class HelpContent(val title: String, val cards: List<HelpCard>)

internal val HELP_TOPICS: Map<String, HelpContent> =
    providerSettingsHelp +
    infoProviderHelp +
    glossaryHelp +
    reportsHelp +
    searchHelp +
    settingsAdminHelp +
    developerHelp +
    chatHelp +
    modelsHelp +
    providerCatalogHelp


/** Per-screen → home-help cross-link table. Keyed by topic id; the
 *  value is the ordered list of home-help topic ids the user reading
 *  this screen's help would likely also benefit from. Rendered as
 *  the "Relevant Help pages" footer at the bottom of any per-topic
 *  help page (HelpScreen.kt). Topics with no entry get no footer.
 *
 *  Reach is intentionally wide — most per-screen topics carry at
 *  least one cross-link. The home-help reference pages themselves
 *  also appear as keys (e.g. help_translations → concepts) so the
 *  user can hop between the reference pages without going back to
 *  Help home first. */
internal val RELATED_HOME_HELP: Map<String, List<String>> = mapOf(
    // ===== Reports / generation =====
    "reports_hub" to listOf("help_about", "help_getting_started", "concepts", "help_glossary_operations"),
    "new_ai_report_screen" to listOf("help_getting_started", "help_glossary_operations"),
    "search_ai_reports_screen" to listOf("help_glossary_operations"),
    "all_ai_reports_screen" to listOf("help_glossary_operations"),
    "report_new" to listOf("help_getting_started", "help_glossary_operations", "help_costs"),
    "report_select_models" to listOf("help_glossary_blocks", "help_glossary_groupings", "help_glossary_operations", "help_costs"),
    "report_run" to listOf("concepts", "help_costs", "help_glossary_operations", "help_translations"),
    "report_view_picker" to listOf("help_glossary_operations"),
    "report_edit_picker" to listOf("help_glossary_operations"),
    "report_pick_flock" to listOf("help_glossary_groupings", "help_glossary_blocks"),
    "report_pick_agent" to listOf("help_glossary_blocks", "help_glossary_groupings"),
    "report_pick_previous" to listOf("help_glossary_operations"),
    "report_pick_swarm" to listOf("help_glossary_groupings", "help_glossary_blocks"),
    "report_pick_model" to listOf("help_glossary_blocks", "help_costs"),
    "report_swarm_info" to listOf("help_glossary_groupings", "help_glossary_blocks"),
    "report_flock_info" to listOf("help_glossary_groupings", "help_glossary_blocks"),
    "report_continue_in_chat" to listOf("help_glossary_operations"),
    "report_meta" to listOf("help_glossary_operations", "concepts", "help_costs"),
    "report_edit_prompt" to listOf("help_glossary_operations"),
    "report_edit_title" to listOf("help_glossary_operations"),
    "report_parameters" to listOf("help_glossary_blocks", "concepts"),
    "report_export" to listOf("help_glossary_operations", "help_privacy", "help_translations"),
    "report_manage" to listOf("help_glossary_operations", "help_costs", "concepts"),
    "report_html_preview" to listOf("help_glossary_operations", "help_translations"),
    "report_meta_run" to listOf("help_glossary_operations", "help_costs"),
    "report_single_result" to listOf("help_glossary_operations", "help_costs"),
    "report_fan_out_confirm" to listOf("help_glossary_operations", "help_costs"),

    // ===== Translation =====
    "translation_run_l1" to listOf("help_translations", "concepts", "help_glossary_operations", "help_costs"),
    "translation_run_l2" to listOf("help_translations", "concepts"),
    "translation_run_l3" to listOf("help_translations", "concepts"),
    "translation_compare" to listOf("help_translations", "concepts"),
    "translation_language" to listOf("help_translations"),
    "translation_models" to listOf("help_translations", "help_costs", "help_glossary_blocks"),

    // ===== Fan-out / Meta / Secondary results =====
    "secondary_list" to listOf("help_glossary_operations", "concepts"),
    "secondary_detail" to listOf("help_glossary_operations"),
    "secondary_scope" to listOf("help_glossary_operations"),
    "secondary_fan_out_l1" to listOf("help_glossary_operations", "concepts", "help_costs"),
    "secondary_fan_out_l2" to listOf("help_glossary_operations", "concepts"),
    "secondary_fan_out_l3" to listOf("help_glossary_operations", "concepts"),
    "secondary_fan_out_onepage" to listOf("help_glossary_operations"),
    "moderation_call_detail" to listOf("help_glossary_operations", "help_privacy"),

    // ===== Chat =====
    "chat_hub" to listOf("help_glossary_operations", "help_about"),
    "chat_session" to listOf("help_glossary_operations", "concepts"),
    "chat_parameters" to listOf("help_glossary_blocks"),
    "chat_history" to listOf("help_glossary_operations"),
    "chat_continue" to listOf("help_glossary_operations"),
    "chat_manage" to listOf("help_glossary_operations"),
    "chat_search" to listOf("help_glossary_operations"),
    "dual_chat_setup" to listOf("help_glossary_operations", "help_glossary_blocks"),
    "dual_chat_session" to listOf("help_glossary_operations"),

    // ===== Provider settings (per-card) =====
    "provider_card_state" to listOf("help_home_ai_providers", "concepts"),
    "provider_card_apikey" to listOf("help_home_ai_providers", "help_privacy"),
    "provider_card_basics" to listOf("help_home_ai_providers"),
    "provider_card_api" to listOf("help_home_ai_providers", "concepts"),
    "provider_card_models" to listOf("help_home_ai_providers", "help_home_info_providers"),
    "provider_card_pricing" to listOf("help_costs", "help_home_info_providers"),
    "provider_card_throttle" to listOf("concepts", "help_home_ai_providers"),
    "provider_card_features" to listOf("help_home_ai_providers"),
    "provider_card_native" to listOf("help_home_ai_providers"),
    "provider_card_capability" to listOf("help_home_ai_providers"),
    "provider_card_patterns" to listOf("help_home_ai_providers"),
    "provider_card_endpoints" to listOf("help_home_ai_providers", "concepts"),
    "providers" to listOf("help_home_ai_providers", "help_getting_started", "help_privacy"),
    "provider_edit" to listOf("help_home_ai_providers", "help_costs"),

    // ===== Info providers =====
    "info_provider_huggingface" to listOf("help_home_info_providers", "help_costs"),
    "info_provider_openrouter" to listOf("help_home_info_providers", "help_costs", "help_home_ai_providers"),
    "info_provider_litellm" to listOf("help_home_info_providers", "help_costs"),
    "info_provider_models_dev" to listOf("help_home_info_providers", "help_costs"),
    "info_provider_helicone" to listOf("help_home_info_providers", "help_costs"),
    "info_provider_llm_prices" to listOf("help_home_info_providers", "help_costs"),
    "info_provider_artificial_analysis" to listOf("help_home_info_providers", "help_costs"),

    // ===== Models / model metadata =====
    "models" to listOf("help_home_ai_providers", "help_glossary_blocks"),
    "model_edit" to listOf("help_glossary_blocks", "help_costs"),
    "model_info" to listOf("help_glossary_blocks", "help_costs", "help_home_info_providers"),
    "model_pick_provider" to listOf("help_home_ai_providers", "help_glossary_blocks"),
    "model_pick_model" to listOf("help_glossary_blocks"),
    "model_pick_agent" to listOf("help_glossary_blocks", "help_glossary_groupings"),
    "model_raw" to listOf("help_glossary_blocks"),
    "models_per_provider" to listOf("help_home_ai_providers", "help_glossary_blocks"),
    "models_search" to listOf("help_glossary_blocks"),
    "model_types" to listOf("help_glossary_blocks"),
    "manual_model_types" to listOf("help_glossary_blocks"),
    "manual_model_types_list" to listOf("help_glossary_blocks"),
    "model_cooldowns" to listOf("concepts", "help_glossary_blocks"),
    "model_cooldowns_list" to listOf("concepts", "help_glossary_blocks"),
    "blocked_models" to listOf("help_glossary_blocks"),
    "blocked_model_edit" to listOf("help_glossary_blocks"),
    "test_excluded_models" to listOf("help_glossary_blocks"),
    "test_excluded_model_edit" to listOf("help_glossary_blocks"),
    "inaccessible_models" to listOf("help_glossary_blocks", "help_home_ai_providers"),

    // ===== Agents / Flocks / Swarms / Parameters / Prompts =====
    "agents" to listOf("help_glossary_blocks", "help_glossary_groupings", "help_getting_started"),
    "agents_list" to listOf("help_glossary_blocks", "help_glossary_groupings"),
    "agent_edit" to listOf("help_glossary_blocks"),
    "flocks" to listOf("help_glossary_groupings", "help_glossary_blocks"),
    "flocks_list" to listOf("help_glossary_groupings", "help_glossary_blocks"),
    "flock_edit" to listOf("help_glossary_groupings", "help_glossary_blocks"),
    "swarms" to listOf("help_glossary_groupings", "help_glossary_blocks"),
    "swarms_list" to listOf("help_glossary_groupings", "help_glossary_blocks"),
    "swarm_edit" to listOf("help_glossary_groupings", "help_glossary_blocks"),
    "parameters" to listOf("help_glossary_blocks"),
    "parameters_list" to listOf("help_glossary_blocks"),
    "parameters_edit" to listOf("help_glossary_blocks"),
    "system_prompts" to listOf("help_glossary_blocks", "help_glossary_operations"),
    "system_prompts_list" to listOf("help_glossary_blocks"),
    "system_prompt_edit" to listOf("help_glossary_blocks"),
    "internal_prompts_hub" to listOf("help_glossary_operations"),
    "fan_in_out_prompts_hub" to listOf("help_glossary_operations"),
    "internal_prompts" to listOf("help_glossary_operations"),
    "internal_prompts_list" to listOf("help_glossary_operations"),
    "internal_prompt_edit" to listOf("help_glossary_operations"),

    // ===== Costs / Usage / Statistics =====
    "statistics" to listOf("help_costs", "help_privacy"),
    "cost_config" to listOf("help_costs", "help_home_info_providers"),
    "cost_override" to listOf("help_costs", "help_home_info_providers"),
    "cost_view" to listOf("help_costs"),
    "usage_statistics" to listOf("help_costs"),

    // ===== API tracing / Developer =====
    "trace_list" to listOf("concepts", "help_privacy"),
    "trace_detail" to listOf("concepts", "help_privacy", "help_home_info_providers"),
    "trace_pick_model" to listOf("concepts"),
    "developer_test" to listOf("concepts", "help_home_ai_providers"),
    "developer_edit" to listOf("help_home_ai_providers", "concepts"),
    "developer_select_model" to listOf("help_glossary_blocks"),
    "developer_select_endpoint" to listOf("help_home_ai_providers"),
    "external_services" to listOf("help_home_info_providers", "help_home_ai_providers"),
    "external_intent" to listOf("help_privacy", "help_glossary_operations"),

    // ===== Settings =====
    "settings_main" to listOf("help_about", "help_privacy"),
    "settings_other" to listOf("help_about", "help_privacy"),
    "settings_network" to listOf("concepts", "help_about"),
    "settings_network_api_calls" to listOf("concepts", "help_costs"),
    "settings_ui" to listOf("help_about"),
    "settings_logging" to listOf("help_privacy", "concepts"),
    "settings_setup" to listOf("help_about", "help_getting_started"),

    // ===== Housekeeping / Backup / Reset =====
    "housekeeping" to listOf("help_backup", "help_privacy"),
    "backup_restore" to listOf("help_backup", "help_privacy"),
    "trim_by_age" to listOf("help_backup", "help_privacy"),
    "reset" to listOf("help_backup", "help_privacy"),
    "reset_runtime" to listOf("help_privacy"),
    "reset_info_providers" to listOf("help_home_info_providers", "help_privacy"),
    "reset_configuration" to listOf("help_privacy"),
    "reset_assets" to listOf("help_privacy"),
    "reset_application" to listOf("help_backup", "help_privacy"),
    "import_export" to listOf("help_backup", "help_privacy"),

    // ===== Refresh / Update =====
    "refresh" to listOf("help_home_info_providers", "help_home_ai_providers", "help_getting_started"),
    "refresh_info_providers" to listOf("help_home_info_providers", "help_costs"),
    "refresh_all" to listOf("help_home_info_providers", "help_home_ai_providers", "help_getting_started"),
    "refresh_result" to listOf("help_home_info_providers"),
    "update_from_cloud" to listOf("help_home_info_providers"),

    // ===== Test =====
    "test" to listOf("help_home_ai_providers", "concepts"),
    "test_all_models_l1" to listOf("help_home_ai_providers", "concepts"),
    "test_all_models_l2" to listOf("help_home_ai_providers", "concepts"),
    "test_all_models_l3" to listOf("help_home_ai_providers", "concepts"),
    "test_all_models_select" to listOf("help_home_ai_providers"),

    // ===== Setup hubs =====
    "setup_models" to listOf("help_home_ai_providers", "help_getting_started"),
    "setup_workers" to listOf("help_glossary_blocks", "help_glossary_groupings"),
    "setup_prompts" to listOf("help_glossary_operations"),

    // ===== Prompts / examples / history =====
    "example_prompts_list" to listOf("help_glossary_operations"),
    "example_prompt_edit" to listOf("help_glossary_operations"),
    "example_prompt_picker" to listOf("help_glossary_operations"),
    "prompt_view" to listOf("help_glossary_operations"),
    "prompt_history" to listOf("help_glossary_operations"),
    "history" to listOf("help_glossary_operations", "help_about"),

    // ===== Share / external content =====
    "share_target" to listOf("help_glossary_operations", "help_privacy"),

    // ===== App log =====
    "applog_list" to listOf("help_privacy", "concepts"),
    "applog_detail" to listOf("help_privacy"),

    // ===== Icon lookup / content viewers =====
    "icon_lookup_main" to listOf("help_home_icons"),
    "icon_lookup_agent" to listOf("help_home_icons", "help_glossary_blocks"),
    "icon_lookup_meta" to listOf("help_home_icons", "help_glossary_operations"),
    "icon_lookup_translation" to listOf("help_home_icons", "help_translations"),
    "icon_lookup_language" to listOf("help_home_icons", "help_translations"),
    "icon_lookup_pair" to listOf("help_home_icons", "help_glossary_operations"),
    "find_icons_selection" to listOf("help_home_icons"),
    "alternative_icons" to listOf("help_home_icons"),
    "content_model_response" to listOf("help_glossary_operations"),
    "content_one_page" to listOf("help_glossary_operations"),

    // ===== Home-help cross-links (concept-to-concept) =====
    "help_about" to listOf("help_getting_started", "concepts"),
    "help_getting_started" to listOf("help_about", "concepts"),
    "concepts" to listOf("help_glossary", "help_translations", "help_costs"),
    "help_glossary" to listOf("concepts", "help_about"),
    "help_glossary_blocks" to listOf("help_glossary", "help_glossary_groupings"),
    "help_glossary_groupings" to listOf("help_glossary", "help_glossary_blocks"),
    "help_glossary_operations" to listOf("help_glossary", "concepts"),
    "help_costs" to listOf("concepts", "help_home_info_providers"),
    "help_privacy" to listOf("help_backup"),
    "help_backup" to listOf("help_privacy"),
    "help_translations" to listOf("concepts", "help_glossary_operations", "help_costs")
)

