package com.ai.ui.admin

internal val localKnowledgeHelp: Map<String, HelpContent> = mapOf(
    "settings_ui_colors" to HelpContent(
        title = "Help - UI Colors",
        cards = listOf(
            HelpCard("Overview", "Color overrides for the app shell and shared UI roles. The screen is fed from AppColors defaults, so every row starts with the current factory value and updates the live palette as soon as you pick a color."),
            HelpCard("Color rows", "Rows are grouped by function rather than old color names: app background, title text, subtitle text, primary text, secondary text, dim text, card background, button background, accents, minor surfaces, and borders."),
            HelpCard("Combined rows", "Some rows intentionally write one value to several AppColors keys. Accent also drives InfoAccent; Card Background drives both CardBackgroundAlt and CardBackground; Text Secondary also drives tertiary text; Text Dim also drives disabled and very dim text."),
            HelpCard("Persistence", "Changes autosave after a short debounce and are flushed again when you leave the screen. A row's Default button returns that one role to the AppColors factory value; the 🧽 icon in the bottom bar clears every override at once, restoring the whole palette to factory."),
            HelpCard("Scope", "This affects app UI colors, including cards, buttons, title text, subtitles, status accents, borders, and the Android status/navigation bar background. It does not change provider/model-generated content or report icons.")
        )
    ),
    "setup_local_models" to HelpContent(
        title = "Help - Local models",
        cards = listOf(
            HelpCard("Overview", "Hub for on-device model assets. Local LLMs are .task chat/completion bundles that drive the synthetic Local provider. Local LiteRT models are .tflite text embedders used by Local semantic search and local-embedder Knowledge bases."),
            HelpCard("Counts", "The number on each row is the number of installed assets currently found in app storage. Counts refresh when you return to the hub."),
            HelpCard("Privacy", "Once a model is installed, local LLM and local embedding calls run on the device. Cloud providers are not contacted by these local flows."),
            HelpCard("Storage", "Local model files can be large and live inside app-private storage. Backup/restore deliberately excludes the large local model directories; reinstall or re-import them after a full restore.")
        )
    ),
    "local_litert_models" to HelpContent(
        title = "Help - Local LiteRT models",
        cards = listOf(
            HelpCard("Overview", "Installs on-device text embedders for Local semantic search and Knowledge bases that use the Local embedder. These are MediaPipe/LiteRT .tflite models with Tasks metadata."),
            HelpCard("Curated downloads", "Each download button installs a known-compatible embedder into app storage. Already-installed models show as installed and the download button is disabled."),
            HelpCard("Add model from file", "Imports a .tflite from Android's file picker. The app loads it as a MediaPipe text embedder before publishing it; a plain TensorFlow Lite model without the required Tasks metadata is rejected immediately instead of appearing as a broken installed model."),
            HelpCard("Installed list", "Installed models are listed by name with a Remove action. Removing releases the model if loaded and deletes the local .tflite file."),
            HelpCard("Where they appear", "Installed embedders show in Local semantic search and as Local options when creating a Knowledge base.")
        )
    ),
    "local_llms" to HelpContent(
        title = "Help - Local LLMs",
        cards = listOf(
            HelpCard("Overview", "Installs on-device MediaPipe GenAI chat models. Installed models surface through the synthetic Local provider, so report and chat flows can run without a cloud API key."),
            HelpCard("Runtime first", "The native LLM runtime is downloaded separately and must be installed before imported .task models can run."),
            HelpCard("Model sources", "The screen links out to recommended model pages because many useful models require accepting license terms before download. After downloading, import the .task file or a supported archive from the file picker."),
            HelpCard("Installed list", "Installed local LLMs are stored in app-private storage and can be removed from this screen. Removing a model only deletes the local file; it does not affect reports already saved."),
            HelpCard("Limits", "Local LLM quality and speed depend on device hardware and the model bundle. Large models can be slow or fail to load on memory-constrained devices.")
        )
    ),
    "search_local_semantic" to HelpContent(
        title = "Help - Local semantic search",
        cards = listOf(
            HelpCard("Overview", "Meaning-based search across saved reports using an installed on-device embedder. No cloud provider is called for the query or report embeddings."),
            HelpCard("Model picker", "Choose one installed Local LiteRT embedder. If the list is empty, install one under Settings -> AI Setup -> Models -> Local Models -> Local LiteRT models (Local Models only shows when Experimental features is on)."),
            HelpCard("Query", "Enter up to three lines. The whole query is embedded as one vector and compared with vectors built from each report's title, prompt, and the first non-blank model response."),
            HelpCard("Indexing and cache", "Existing report embeddings are reused when the report content hash, provider key, and model name match. New or edited reports are embedded and stored for the next run."),
            HelpCard("Results", "The top ten positive cosine matches are shown with title, timestamp, score, and the standard Manage/View action icons. Rows are sorted by score descending."),
            HelpCard("Trace", "The screen writes a local trace category named Local semantic search so the ladybug link can show what was indexed and embedded even though no network API was used.")
        )
    ),
    "knowledge_list" to HelpContent(
        title = "Help - Knowledge bases",
        cards = listOf(
            HelpCard("Overview", "List of saved Retrieval-Augmented Generation knowledge bases. Each row shows the name, embedder, source count, and chunk count."),
            HelpCard("New knowledge base", "Creates an empty knowledge base after you choose a name and an embedder. The embedder is fixed for that knowledge base because all chunks must live in the same vector space."),
            HelpCard("Shared content", "When the app receives a shared file or text/link, a pending banner lets you attach that item to an existing or new knowledge base."),
            HelpCard("Rows", "Tap a row to open its sources and chunks. Deleting a knowledge base removes its indexed sources and embeddings from app storage.")
        )
    ),
    "knowledge_new" to HelpContent(
        title = "Help - New knowledge base",
        cards = listOf(
            HelpCard("Overview", "Creates a knowledge base by naming it and choosing the embedder that will index every future source."),
            HelpCard("Embedder choices", "The picker includes installed Local LiteRT embedders and active remote embedding models. Remote embedders require provider setup and bill like normal API calls; local embedders run on device."),
            HelpCard("Fixed after create", "The embedder cannot be changed after creation. Mixing embedders would make similarity scores meaningless, so create a new knowledge base if you want a different embedder."),
            HelpCard("Next step", "After creation, add files or web pages from the detail screen. Indexing turns sources into searchable chunks.")
        )
    ),
    "knowledge_detail" to HelpContent(
        title = "Help - Knowledge base detail",
        cards = listOf(
            HelpCard("Overview", "Manage one knowledge base: add sources, inspect indexed source rows, re-index stale sources, delete sources, or delete the whole knowledge base."),
            HelpCard("Add file", "Imports a supported document from the Android file picker. Text, Markdown, CSV/TSV, PDF, Word/OpenDocument, and spreadsheet-style documents are extracted into chunks before embedding."),
            HelpCard("Add web page", "Fetches a URL, extracts readable text, chunks it, and embeds it with this knowledge base's configured embedder."),
            HelpCard("Source rows", "Rows show status, source title/path, chunk count, and actions. Re-index when source content changed or an earlier ingest failed."),
            HelpCard("Privacy and cost", "Local embedders keep embedding on device. Remote embedders send extracted chunks to the selected embedding provider and can incur cost proportional to source size.")
        )
    ),
    "agent_view" to HelpContent(
        title = "Help - Agent view",
        cards = listOf(
            HelpCard("Overview", "Read-only view of one Agent. It shows the configured provider/model, endpoint/API-key status, and the reusable prompt and parameter choices that make this agent different from a raw model."),
            HelpCard("Navigation", "Tap the model row to open Model Info. Use the edit action from the surrounding setup flow when you need to change the agent."),
            HelpCard("How it runs", "Reports expand an agent into one provider/model call plus the agent's optional endpoint, API key override, system prompt, and parameter presets.")
        )
    ),
    "flock_view" to HelpContent(
        title = "Help - Flock view",
        cards = listOf(
            HelpCard("Overview", "Read-only view of one Flock: a named group of Agents with optional shared system prompt and parameter presets."),
            HelpCard("Members", "Each agent row shows the effective worker and can open the Agent view. Inactive-provider agents can still be present in the saved flock but will not run until the provider is usable again."),
            HelpCard("Run behavior", "When added to a report, a flock expands to all of its agents. Flock-level prompt/parameter selections apply as shared overrides for the group.")
        )
    ),
    "swarm_view" to HelpContent(
        title = "Help - Swarm view",
        cards = listOf(
            HelpCard("Overview", "Read-only view of one Swarm: a named list of raw provider/model members, with optional shared prompt and parameters."),
            HelpCard("Members", "Member rows open Model Info. A swarm does not store per-member API-key overrides; it uses each provider's configured key and endpoint."),
            HelpCard("Difference from a Flock", "A flock references Agents. A swarm references provider/model pairs directly, which is useful for one prompt across many models without creating Agent rows first.")
        )
    ),
    "provider_view" to HelpContent(
        title = "Help - Provider view",
        cards = listOf(
            HelpCard("Overview", "Read-only provider profile with endpoints, capabilities, default settings, and catalog status. It is meant for inspection without entering the full provider editor."),
            HelpCard("Endpoints", "Shows the provider's base URL, admin URL when set, and its list of named built-in endpoints. Endpoints materialized on the fly from a LiteLLM path pick while creating an Agent don't show here — see the Agent's own endpoint field for that."),
            HelpCard("Capabilities", "Rows show provider-level flags: citations, search recency, native rerank, native moderation, whether cost is extracted from the API response, whether pricing comes from the /models list, and whether the provider exposes a cross-provider model catalog."),
            HelpCard("Editing", "Use the manage/edit action when you need to change API keys, state, defaults, endpoints, pricing, or throttle overrides.")
        )
    ),
    "model_info_view" to HelpContent(
        title = "Help - Model Info view",
        cards = listOf(
            HelpCard("Overview", "Deep model profile combining local configuration, provider catalog data, external metadata, pricing, capability flags, usage, workers, and AI-generated introductions."),
            HelpCard("Provider and raw sources", "The provider name in the hero card opens Provider view. The Sources card lists a row per info-provider catalog enabled under AI Setup — HuggingFace, OpenRouter, LiteLLM, models.dev, Helicone, llm-prices, Artificial Analysis, Requesty, llm-stats, genai-prices, TrueFoundry, and CloudPrice. A row is faded and un-tappable when that catalog has no data for this model; otherwise tapping it opens the raw fields as a structured tree."),
            HelpCard("Capabilities and costs", "The Capabilities card lists what the dispatcher believes the model can do — vision, web search, thinking/reasoning, PDF input, deprecation notices, and any default temperature or stop sequences. The Costs card walks the layered pricing lookup and shows every catalog that reports a price for this model, or notes when none does and the built-in default applies."),
            HelpCard("Usage and workers", "Usage cards show call count, token totals, cost, and recent reports that used this model. The Workers card lists every saved Agent, Flock, or Swarm that references this exact provider/model pair, with a tap-through to that entity's read-only view."),
            HelpCard("AI introduction", "When available, the intro is generated from model metadata and cached. It is informational; it does not change dispatch behavior.")
        )
    )
)
