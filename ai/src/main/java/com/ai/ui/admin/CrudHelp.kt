package com.ai.ui.admin

/** Help content for the CRUD ("Create / Read / Update / Delete") screens.
 *
 *  [crudGenericHelp] holds one screen explaining how every CRUD works in
 *  general; the rest are per-CRUD topics that explain — functionally, not
 *  technically — what the managed data is and why you'd touch it. Each
 *  per-CRUD topic links back to the generic page through
 *  [crudRelatedHelp] (rendered as the "Relevant Help pages" footer). */

/** Shared closing card pointing at the generic CRUD page. Appended to
 *  every per-CRUD topic so each screen explains its data and then says
 *  "and here's how the list / view / add / edit mechanics work". */
private val crudHowItWorksCard = HelpCard(
    "Managing the list",
    "This is a list screen. Tap 👁 to view an entry, 🔧 to manage it, and 🆕 to " +
        "add a new one. Open an entry to read it, then ✏️ to edit or 🗑 to delete. " +
        "See \"How CRUD screens work\" below for the full walkthrough."
)

private fun crud(title: String, vararg cards: HelpCard): HelpContent =
    HelpContent(title, cards.toList() + crudHowItWorksCard)

internal val crudHelp: Map<String, HelpContent> = mapOf(
    "crud_generic" to HelpContent(
        "How CRUD screens work",
        listOf(
            HelpCard(
                "What is a CRUD screen?",
                "Several setup areas of the app are simple managed lists: a list of " +
                    "saved entries you can Create, Read, Update and Delete (\"CRUD\"). " +
                    "Agents, flocks, swarms, system prompts, model overrides and the " +
                    "like all share the same four-screen pattern, so once you know one " +
                    "you know them all."
            ),
            HelpCard(
                "The four screens",
                "• List — every saved entry, one per row. 🆕 adds a new one.\n" +
                    "• View — tap an entry (👁) to read all its fields. From here ✏️ " +
                    "edits, 📄/copy duplicates it as a starting point, and 🗑 deletes it.\n" +
                    "• Add — a blank form to create a new entry.\n" +
                    "• Edit — the same form pre-filled with an existing entry."
            ),
            HelpCard(
                "Saving and deleting",
                "Add / Edit only change anything when you press Save. Delete asks for " +
                    "confirmation first. Changes are local to this device and take " +
                    "effect immediately — there's nothing to publish or sync."
            )
        )
    ),
    "crud_cost_overrides" to crud(
        "Cost overrides",
        HelpCard(
            "What this manages",
            "Your own per-model price corrections. When the app's built-in price " +
                "catalogue is wrong or missing for a model, add an override here so " +
                "report and chat cost estimates come out right for that model."
        )
    ),
    "crud_blocked_models" to crud(
        "Blocked models",
        HelpCard(
            "What this manages",
            "Models you never want to use. A blocked model is hidden from the model " +
                "pickers and skipped by bulk operations, with an optional note saying " +
                "why you blocked it (too expensive, unreliable, deprecated, …)."
        )
    ),
    "crud_model_cooldowns" to crud(
        "Model cooldowns",
        HelpCard(
            "What this manages",
            "Temporary rest periods for a model. After repeated rate-limit or error " +
                "responses a model can be put on a cooldown so the app stops calling " +
                "it until the period passes — useful for flaky or quota-limited models."
        )
    ),
    "crud_inaccessible_models" to crud(
        "Inaccessible models",
        HelpCard(
            "What this manages",
            "Models the app tried but couldn't reach (wrong key, no access, removed). " +
                "Keeping them listed here keeps the pickers clean; remove an entry to " +
                "give the model another chance once you think it should work again."
        )
    ),
    "crud_default_meta_items" to crud(
        "Default meta items",
        HelpCard(
            "What this manages",
            "A list of meta prompts that run automatically. Each row pins one " +
                "Meta prompt (from Prompt management → Meta) to a target — either an " +
                "Agent, or a specific Provider + Model."
        ),
        HelpCard(
            "When they run",
            "Whenever a report's models all finish, one meta result is created for " +
                "every row here — no need to start it by hand. A row is skipped if its " +
                "meta prompt no longer exists, its target can't be resolved, or that " +
                "report already has a result for that meta prompt."
        ),
        HelpCard(
            "Target precedence",
            "If both Provider + Model are set they win; otherwise the named Agent is " +
                "used. An agent name that matches a provider falls back to that " +
                "provider's default model. Bundled defaults come from assets/meta.json " +
                "and reappear after a delete on the next launch."
        )
    ),
    "crud_model_types" to crud(
        "Manual model types",
        HelpCard(
            "What this manages",
            "Your hand-set classification for a model — for example marking a model as " +
                "a reasoning, vision or embedding model when auto-detection got it " +
                "wrong. This steers where the model shows up and how it's used."
        )
    ),
    "crud_test_excluded" to crud(
        "Test-excluded models",
        HelpCard(
            "What this manages",
            "Models to leave out of the connectivity / capability test runs. Exclude " +
                "models that are slow, costly or known-good so the test sweeps stay " +
                "fast and cheap."
        )
    ),
    "crud_parameters" to crud(
        "Parameters",
        HelpCard(
            "What this manages",
            "Saved sets of generation settings (temperature, max tokens, top-p and the " +
                "like) that you can name and re-apply to reports and chats, instead of " +
                "re-typing the same tuning every time."
        )
    ),
    "crud_example_prompts" to crud(
        "Example prompts",
        HelpCard(
            "What this manages",
            "A personal library of reusable prompts. Save the prompts you keep coming " +
                "back to here and pick one when starting a report or chat rather than " +
                "writing it again."
        )
    ),
    "crud_internal_prompts" to crud(
        "Internal prompts",
        HelpCard(
            "What this manages",
            "The behind-the-scenes prompts the app uses for its own helper steps — " +
                "meta prompts, fan-out, rerank, moderation, translation and titling. " +
                "Edit these to change how those automatic operations behave."
        )
    ),
    "crud_system_prompts" to crud(
        "System prompts",
        HelpCard(
            "What this manages",
            "Reusable system prompts — the standing instructions that set a model's " +
                "role or tone for a whole report or chat. Save the ones you use often " +
                "and apply them by name."
        )
    ),
    "crud_agents" to crud(
        "Agents",
        HelpCard(
            "What this manages",
            "An agent is a saved provider + model + its settings, given a name. Agents " +
                "are the building block you pick when launching a report or chat, and " +
                "they're what flocks and swarms are made of."
        )
    ),
    "crud_flocks" to crud(
        "Flocks",
        HelpCard(
            "What this manages",
            "A flock is a named bundle of agents you launch together so the same prompt " +
                "runs across several models at once. Manage which agents belong to each " +
                "flock here."
        )
    ),
    "crud_swarms" to crud(
        "Swarms",
        HelpCard(
            "What this manages",
            "A swarm is a larger grouping built for multi-step / fan-out style runs. " +
                "Manage the swarm's membership and configuration here; see the glossary " +
                "for how swarms differ from flocks."
        )
    )
)

/** Link every per-CRUD topic to the generic "How CRUD screens work" page
 *  (rendered as the Relevant Help pages footer). */
internal val crudRelatedHelp: Map<String, List<String>> =
    crudHelp.keys.filter { it != "crud_generic" }.associateWith { listOf("crud_generic") }
