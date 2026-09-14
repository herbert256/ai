# Default prompts

Default prompts are named reusable user prompts, managed under Setup → Prompt management → Default prompts. The CRUD supports create, edit, duplicate and delete. Names and text are required; names are unique ignoring case and surrounding spaces.

Assign one from the 📄 action beside Parameters and System prompt on an Agent, Flock or Swarm editor, then save the worker. The picker shows its current assignment; its delete action clears that assignment and its edit action opens the catalog. Worker detail screens show the assigned name and text.

## Use in reports and chats

A typed report prompt takes priority. Leave it empty to use the selected workers' defaults. The model-selection screen previews each resolved default and cannot advance if any selected model has none. Selecting a Flock uses its default, falling back to each member Agent's default. Selecting an Agent directly uses only that Agent's default. Swarm members use the selected Swarm's default. Bare models have no default.

Each primary run freezes its effective prompt in `ReportAgent.executionConfig`, so retry/regenerate retains the original text after catalog edits or deletion. The report overview stores the resolved question, or the questions grouped by worker when defaults differ. Titles, language detection and secondary operations receive that overview. Existing explicit internal/secondary templates continue to supply their own task prompts. New chats with an Agent prefill its default as an editable, unsent draft; a staged starter text takes priority. Prompt moderation on the New Report page requires an explicit prompt.

## Persistence and compatibility

`DefaultPrompt(id, name, prompt)` lives in `Settings.defaultPrompts`, saved under `ai_default_prompts`. Agent, Flock and Swarm each store a nullable `defaultPromptId`. Fresh installs start with an empty catalog and no assignments; existing workers load with no assignment. No personal prompts or reports are bundled as defaults.

Delete clears references on all three worker types. Configuration import/export includes both the catalog and worker references; Default prompts also has its own import/export row. Backup includes the settings preference automatically. No report-bundle schema change is required.
