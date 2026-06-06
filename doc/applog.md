# AppLog — in-app file logger

`com.ai.data.AppLog` is a log4j-style file appender that
mirrors `android.util.Log`. Every call lands in both logcat
and a daily-rotating plain-text file under
`<filesDir>/applog/applog_<yyyyMMdd>.log` (when the call's
level is at or above the user-configured threshold).

Designed so a user can hand the app a clean, durable log when
something misbehaves — independent of `adb logcat`, shareable from
inside the app. WARN / ERROR calls additionally flash a short
on-screen toast so a failure is noticed without opening the viewer.

> This page covers *how the logger works*. For the catalogue of
> *every* call site that writes to the log — grouped by severity —
> see **[log-details.md](log-details.md)**.

## Levels

```kotlin
enum class LogLevel(val priority: Int) {
    DEBUG(3),    // matches Log.DEBUG (also the home of former TRACE calls)
    INFO(4),     // matches Log.INFO
    WARN(5),     // matches Log.WARN
    ERROR(6),    // matches Log.ERROR
    OFF(99)      // sentinel — disables the file appender (logcat still fires)
}
```

There is no separate TRACE level — the old `AppLog.v` forwarder
was folded into `AppLog.d`, so DEBUG is the lowest file-appender
level. Priorities align with `android.util.Log` so each forwarder
(`AppLog.d/i/w/e`) is a one-line dispatch: log to logcat
unconditionally, then append to the file only when
`threshold.priority <= level.priority`. Threshold defaults to
**WARN** — a fresh install persists only warnings and errors;
lower it to INFO/DEBUG to capture every API call + batch
start/end while diagnosing an issue. `OFF` silences the file
appender entirely (logcat is unaffected).

The threshold is persisted in main prefs (`eval_prefs`, key
`log_level`). `AppLog.init` reads it directly from
`SharedPreferences` **before** `AppViewModel`'s bootstrap so that
DEBUG calls inside bootstrap itself are admitted on cold
start — `AppLog` keeps no `SettingsPreferences` dependency so it
can apply the threshold before any higher-level singletons exist.
The `GeneralSettings.logLevel` field mirrors it for the rest of
the runtime; an update via **Settings → Logging and tracing →
Application log level** re-mirrors to `AppLog.threshold`.

## File format

One line per call:

```
2026-05-11 09:51:09.732 INFO  App: App started — AI v1.42 (built 2026-05-11T07:53:00Z, installed …) logLevel=INFO, tracing=true
```

Format: `yyyy-MM-dd HH:mm:ss.SSS LEVEL TAG: message`. A stack
trace, when one is attached, is indented by four spaces on
subsequent lines (the viewer folds those continuation lines back
into the entry they followed).

Files rotate daily — `appendLine` compares today's `yyyyMMdd`
against `writerDate` on every append, and reopens the
`BufferedWriter` (in append mode) on a new file when they differ.
A single writer is held open across calls and **flushed per line**
so a process kill never loses the last few lines (slightly more
I/O than batched, but a durable log is the whole point).

## Bootstrap log line

On every app start, `AppViewModel`'s startup path writes one
structured INFO line (tag `App`) capturing the app label,
`BuildConfig.VERSION_NAME`, the build timestamp, the install time
(`PackageInfo.lastUpdateTime`), and the resolved log level +
tracing flag. Makes it trivial to tell, in a multi-day log file,
exactly when the app last (re)started. The detailed per-step
bootstrap trace lines use the `App.start` tag.

## No per-report tagging

The application log does **not** tag lines by report id. Per-report
activity lives in the per-report **audit log** (`AuditLog`,
`<filesDir>/audit/<reportId>.log`), which is the canonical home for
batch start/end, mutating actions and per-call records — the
application log keeps app-wide diagnostics only. The report screen's
**View → Log** deep-link still opens the application-log file for the
day the report was created, but no longer pre-seeds a per-report
search filter (`reportLogContext` survives only as the report-section
coroutine context — `Dispatchers.IO` + the crash handler).

## Sensitive-value redaction

`AppLog.redactSecret` strips three common secret shapes inline
before each line is written (and before a toast is shown):

- `Bearer <token>` / `Basic <auth>` → `Bearer [REDACTED]`
  (regex `(?i)(Bearer|Basic)\s+[A-Za-z0-9._\-+/=]+`).
- Raw API keys — a `sk-` / `xai-` / `gsk_` / `key-` prefix
  followed by ≥16 key-ish chars → `<prefix>[REDACTED]`.
- Google `key=<token>` query params (≥16 chars) → `key=[REDACTED]`.

These are the same shapes `TracingInterceptor` guards against, so
call sites that already redact themselves pass through unchanged.

## WARN / ERROR toasts

