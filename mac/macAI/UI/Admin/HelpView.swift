import SwiftUI

/// In-app documentation and help.
struct HelpView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HelpSection(
                    icon: "star",
                    title: "Welcome to macAI",
                    text: "macAI is an AI-powered tool for generating reports and chatting with AI models from \(AppService.entries.count) providers. Configure your API keys, create agents, and start generating multi-model reports."
                )

                HelpSection(
                    icon: "1.circle",
                    title: "Getting Started",
                    text: "1. Go to AI Setup > Providers and add your API keys\n2. Test the connection to verify your keys work\n3. Create agents in AI Setup > Agents\n4. Group agents into flocks for multi-model reports\n5. Start a chat or generate a report"
                )

                HelpSection(
                    icon: "doc.badge.plus",
                    title: "AI Reports",
                    text: "Reports let you send the same prompt to multiple AI models simultaneously. Select agents, flocks, or swarms, enter your prompt, and generate. Results are displayed per-agent with expandable cards. Export to HTML for sharing."
                )

                HelpSection(
                    icon: "bubble.left.and.bubble.right",
                    title: "AI Chat",
                    text: "Chat with any configured AI model. Supports streaming responses, chat history, and parameter customization. Use Dual Chat to have two models discuss a topic."
                )

                HelpSection(
                    icon: "person.3",
                    title: "Agents",
                    text: "Agents combine a provider, model, API key, and parameters. They inherit API key and model from their provider when those fields are empty. Agents are the building blocks for flocks and reports."
                )

                HelpSection(
                    icon: "bird",
                    title: "Flocks",
                    text: "Flocks are named groups of agents. Use them to run reports across multiple agents at once. Each flock can have its own parameter presets and system prompt."
                )

                HelpSection(
                    icon: "ant",
                    title: "Swarms",
                    text: "Swarms are lightweight groups of provider/model pairs. Unlike flocks (which reference agents), swarms directly specify provider and model combinations. Good for quick multi-model comparisons."
                )

                HelpSection(
                    icon: "slider.horizontal.3",
                    title: "Parameters",
                    text: "Parameter presets configure model behavior: temperature, max tokens, top-p/k, penalties, seed, and more. Assign presets to agents, flocks, swarms, or individual reports. Multiple presets are merged (later values win)."
                )

                HelpSection(
                    icon: "text.alignleft",
                    title: "System Prompts",
                    text: "Reusable system prompts that can be assigned to agents, flocks, and swarms. System prompts set the AI's behavior and persona."
                )

                HelpSection(
                    icon: "doc.text",
                    title: "Prompts",
                    text: "Internal app prompts with variable substitution. Supported variables: @MODEL@ (model name), @PROVIDER@ (provider name), @AGENT@ (agent name), @SWARM@ (swarm name), @NOW@ (current date/time)."
                )

                HelpSection(
                    icon: "magnifyingglass",
                    title: "Model Search",
                    text: "Search across all models from all configured providers. Find specific models, view their details, and create agents directly from search results."
                )

                HelpSection(
                    icon: "chart.bar",
                    title: "AI Usage",
                    text: "Track token usage and costs across all providers and models. Costs are calculated using a six-tier pricing system: API response > manual overrides > OpenRouter > LiteLLM > fallback > default."
                )

                HelpSection(
                    icon: "hammer",
                    title: "Housekeeping",
                    text: "Maintenance tools: refresh model lists from all providers, test all API keys, generate default agents, and clean up old data (chats, reports, traces, statistics)."
                )

                HelpSection(
                    icon: "terminal",
                    title: "Developer Mode",
                    text: "Build and send raw API requests for testing. Choose a provider, configure parameters, edit the JSON request body, and inspect the full trace."
                )

                HelpSection(
                    icon: "network",
                    title: "API Traces",
                    text: "View detailed logs of all API requests and responses. Inspect headers, request bodies, and response data. Useful for debugging API issues."
                )

                HelpSection(
                    icon: "square.and.arrow.up",
                    title: "Export / Import",
                    text: "Export your full configuration (providers, agents, flocks, swarms, parameters) as JSON (v21 format). Import configurations from file. Compatible with the Android version of the app."
                )

                HelpSection(
                    icon: "brain",
                    title: "Think Sections",
                    text: "Some AI models include reasoning in <think> tags. These are displayed as collapsible sections, letting you see the model's thought process."
                )

                HelpSection(
                    icon: "lock.shield",
                    title: "Privacy & Security",
                    text: "API keys are stored locally in UserDefaults. No data is sent to third parties other than the AI providers you configure. Export files contain API keys in plain text - handle with care."
                )
            }
            .padding()
        }
        .navigationTitle("Help")
    }
}

// MARK: - Help Section

struct HelpSection: View {
    let icon: String
    let title: String
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .foregroundStyle(AppColors.primary)
                    .frame(width: 24)
                Text(title)
                    .font(.headline)
            }
            Text(text)
                .font(.body)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(AppColors.cardBorder, lineWidth: 1)
        )
    }
}
