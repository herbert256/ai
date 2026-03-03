# Calling the AI App from External Applications

This document describes how to call the AI app from another Android application (e.g., the chess Eval app) to generate AI reports.

## Overview

The AI app accepts external intents with a title, prompt, optional system prompt, and optional instructions. When launched with instructions containing agent/model selection tags, it goes directly to report generation. Otherwise it opens the "New AI Report" screen with the provided data pre-filled.

## Intent Details

### Action
```
com.ai.ACTION_NEW_REPORT
```

### Package
```
com.ai
```

### Extras

| Extra Key | Type | Required | Description |
|-----------|------|----------|-------------|
| `title` | String | Optional | Title for the AI report (appears in report header) |
| `system` | String | Optional | System prompt sent to AI models (used as fallback when agent/flock/swarm has no system prompt configured) |
| `prompt` | String | Required | The prompt/question to send to AI agents |
| `instructions` | String | Optional | Control tags for report behavior (see Instructions Tags below) |

### Instruction Tags

The `instructions` parameter supports XML-style tags to control report behavior:

| Tag | Description |
|-----|-------------|
| `<agent>Name</agent>` | Select an agent by name (repeatable) |
| `<flock>Name</flock>` | Select a flock by name (repeatable) |
| `<swarm>Name</swarm>` | Select a swarm by name (repeatable) |
| `<model>provider/model</model>` | Select a specific model (repeatable) |
| `<type>Classic</type>` or `<type>Table</type>` | Report format |
| `<open>HTML</open>` | HTML content shown at the top of the report |
| `<close>HTML</close>` | HTML content shown at the bottom of the report |
| `<next>View</next>` | Auto-action on completion: `View`, `Share`, `Browser`, `Email` |
| `<email>addr@example.com</email>` | Auto-email report on completion |
| `<edit>` | Show the New Report screen for prompt editing before generating |
| `<select>` | Show model selection screen even when agent/model tags are present |
| `<return>` | Finish the activity after the `<next>` action completes |

### Legacy Format

For backward compatibility, the `prompt` extra can contain both the prompt and instructions separated by a `-- end prompt --` marker. The text above the marker is the prompt; the text below contains instruction tags. When the `instructions` extra is provided, it takes precedence and the marker is not used.

## Implementation in Calling App

### Kotlin Example

```kotlin
/**
 * Launch the AI app with a prompt for report generation.
 *
 * @param title Report title (optional)
 * @param system System prompt for AI models (optional)
 * @param prompt The prompt to send to AI agents
 * @param instructions Control tags for report behavior (optional)
 */
fun launchAiReport(
    context: Context,
    title: String,
    prompt: String,
    system: String? = null,
    instructions: String? = null
) {
    val intent = Intent().apply {
        action = "com.ai.ACTION_NEW_REPORT"
        setPackage("com.ai")
        putExtra("title", title)
        putExtra("prompt", prompt)
        if (system != null) putExtra("system", system)
        if (instructions != null) putExtra("instructions", instructions)
    }

    // Check if AI app is installed
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        // AI app not installed - show error or fallback
        Toast.makeText(context, "AI app not installed", Toast.LENGTH_SHORT).show()
    }
}
```

### Usage Examples

```kotlin
// Simple report - opens New Report screen for user to review and generate
launchAiReport(
    context = context,
    title = "Chess Position Analysis",
    prompt = "Analyze this chess position: FEN $fen"
)

// With system prompt and auto-generation using a specific flock
launchAiReport(
    context = context,
    title = "Chess Position Analysis",
    prompt = "Analyze this chess position: FEN $fen",
    system = "You are a chess grandmaster. Provide detailed positional analysis.",
    instructions = "<flock>Chess Engines</flock><type>Classic</type><next>View</next>"
)

// Auto-generate with specific agents, email result, then close
launchAiReport(
    context = context,
    title = "Daily Report",
    prompt = "Generate the daily summary for today.",
    instructions = """
        <agent>GPT-4o</agent>
        <agent>Claude</agent>
        <type>Table</type>
        <email>user@example.com</email>
        <return>
    """.trimIndent()
)
```

## User Flow

### Without instructions
1. User taps "AI Analysis" button in calling app
2. Calling app creates intent with title and prompt
3. AI app launches and shows "New AI Report" screen with pre-filled data
4. User reviews the prompt (can edit if desired)
5. User taps "Generate" and selects AI agents
6. AI app generates report from selected agents
7. User views results in AI app

### With instructions (agent/model selection tags)
1. Calling app creates intent with title, prompt, and instructions
2. AI app launches directly to the report generation screen
3. Selected agents/models are auto-populated from instruction tags
4. Report generates automatically if a `<type>` tag is present
5. On completion, the `<next>` action triggers automatically (if specified)

## Notes

- The AI app handles all API calls and agent configuration
- Users must have configured at least one AI agent in the AI app before generating reports
- The prompt can include any text - markdown formatting is preserved
- Special placeholder `@DATE@` in the prompt will be replaced with the current date
- The title is optional; if omitted, the report will have no title header
- The `system` prompt is used as a fallback: if an agent/flock/swarm has its own system prompt configured, that takes precedence

## Error Handling

Check if the AI app is installed before launching:

```kotlin
val intent = Intent("com.ai.ACTION_NEW_REPORT")
intent.setPackage("com.ai")

if (intent.resolveActivity(packageManager) != null) {
    startActivity(intent)
} else {
    // Show message that AI app needs to be installed
    showInstallAiAppDialog()
}
```

## Dependencies

The calling app needs no special permissions or dependencies. It only needs to be able to send standard Android intents.
