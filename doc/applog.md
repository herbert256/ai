# AppLog — in-app file logger

`com.ai.data.AppLog` is a log4j-style file appender that
mirrors `android.util.Log`. Every call lands in both logcat
and a daily-rotating plain-text file under
`<filesDir>/applog/applog_<yyyyMMdd>.log` (when the call's
level is at or above the user-configured threshold).

Designed so a user can hand the app a clean, durable log when
something misbehaves — independent of `adb logcat`, shareable from
inside the app.

## Levels

```kotlin
enum class LogLevel(val priority: Int) {
    TRACE(2),    // = Log.VERBOSE
    DEBUG(3),    // = Log.DEBUG
    INFO(4),     // = Log.INFO
    WARN(5),     // = Log.WARN
    ERROR(6),    // = Log.ERROR
    OFF(99)      // disables the file appender (logcat still fires)
}
```

Priorities align with `android.util.Log` so the forwarder is a
one-line dispatch. Threshold defaults to **INFO** — noisy enough
to capture every API call + batch start/end without flooding
the device with per-token streaming chatter.

The threshold is persisted in main prefs (`log_level`).
`AppLog.init` reads it directly from `SharedPreferences`
("eval_prefs") **before** `AppViewModel`'s bootstrap so that
DEBUG / TRACE calls inside bootstrap itself are admitted on
cold start. The `GeneralSettings.logLevel` field mirrors it for
the rest of the runtime; an update via Settings → Logging
re-mirrors to `AppLog.threshold`.

## File format

One line per call:

```
2026-05-11 09:51:09.732 INFO  AppLifecycle: App start — com.ai 1.42 build 2026-05-11T07:53:00Z
```

Format: `yyyy-MM-dd HH:mm:ss.SSS LEVEL TAG: message`. A stack
trace, when one is attached, is indented by four spaces on
subsequent lines.

Files rotate daily — the writer compares today's `yyyyMMdd`
against `writerDate` on every append, and reopens the
`BufferedWriter` on a new file when they differ. The buffer is
**flushed per line** so a process kill never loses the last few
lines (slightly more I/O than batched, but a durable log is the
whole point).

## Bootstrap log line

On every app start, `AppViewModel.bootstrap` writes one
structured INFO line capturing the app name, versionName,
versionCode, and the BUILD_TIMESTAMP (set by the AGP build
script). Makes it trivial to tell, in a multi-day log file,
exactly when the app last (re)started.

## Sensitive-value redaction

`AppLog.redactSecret` strips three common shapes before write:

- `Bearer <token>` / `Basic <auth>` → `Bearer [REDACTED]`
- Raw API keys (`sk-`, `xai-`, `gsk_`, `key-` followed by ≥16
  base64-ish chars) → `<prefix>[REDACTED]`
- Google `?key=<token>` query params → `key=[REDACTED]`

Same shapes `TracingInterceptor.headersToMap` catches — call
sites that already redact (e.g. dispatch's own header logger)
pass through unchanged.

## In-memory file-list cache

`AppLog.cachedFiles` mirrors `<filesDir>/applog/`'s listing so
the viewer's file-list screen doesn't restat on every nav.
Invalidated on `appendLine`, `deleteLog`, `deleteLogsOlderThan`,
and `clearLogs`. Same contract as
`ApiTracer.cachedTraceFiles`.

## Writer-health surfaces

When `appendLine`'s catch block fires (disk full, file-handle
exhaustion, …), the failure is recorded:

- `AppLog.lastWriterError` — the catch block's message.
- `AppLog.droppedLineCount` — increments on every miss; resets
  to 0 on the next successful flush.

The viewer's empty-state branch reads both so a user can tell
"logging is broken" apart from "nothing was logged yet" —
these used to be indistinguishable.

## Coverage

The data + viewmodel layers carry broad TRACE / DEBUG coverage.
Tagged sources (`grep` against `AppLog.d/v/i/w/e` shows the
canonical set):

- `AppLifecycle`, `AppViewModel`, `AiAnalysis`, `ApiDispatch`,
  `ApiStreaming`, `ApiTracer`, `AtomicFileWrite`
- `BackupManager`, `ChatHistoryManager`, `ChatViewModel`
- `ImportExport`
- `ModelListCache`, `PricingCache`, `ProviderRegistry`,
  `ProviderFieldTimestamps`
- `ReportExport`, `ReportStorage`, `ReportViewModel`
- `SecondaryResultStorage`, `SettingsExport`
- `Throttle` — `ProviderThrottle`'s rate-limit / concurrent-cap
  wait logs (each timeout reports the queue depth or
  available-permit drain)

## Viewer screens

Reachable from Hub → AI App log.

### `AppLogScreen` — file list

One row per log file. Date (extracted from the filename
`applog_yyyyMMdd.log` shape), size, line count. Sorted newest
first.

### `AppLogScreen` — per-file viewer

Title bar action strip: `< Back`, 🐞 Trace (when the entry's
TraceFile points at an existing API trace), 📋 Copy, 📤 Share,
🗑 Delete (the current file), ❓ Help.

Filters (top of screen):

- **Search query** — text contains-match (lowercased).
- **Level checkboxes** — TRACE / DEBUG / INFO / WARN / ERROR.
  Default state on a fresh install: WARN + ERROR enabled.
- **Time range** — From / To `HH:mm` text fields with a clock
  picker. Empty = no bound.
- **Tag dropdown** — populated from the file's distinct tag
  set. Default selection "(any)" matches everything; the label
  is prefixed `Tag · …` so the chip's purpose is obvious when
  collapsed. Duplicates (same tag, different casing) are
  normalised.
- **Clear filters** — secondary button on the result-count
  line. Resets every filter to its default.

Rows render reverse-chronological (newest at the top), three
lines per entry (timestamp + tag · level / message preview /
optional matching-tag fragment). Tapping a row opens the
per-entry overlay.

### `AppLogEntryScreen` — per-entry detail

3-line header: TIMESTAMP / LEVEL TAG / message preview. Body:
the full message + any indented stack trace lines that followed
it. Title bar:

- 🐞 Trace — appears when the entry's tag + timestamp match
  an `ApiTracer` trace file (typical for `ApiDispatch` /
  `ApiStreaming` log lines that fire inside a request).
- Prev / Next — walk to the next / previous entry in the
  current filtered set.

### Copy / Share dialog

Tapping 📋 Copy or 📤 Share opens a small dialog:

- **Filtered only** — yes / no toggle.
- **Last N lines** vs **Complete log** — radio.

Copy lands in the clipboard, Share marshals to the system
chooser. Both paths reuse the same serialiser as the in-app
viewer (so a shared log matches what the user just looked at).

## Trimming

`AppLog.deleteLogsOlderThan(cutoffMs)` is exposed via
Housekeeping → Trim by age, alongside the report / chat /
trace trimmers. `clearLogs()` is wired into Housekeeping →
Reset → "Clear app log files".

## Files

- `data/AppLog.kt` — singleton + level enum + file-info type.
- `ui/admin/AppLogScreen.kt` — list + viewer + entry screens.
- `ui/settings/SettingsScreen.kt` — `Logging` card (threshold).
- `model/SettingsModels.kt` /
  `viewmodel/AppViewModel.kt` — `GeneralSettings.logLevel`.
