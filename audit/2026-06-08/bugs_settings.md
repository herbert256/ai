# Settings Bugs

### Bug 1 - Severity: Medium - Category: Local LiteRT first paint
**Location:** `ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt:50-55`

**Symptom:** Opening the Local LiteRT models screen can block composition
while scanning local model files.

**Root cause:** `LocalEmbedder.availableModels(context)` is called inside
plain `remember`, and that helper lists files and sweeps stale partials.

**Reproduction:** Add many local `.tflite` files or stale `.part` files,
then open Settings -> Local LiteRT models.

**Proposed fix:** Load installed models via `produceState` on
`Dispatchers.IO` and keep stale-partial sweeping in a background cleanup
path.

**Status:** Fixed — 73f6077d (load + refresh LiteRT embedder models off-main (settings bugs 1, 4))

### Bug 2 - Severity: Medium - Category: Local LLM first paint
**Location:** `ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt:179-184`

**Symptom:** Opening the Local LLMs screen can block first paint while
checking installed models and runtime state.

**Root cause:** `LocalLlm.installedTaskFiles(context)` and
`LlmRuntime.isInstalled(context)` run synchronously in `remember`.

**Reproduction:** Install several large `.task` files, then open Settings
-> Local LLMs.

**Proposed fix:** Load installed model/runtime state on `Dispatchers.IO`
and render a loading/empty state until complete.

**Status:** Fixed — c9c69a95 (load + refresh Local LLM list off-main (settings bugs 2, 4))

### Bug 3 - Severity: Medium - Category: Setup hub counts
**Location:** `ai/src/main/java/com/ai/ui/settings/SetupScreens.kt:147-153`, `ai/src/main/java/com/ai/ui/settings/SetupScreens.kt:469-472`

**Symptom:** Settings setup hubs can stutter on open/resume while counting
local models.

**Root cause:** The hubs call `LocalEmbedder.availableModels(context).size`
and `LocalLlm.availableLlms(context).size` inside `remember`.

**Reproduction:** Return to AI Setup or Local Models setup with many local
files installed.

**Proposed fix:** Compute counts in IO-backed state shared with the local
runtime screens.

**Status:** Fixed — 0e3f4772 (count local models off-main in the setup hubs)

### Bug 4 - Severity: Medium - Category: Local runtime refresh work
**Location:** `ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt:63-64`, `ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt:105-106`, `ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt:145-147`, `ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt:195-196`, `ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt:317-319`

**Symptom:** Import/delete completion can briefly freeze the local runtime
screens.

**Root cause:** After background import/download, the UI refreshes
`installed` by calling file-list helpers on the main thread. Delete buttons
also delete files and rescan synchronously.

**Reproduction:** Import or delete a model while many model files are
present.

**Proposed fix:** Perform delete and installed-list refresh on
`Dispatchers.IO`, then publish the result to Compose state.

**Status:** Fixed — 73f6077d (off-main LiteRT + Local LLM list refresh (settings bugs 1, 4 / 2, 4))

### Bug 5 - Severity: Medium - Category: LLM runtime load on main
**Location:** `ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt:233-237`

**Symptom:** Installing the LLM runtime can block the UI after the download
finishes.

**Root cause:** `LlmRuntime.ensureLoaded(context)` is called on the main
thread after `withContext(Dispatchers.IO) { LlmRuntime.download(...) }`.
`ensureLoaded` performs `System.load`.

**Reproduction:** Download the LLM runtime on a device where native library
loading is slow.

**Proposed fix:** Keep `ensureLoaded` on `Dispatchers.IO`, then update
`runtimeInstalled` on the main thread.

**Status:** Fixed — 0bef26a4 (load LLM runtime native lib off the main thread)

### Bug 6 - Severity: Medium - Category: LiteRT import validation
**Location:** `ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt:340-369` (`importTfliteModel`)

**Symptom:** The app can report an imported LiteRT model as installed even
when the file is not a usable MediaPipe text embedder.