`AppLog.w` and `AppLog.e` (when admitted by the threshold) also
post a short `Toast` on the main thread — `LEVEL TAG: <redacted
message>` truncated to 140 chars — so the user notices a problem
without opening the viewer. A burst (e.g. fan-out icon retries
spraying dozens of warnings in a second) is coalesced via
`TOAST_MIN_INTERVAL_MS = 1500ms` so the screen isn't flooded with
un-dismissable toasts. The toast needs the application `Context`
captured in `init`; before `init` it is silently skipped (the
file + logcat lines still fire).

## In-memory file-list cache

`AppLog.cachedFiles` mirrors `<filesDir>/applog/`'s listing
(sorted newest-first) so the viewer's file-list screen doesn't
restat on every nav. It is invalidated on `appendLine`
(set to null — the next listing does an O(N) restat) and surgically
pruned on `deleteLog` / `deleteLogsOlderThan`, and reset on
`clearLogs`. Same contract as `ApiTracer.cachedTraceFiles`.

## Writer-health surfaces

`appendLine` buries every exception — logging failures must never
throw into caller code — but records what failed so the viewer can
tell "logging is broken" apart from "nothing was logged yet":

- `AppLog.lastWriterError` — the catch block's message (disk full,
  file-handle exhaustion, …), or null when healthy.
- `AppLog.droppedLineCount` — increments on every miss; both reset
  to 0 on the next successful flush.

The list screen's empty-state branch reads both: a red **"Log
writer failed"** banner (message + dropped-line count) when
`lastWriterError != null`, otherwise a neutral *"(no log files
yet)"* hint that also surfaces the current threshold so a
WARN/ERROR threshold on a quiet app isn't mistaken for a broken
logger.

## Coverage

The data + viewmodel layers carry broad DEBUG / INFO
coverage. The canonical tag set is the literal tag *strings*
passed to `AppLog.v/d/i/w/e` (not class names); there are roughly
**77 distinct tags**. Grouped:

- **Lifecycle / infra** — `App` (startup line), `App.start`
  (per-step bootstrap), `Crash`, `Housekeeping`, `CapsWatch`,
  `Throttle`, `RateLimit`, `Overloaded`, `TagPropagation`,
  `AtomicFileWrite`, `Settings`.
- **API / dispatch** — `AiAnalysis`, `ApiClient`, `ApiDispatch`,
  `ApiTracer`, `SSE`.
- **Reports / regenerate** — `Report`, `ReportStorage`,
  `RegenBatch`, `RegenerateBatchStorage`, `Resume`,
  `SecondaryResume`, `BgResumeSweep`.
- **Secondary results** — `Secondary`, `SecondaryResultStorage`,
  `Meta`, `Meta-xlate`, `MetaCache`, `FanOut`, `FanIn`, `FanMeta`,
  `Rerank`, `Moderation`, `Tournament`, `JudgeEval`, `Compare`.
- **Translation** — `Translation`, `TranslationIcon`,
  `TranslationIconAlt`, `Translate-missing`,
  `PromptTranslationStore`.
- **Icons (alt / find-alternative)** — `AgentIconAlt`,
  `InternalPromptIcon`, `InternalPromptIconAlt`, `LanguageIconAlt`,
  `PairIconAlt`, `PairTitleAlt`.
- **Knowledge / RAG / embeddings** — `Knowledge`, `Chat.RAG`,
  `EmbeddingsStore`.
- **On-device runtime** — `LocalLlm`, `LocalEmbedder`,
  `LlmRuntime`, `LocalRuntime`.
- **Chat / import-export** — `Chat`, `ChatHistory`,
  `ImportExport`, `BulkExport`, `ReportExport`, `Backup`.
- **Catalogs / providers** — `ModelListCache`, `PricingCache`,
  `RefreshAll`, `RecentModels`, `ProviderRegistry`,
  `ProviderFieldTimestamps`.
- **Model test / stress** — `ModelTest`, `ModelTestRunStore`,
  `StressTest`, `Workers`.
- **Seed loaders** (first-run asset seeding) — `DefaultMetaItemSeed`,
  `ExamplePromptSeed`, `FlockSeed`, `InaccessibleSeed`,
  `SwarmSeed`, `SystemPromptSeed`, `TestExcludedSeed`.

## Viewer screens

Reached from **Settings → Logging and tracing → Application log**,
and from the **Monitor / AI Dashboard** hub (the *Application log*
card; a sibling *App log statistics* aggregate page is also
reachable from the 📈 icon on the list screen). All three screens
below live in `ui/admin/AppLogScreen.kt`.

### `AppLogListScreen` — file list

