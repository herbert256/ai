# Screens

Every screen title used in the app and the subtitle line shown beneath it
(the orange `TitleBar` subject, or — for the report **View** family — the
report line of `ViewTitleBar`, which is the report's `barTitle`). `<…>` marks
text filled in at runtime; a blank subtitle cell means the screen shows no
second line. Where the same title appears with more than one subtitle, each is
listed. Sorted by title, then subtitle.

Two distinct view-style title bars feed this table:
`ViewTitleBar` (`ui/report/view/helpers/`) drives the report **View** family
(Reports / Icons / Costs / **Answer matrix** / Fan-out / …) and renders the
report's `barTitle` as the report line; `ViewScreenTitleBar` (`ui/shared/`)
drives the read-only entity views (Agent / Flock / Swarm / Provider). Plain
screens use the generic `TitleBar`.

| Screen title | Subtitle line |
|---|---|
| About | Version, build date and credits |
| Add Agent | \<agent name\> |
| Add blocked model | Block one model from being called |
| Add cooldown | Pause a model until a given time |
| Add default meta item | Auto-run a meta prompt on report completion |
| Add example prompt | \<prompt title\> |
| Add Flock | \<flock name\> |
| Add inaccessible model | Mark one model as unreachable |
| Add override | Assign one model's API type |
| Add Override | Set input/output $/M for one model |
| Add override | Set one model's API type by hand |
| Add Parameters | \<preset name\> |
| Add Swarm | \<swarm name\> |
| Add System Prompt | \<prompt name\> |
| Add test-excluded model | Exclude one model from Test all |
| Add \<prompt type\> | \<name\> |
| Agent | This agent's model, prompt & settings |
| Agent | \<agent name\> |
| Agents | Saved model + prompt + params combos |
| All reports | Browse every saved report, newest first |
| Alternative icons | Live icon ideas from several models |
| Alternative titles | Live title ideas from several models |
| Alternative translations | Live translations from several models |
| Answer matrix | \<report title\> |
| API Test | Hand-craft a raw API call to a model |
| API trace statistics | What hit the network |
| API Traces | Every captured API request & response |
| API Traces | \<active trace filter\> |
| App log statistics | The in-app log |
| App settings | App-wide & report-model default prompt / parameters |
| Application log | Daily app logs for diagnosing issues |
| assets/*.json | Restore providers/prompts from defaults |
| Audit | Per-report audit trail |
| Audit | \<report title\> |
| Backup & Restore | Back up or restore the whole app to a zip |
| Blocked model | \<provider\> · \<model\> |
| Blocked models | Models the app will never call |
| Broken work | Batch work that needs attention |
| Caches | Browse and manage every on-disk cache |
| Chat | Start or resume a chat with a model |
| Chat | \<running cost\> |
| Chat History | Resume any of your saved chat sessions |
| Chat Parameters | Set model & options before chatting |
| Clear all configuration | Wipe all config; keeps reports & chats |
| Clear Info providers | Drop cached pricing; refetch on Refresh |
| Clear runtime data | Drop history; keeps config & API keys |
| Configure API parameters | |
| Configure API parameters | Current active: \<preset names\> |
| Continue in chat | Send this answer into a new chat |
| Compare with meta | Pick a meta result to score answers against |
| Compare with meta | \<report title\> |
| Compare with meta - model | \<report title\> |
| Cooldown | \<provider\> / \<model\> |
| Costs | Correct model prices used in cost totals |
| Costs | Where the money went, per call |
| Costs | \<report title\> |
| Costs tiers | Pricing tier per model + catalog freshness |
| Crash report | \<crash file\> |
| Crash reports | Captured errors — tap to view & share |
| Default icons | Fallback + bottom-bar action emoji |
| Default meta item | \<meta name\> |
| Default meta items | Meta prompts auto-run when a report finishes |
| Define model system prompt | |
| Define model system prompt | Current active: \<prompt name\> |
| Dual Chat | Set up two models to debate a topic |
| Dual Chat | Two models taking turns automatically |
| Edit Agent | \<agent name\> |
| Edit blocked model | Block one model from being called |
| Edit cooldown | Pause a model until a given time |
| Edit dashboard | Pin, reorder and preview cards |
| Edit default meta item | Auto-run a meta prompt on report completion |
| Edit example prompt | \<prompt title\> |
| Edit Flock | \<flock name\> |
| Edit icons | Every icon in this report |
| Edit inaccessible model | Mark one model as unreachable |
| Edit model title | Rename one model's answer title |
| Edit override | Assign one model's API type |
| Edit Override | Set input/output $/M for one model |
| Edit override | Set one model's API type by hand |
| Edit Parameters | \<preset name\> |
| Edit prompt | Saving needs a regenerate to apply |
| Edit report | \<report short title\> |
| Edit report | Icon, titles, parameters, prompt |
| Edit Request | Edit the raw JSON request body |
| Edit Swarm | \<swarm name\> |
| Edit System Prompt | \<prompt name\> |
| Edit test-excluded model | Exclude one model from Test all |
| Edit title | Metadata only — no regenerate needed |
| Edit title | Rename one fan-out response title |
| Edit titles | Every dynamic title in this report |
| Edit \<prompt type\> | \<name\> |
| Embeddings | Cached RAG / semantic-search vectors |
| Errored models | \<report title\> · \<batch name\> |
| Errors | \<report title\> · \<batch name\> |
| Example prompt | \<prompt title\> |
| Example prompts | Starter prompts for new reports |
| Examples | Open ready-made example reports |
| Export | Pick a format and save or share it |
| Export / Import | Move your data in and out via files |
| Extended local search | Tokenised search over saved reports |
| External request | Another app wants to make a report |
| External Services | Keys for search and other extras |
| Fan In | \<report title\> |
| Fan Meta | Loading the fan-out… |
| Fan Meta | \<fan-out run\> |
| Fan Meta - All | \<fan-out run\> |
| Fan Meta - meta model | \<meta model\> |
| Fan Meta - model | \<provider\> / \<model\> |
| Fan Meta - pair | This pair no longer exists |
| Fan Meta - pair | \<answerer label\> |
| Fan Meta workers | \<fan-out run\> |
| Fan out | Loading the fan-out… |
| Fan out | \<fan-out run\> |
| Fan out - model | \<provider\> / \<model\> |
| Fan out - one page | \<provider\> / \<model\> (one page) |
| Fan out - pair | This pair no longer exists |
| Fan out - pair | \<answerer label\> |
| Fan out statistics | \<fan-out run\> |
| Fan Out - run | Confirm the calls before fanning out |
| Fan Out - scope | \<secondary kind\> |
| Fan out/in prompts | Prompts for multi-model fan out/in |
| Fan-out | \<report title\> |
| Fan-out pair | \<report title\> |
| Flock | \<flock name\> |
| Flocks | Named groups of agents |
| Find alternative translation | \<target language\> - \<type\> |
| Find icon | \<icon target\> |
| First launch | Start with setup essentials |
| Help | \<per-topic subject\> |
| Head-to-heads | \<model\> |
| History | All your saved reports, newest first |
| Housekeeping | Backup, cleanup and maintenance tools |
| HTML preview | \<report title\> |
| HTML preview (short) | \<report title\> |
| Icon lookup | \<icon target\> |
| Icons | \<report title\> |
| Import | Bring data in from a file |
| Inaccessible model | \<provider\> · \<model\> |
| Inaccessible models | Models that returned unreachable |
| Info provider | \<provider name\> |
| Info Providers | Six pricing & capability catalogs |
| Internal prompts | Prompts the app's own flows use |
| Internal-prompt icons | Per-(name, title) emoji for internal-prompt rows |
| Interrupted models | \<report title\> · \<batch name\> |
| Judge | \<report title\> |
| Judge the judges | \<report title\> |
| Match | \<report title\> |
| Live Dashboard | What's happening right now |
| Loading… | Loading settings… |
| Local LiteRT models | On-device embedding models |
| Local LLMs | On-device chat models, run offline |
| Local models | On-device LLMs and embedders |
| Local semantic search | On-device meaning search, no cloud |
| Log entry | \<filename\> |
| Log file | \<filename\> |
| Logging and tracing | Log level and API call tracing |
| Manage a report | |
| Manage chats | Bulk-delete old chats or export them |
| Manage reports | Delete old reports or export them all |
| Manual cost override | \<provider\> · \<model\> |
| Manual cost overrides | Prices you set by hand, beat the catalog |
| Manual model types | Model types you set by hand |
| Manual override | \<provider\> / \<model\> |
| Maximal API calls | How many calls run at once |
| Meta | Run a meta prompt over the answers |
| Meta | Run a prompt over the report's answers |
| Meta (titles / lang-icon) | Cached report titles + language icons (7 d) |
| Meta detail | \<secondary title\> |
| Metadata & icons | Master switch and per-item options for optional report metadata |
| Model cooldowns | Models paused after rate-limit errors |
| Model Info | \<model name\> |
| Model Info | \<report title\> |
| Model lists | Cached /models response per provider |
| Model reports | \<report title\> |
| Model response | Conclusion, motivation and full reply |
| Model response | Read each model's full answer |
| Model response | \<agent label\> |
| Model Types | Default API path per model type |
| Models | Fetch, test and edit a provider's models |
| Models | The whole catalog |
| Models | \<provider id\> |
| Models setup | Models, types and manual overrides |
| Moderation | \<report title\> |
| Moderation result | \<agent label\> |
| Monitor | Live and historical observability |
| Network settings | Timeouts, throttling and retry rules |
| New report | Blank, a past prompt, or an example |
| New Report | Write your prompt, then pick models |
| One page view | \<model label\> |
| One-time prompt | Run a prompt without saving it |
| Other settings | Identity |
| Pick a flock | Add a whole group of agents at once |
| Pick a swarm | Add a multi-agent team to the report |
| Pick an agent | Add a saved agent to the report |
| Pick an example prompt | Start a report from a sample prompt |
| Pick inaccessible model | Add one model, with live pricing |
| Pick model | Filter the trace list to one model |
| Pick model for chat | Add one model, with live pricing |
| Pick model for swarm | Add one model, with live pricing |
| Pick model to block | Add one model, with live pricing |
| Pick model to exclude from Test all models | Add one model, with live pricing |
| Pick moderation model | Add one model, with live pricing |
| Pick previous report | Reuse a past report's model selection |
| Pick provider / model | Add one model, with live pricing |
| Pick rerank model | Add one model, with live pricing |
| Pick target language | Choose a language to translate into |
| Pricing tiers | Per-source model pricing catalogs |
| Prompt | The prompt sent to the models |
| Prompt | \<report title\> |
| Prompt History | Reuse a prompt you sent before |
| Prompt management | System, internal and example prompts |
| Prompt translations | Generate and manage internal-prompt translations |
| Prompts | Cached internal-prompt responses (48 h) |
| Provider | \<provider id\> |
| Providers | 42 built-in plus your own providers |
| Providers / Models | The whole model fleet |
| Quick local search | Fast substring match, no scoring |
| Rank the translators | \<report title\> |
| Rank workers | \<report title\> |
| Ranking weights | Weight each ranking 0–10 |
| Refine answer | \<answer label\> |
| Refresh | Update model catalogs and workers |
| Regenerate report | Re-run every model on this report |
| Report - API | Raw API request and response |
| Report - Get info | Status of icon, title & language jobs |
| Report - second results | Status of every secondary result |
| Report - select models | Add agents, flocks, swarms or models |
| Report information | Everything we know about this report |
| Report information | \<report title\> |
| Reports | Create, browse and search your reports |
| Reports | Reports and secondary results |
| Rerank | \<report title\> |
| Reset | Five ways to clear data, safe to drastic |
| Reset application | Factory reset; only API keys are kept |
| Restore | Restore the whole app from a backup zip |
| Run a Meta prompt | Choose a prompt for this action |
| Run \<meta name\> | Tweak the prompt for this run only |
| Scope | \<secondary kind\> |
| Search Chats | Full-text search across saved chats |
| Search reports | Find a report by keyword or meaning |
| Secondary detail | \<secondary title\> |
| Secondary results | \<secondary title\> |
| Select Agent | Pick one of your saved agents |
| Select Endpoint | \<provider id\> |
| Select Model | Pick a model, with pricing & flags |
| Select Model | \<provider id\> |
| Select Provider | Pick a cloud provider |
| Semantic search | Cloud embedding search by meaning |
| Settings | App preferences, grouped by topic |
| Share | Turn shared content into a report/chat |
| Setup | Providers, models, workers & prompts |
| Source | \<model name\> |
| Spend & usage | Calls, tokens and cost by provider, type, report and model |
| Statistics | Lifetime aggregates across the app |
| Stress test | Report every example prompt with swarm "Level 2" |
| Supported params | OpenRouter per-model supported parameters |
| Swarm | \<swarm name\> |
| Swarms | Multi-step agent pipelines |
| System prompt | \<prompt name\> |
| System prompts | Reusable system instructions |
| Test | Diagnostic test flows for models |
| Test all models | Choose which providers to test |
| Test all models | Per-provider pass rate, tap to drill in |
| Test all models - model | Pass/fail, latency and the model's reply |
| Test all models - model | \<model name\> |
| Test all models - provider | \<provider id\> |
| Test-excluded model | \<provider\> · \<model\> |
| Test-excluded models | Models skipped by Test all models |
| Trace categories | Every category, by trace count |
| Trace detail | Full request & response of one call |
| Trace hosts | Every host, by trace count |
| Trace models | Every model, by trace count |
| Translate | \<report title\> |
| Translation | Per-model progress of this translation |
| Translation | \<target language\> |
| Translation - model | \<model label\> |
| Translation - type | \<type label\> |
| Translation call | Source text and its translation |
| Translation call | \<target language\> |
| Translation compare | \<translation title\> |
| Translation workers | \<target language\> |
| Translations | \<report title\> |
| Translator | \<report title\> |
| Trim by age | Delete reports, chats & traces by age |
| Tournament | Head-to-head tools |
| Tournament | \<report title\> |
| Tournament | \<ranking method\> ranking |
| Tournament - judge | \<report title\> |
| Tournament - Match | \<report title\> |
| Tournament - model | \<report title\> |
| Tournament workers | \<report title\> |
| UI tweaks | Visual and layout preferences |
| UI Colors | App palette |
| Unfinished | \<report title\> · \<batch name\> |
| Update from cloud | Install the latest APK from a synced file |
| User notes | Every note in this report |
| Value view | \<report title\> |
| View a report | \<report title\> |
| View in one page | \<section title\> |
| View Reports | Read each model's full answer |
| Workers | Models, agents, flocks and swarms |
| \<catalog name\> | What the refresh updated |
| \<provider id\> | Usage detail |
| \<refresh scope\> | Updating catalogs and workers… |

## Notes on two subtitles that are stale in the app itself

This table transcribes the strings exactly as they appear in the running app,
including two that no longer describe what their screen does. They are recorded
verbatim here for fidelity; the underlying source strings are the things that
need fixing, not this doc:

- **Info Providers — "Six pricing & capability catalogs"**
  (`RefreshScreen.kt:460`). There are now **seven** external metadata
  repositories and eight price sources (see `repositories.md` /
  `costs.md`); the catalog count is no longer six. The help page lists the
  same stale six.
- **Swarms — "Multi-step agent pipelines"**
  (`cruds/workers/swarms/list.kt:42`). A swarm is a **flat group of
  (provider, model) pairs**, not a pipeline — the Models-setup NavCard
  already says the accurate "Groups of provider/model pairs"
  (`SetupScreens.kt:219`). See `workers.md`.

The **Providers** subtitle "42 built-in plus your own providers"
(`SetupScreens.kt:561`) is accurate: the bundled catalog has 49 cloud
providers (loaded at runtime from `assets/providers.json`, not hardcoded),
plus any custom providers you add.

The **Answer matrix** screen (new, `ui/report/view/AnswerMatrix.kt`, reached
from the **Matrix** tile on the View grid, between Reports and Costs) is a
report-View-family screen. Its orange report line is the report's `barTitle`
(recorded as `<report title>` above); it also carries a secondary
`subject = "ranked by <rerank model>"` line when a rerank result exists. It
reuses the `view_ai_report` help topic rather than defining its own.