**Root cause:** Import checks only that the copied file is non-empty and
renamed successfully. It does not validate MediaPipe Tasks metadata or load
the embedder before adding it to the installed list.

**Reproduction:** Import any non-empty file through "Add model from file".
It appears in the installed list and later fails when selected for local
embedding.

**Proposed fix:** Validate by attempting to create/release a `TextEmbedder`
off-main, or mark unvalidated models separately with a clear error state.

**Status:** Fixed — 58dfa590 (validate an imported .tflite loads as a MediaPipe embedder)

### Bug 7 - Severity: Medium - Category: Local LLM import validation
**Location:** `ai/src/main/java/com/ai/ui/settings/LocalRuntimeScreens.kt:388-465` (`importTaskModel`)

**Symptom:** The app can import an arbitrary non-empty file as a Local LLM
`.task` model and only fail later at generation time.

**Root cause:** Unknown file extensions fall through to copying as `.task`,
and success requires only that the staged file is non-empty.

**Reproduction:** Import a PDF or random binary through "Add LLM from
file". It can appear as an installed LLM.

**Proposed fix:** Restrict accepted extensions, validate archive entries,
and optionally attempt a metadata/header validation before publishing the
model to the installed list.

**Status:** Fixed — 58dfa590 (reject unknown file types instead of copying as .task)

### Bug 8 - Severity: High - Category: Import UI blocking
**Location:** `ai/src/main/java/com/ai/ui/settings/ImportExportScreen.kt:1246-1249`

**Symptom:** Importing API keys can block the UI thread on large or slow
content URIs.

**Root cause:** The `keys` import branch calls `readFromUri(uri)` directly
from the Activity Result callback, unlike the JSON-object helper that reads
on `Dispatchers.IO`.

**Reproduction:** Pick a large or cloud-backed API-keys JSON file. The
callback reads the entire stream synchronously before parsing.

**Proposed fix:** Route the keys import through `launchJsonObjectImport` or
wrap `readFromUri` in `withContext(Dispatchers.IO)`.

**Status:** Fixed — 7154198a (read API-keys import off the main thread)

### Bug 9 - Severity: Medium - Category: App log route filename trust
**Location:** `ai/src/main/java/com/ai/ui/admin/AppLogScreen.kt:438-466`, `ai/src/main/java/com/ai/ui/admin/AppLogScreen.kt:645-654`, `ai/src/main/java/com/ai/ui/navigation/NavRoutes.kt:181-183`

**Symptom:** App Log detail can read/delete a filename supplied by route
argument without first checking it is one of the listed log files.

**Root cause:** `AppLogDetailScreen` stores the route `filename` directly
as `currentFilename` and passes it to `AppLog.readLogFile`/`deleteLog`.
`NavRoutes.aiAppLogDetail` also does not encode the filename path segment.

**Reproduction:** Navigate manually to an app-log detail route with a
crafted filename segment.

**Proposed fix:** Encode filename route segments, validate against
`AppLog.getLogFiles()` before read/delete, and harden the data helpers.

**Status:** Fixed — 58dfa590 (encode the app-log-detail route filename segment)

### Bug 10 - Severity: Medium - Category: Prompt translations main-thread IO
**Location:** `ai/src/main/java/com/ai/ui/admin/PromptTranslationsScreen.kt:54-58`, `ai/src/main/java/com/ai/ui/admin/PromptTranslationsScreen.kt:230-239`

**Symptom:** Prompt translation management can block the UI when many
generated prompt files exist.

**Root cause:** `InternalPromptSeed.listLanguages` and
`PromptTranslationStore.storedLanguages` run inside `remember` on the main
thread, and delete confirmation calls `PromptTranslationStore.deleteLanguage`
directly from the button handler.

**Reproduction:** Generate translations for several languages, open Prompt
translations, then delete a language with many prompt files.

**Proposed fix:** Load language lists and perform delete/count operations on
`Dispatchers.IO`, then update Compose state from the result.

**Status:** Fixed — c075e3f9 (load + delete prompt translations off-main)