Title "Application log" (help topic `applog_list`). One row per
log file: **Date** (extracted from the `applog_yyyyMMdd.log`
shape) and on-disk **Size**, sorted newest-first. Title-bar
actions: 📈 stats (jumps to *App log statistics*), 📤 share (opens
a day-picker; tapping a day stages that file's bytes into
`cacheDir/exports` and fires the system share sheet as a real
`.log` attachment), and 🗑 clear-all (deletes every log file after
confirmation). A **Delete > 7 days** button at the bottom calls
`deleteLogsOlderThan(now − 7d)`.

### `AppLogDetailScreen` — per-file viewer

Title "Log file" (help topic `applog_detail`), subject = the
filename. Title-bar action strip: `< Back`, 📋 Copy, 📤 Share, 🗑
Delete (this file), and 🧽 Clear-filters (shown only while a filter
is active). Entries render reverse-chronological (newest at the
top); stack-trace continuation lines stay glued to their header.

Filters (top of screen):

- **Search query** — free-text substring match across the whole
  entry (header + continuation lines), case-insensitive, with a ✕
  to clear. Seeded from `initialSearch` (empty for the report
  deep-link, which now just opens the day's file) and **not** reset
  on file-step navigation, so a run that spilled into the next day's
  file can be followed by flipping files with the filter held.
- **Level chips** — multi-select FilterChips for DEBUG / INFO /
  WARN / ERROR. **All four enabled by default.** Headers
  with no recognised level token (legacy / pre-AppLog lines) are
  always kept visible.
- **Time range** — Start / End buttons that open Material 3 clock
  pickers (value shown as `HH:mm`, each with a Clear button to
  drop the bound). Empty = no constraint.
- **Tag dropdown** — populated from the file's distinct tag set,
  sorted alphabetically. The `(any)` sentinel matches everything.

All active filters are AND-ed. A *"Showing X of Y"* count line
sits above the list. **File-step navigation is a horizontal
swipe** on the content area (left = next day, right = previous
day); a centred `N / total` counter shows the position. Tapping a
row opens the per-entry overlay (the established
overlay-and-`return` idiom, so the parent's scroll survives).

### `AppLogEntryScreen` — per-entry detail

Title "Log entry" (help topic `applog_detail`, subject = the
filename). Three-line body header (time-of-day / `LEVEL  TAG`,
level-coloured + bold / the message), followed by any indented
stack-trace continuation lines. Title-bar actions: 📋 Copy
(whole entry), 📤 Share, and 🐞 **Trace** — which appears only when
the entry's timestamp falls within **30 s** of an `ApiTracer`
trace file (the nearest such trace wins); tapping it navigates to
that trace. Walk to the **previous / next** entry in the current
filtered set by tapping the left / right half of the body (no-op
at the ends); a counter at the bottom reads
`pos / total (tap left ← prev, tap right → next)`.

### Copy / Share dialog

Tapping 📋 Copy or 📤 Share on the file viewer opens one shared
dialog with three mutually-aware options:

- **Filtered only** — emits exactly the entries currently visible
  under the active search / level / tag / time filters (label
  shows the count).
- **Complete log** — the whole file, ignoring filters.
- **Last N lines** — a digit field (the default path); disabled
  while either checkbox above is ticked.

"Filtered only" and "Complete log" are exclusive checkboxes
(ticking one unticks the other). Copy lands in the clipboard;
Share marshals to the system chooser. Both reuse the same
serialiser as the in-app viewer, so a shared log matches what the
user just looked at.

## Trimming

`AppLog.deleteLogsOlderThan(cutoffMs)` is wired to the list
screen's **Delete > 7 days** button (and is also available to the
report / chat / trace age-trimmers). `clearLogs()` drops every
file: it's the list screen's 🗑 clear-all action and is also called
last in `AppViewModel`'s runtime-wipe / reset path (last, because
that method's own prior log lines go with it).

## Files

- `data/AppLog.kt` — the `object` singleton, `LogLevel` enum,
  `AppLogFileInfo` type, redaction regexes, toast debounce, and
  the `currentLogId` ThreadLocal.
- `ui/admin/AppLogScreen.kt` — list + file viewer + per-entry
  screens, plus the log-entry parser and time-filter helpers.
- `ui/admin/AiDashboardScreen.kt` — the *Application log* hub card
  and the *App log statistics* aggregate page (`ai_log_stats`).
- `ui/admin/DeveloperHelp.kt` — `applog_list` / `applog_detail`
  help topics.
- `ui/settings/SettingsScreen.kt` — the `Logging and tracing` card
  (threshold dropdown).
- `viewmodel/AppViewModelTypes.kt` — `GeneralSettings.logLevel`
  (mirrored to `AppLog.threshold` on every settings save) and
  `loggingMasterEnabled` (the master gate, default on).
