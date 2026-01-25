# Calling the AI App from External Applications

This document describes how to call the AI app from another Android application (e.g., the chess Eval app) to generate AI reports.

## Overview

The AI app accepts external intents with a title and prompt. When launched this way, it opens directly to the "New AI Report" screen with the provided data pre-filled. The user then selects their AI agents and generates the report.

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
| `prompt` | String | Required | The prompt/question to send to AI agents |

## Implementation in Calling App

### Kotlin Example

```kotlin
/**
 * Launch the AI app with a prompt for report generation.
 *
 * @param title Report title (optional)
 * @param prompt The prompt to send to AI agents
 */
fun launchAiReport(context: Context, title: String, prompt: String) {
    val intent = Intent().apply {
        action = "com.ai.ACTION_NEW_REPORT"
        setPackage("com.ai")
        putExtra("title", title)
        putExtra("prompt", prompt)
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

### Usage Example (Chess Eval App)

```kotlin
// When user clicks "AI Analysis" button for a position
fun onAiAnalysisClick(fen: String, playerToMove: String) {
    val title = "Chess Position Analysis"
    val prompt = """
        Analyze this chess position:

        FEN: $fen

        It's $playerToMove's turn to move.

        Please provide:
        1. Evaluation of the position
        2. Best moves for both sides
        3. Key strategic themes
        4. Tactical opportunities
    """.trimIndent()

    launchAiReport(context, title, prompt)
}

// When user clicks "AI Analysis" for a player
fun onPlayerAnalysisClick(username: String, platform: String) {
    val title = "Player Analysis: $username"
    val prompt = """
        Analyze the chess player $username on $platform.

        Please provide insights about:
        1. Playing style and strengths
        2. Opening repertoire
        3. Areas for improvement
        4. Notable games or achievements
    """.trimIndent()

    launchAiReport(context, title, prompt)
}
```

## User Flow

1. User taps "AI Analysis" button in chess Eval app
2. Chess Eval app creates intent with title and prompt
3. AI app launches and shows "New AI Report" screen with pre-filled data
4. User reviews the prompt (can edit if desired)
5. User taps "Generate" and selects AI agents
6. AI app generates report from selected agents
7. User views results in AI app

## Notes

- The AI app handles all API calls and agent configuration
- Users must have configured at least one AI agent in the AI app before generating reports
- The prompt can include any text - markdown formatting is preserved
- Special placeholder `@DATE@` in the prompt will be replaced with the current date
- The title is optional; if omitted, the report will have no title header

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
