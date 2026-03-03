import SwiftUI

// MARK: - Help Screen

struct HelpScreen: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                helpSection(icon: "star", title: "Welcome to AI", content: "AI is a multi-provider AI-powered reports and chat application. It supports 31 AI services including OpenAI, Anthropic Claude, Google Gemini, and many more.")

                helpSection(icon: "gear", title: "Getting Started", content: "1. Go to Settings > AI Setup > Providers\n2. Configure at least one provider with an API key\n3. Create agents (or let the app auto-create them)\n4. Start generating reports or chatting!")

                helpSection(icon: "house", title: "AI Hub (Home)", content: "The Hub is your main dashboard. It shows quick-access cards for creating new reports, starting chats, and navigating to key features. Provider status indicators show which services are configured.")

                helpSection(icon: "doc.text", title: "AI Reports", content: "Reports let you send the same prompt to multiple AI agents simultaneously. Choose agents individually, as flocks (groups of agents), or as swarms (provider/model combinations). Results are displayed side-by-side for comparison.")

                helpSection(icon: "bubble.left", title: "AI Chat", content: "Chat provides a streaming conversation interface with any configured AI provider. Select a provider and model, then start chatting. Messages stream in real-time using Server-Sent Events (SSE).")

                helpSection(icon: "bubble.left.and.bubble.right", title: "Dual AI Chat", content: "Dual Chat puts two AI models in conversation with each other on a topic you choose. Set the subject and number of interactions, then watch the models discuss.")

                helpSection(icon: "magnifyingglass", title: "AI Models", content: "Browse and search across all models from all configured providers. View model details including provider info and pricing. Test any model with a quick connectivity check.")

                helpSection(icon: "chart.bar", title: "AI Statistics", content: "Track your AI usage including total calls, tokens consumed, and costs. Data is grouped by provider and model. Costs are calculated using a six-tier pricing system.")

                helpSection(icon: "clock", title: "AI History", content: "Browse previously generated reports. Search by title, prompt text, or report content. View, share (as HTML or JSON), or delete individual reports.")

                helpSection(icon: "person.circle", title: "AI Agents", content: "Agents combine a provider, model, API key, endpoint, parameter presets, and system prompt into a reusable configuration. Agents inherit settings from their provider when fields are left empty.")

                helpSection(icon: "bird", title: "AI Flocks", content: "Flocks are groups of agents. When you generate a report with a flock, each agent in the flock processes the prompt independently. Flocks can have their own parameter presets and system prompts.")

                helpSection(icon: "ant", title: "AI Swarms", content: "Swarms are groups of provider/model combinations (not agents). They're useful when you want to test many models without creating individual agents for each one.")

                helpSection(icon: "slider.horizontal.3", title: "AI Parameters", content: "Parameter presets let you save and reuse combinations of: temperature, max tokens, top P, top K, frequency penalty, presence penalty, seed, system prompt, JSON mode, web search, citations, and search recency.")

                helpSection(icon: "text.bubble", title: "System Prompts", content: "System prompts are reusable instructions that set the AI's behavior. They can be attached to agents, flocks, and swarms. The system prompt is sent before the user's message.")

                helpSection(icon: "dollarsign.circle", title: "AI Costs", content: "Costs are calculated using a six-tier pricing system:\n1. API-reported cost (highest priority)\n2. Manual price overrides\n3. OpenRouter pricing\n4. LiteLLM pricing\n5. Hardcoded fallback prices\n6. Default ($25/$75 per million tokens)")

                helpSection(icon: "wrench", title: "Housekeeping", content: "Housekeeping provides tools to refresh provider states, update model lists, export/import configuration, and clean up old data. Use 'Refresh All' to update everything at once.")

                helpSection(icon: "arrow.triangle.2.circlepath", title: "Export/Import", content: "Configuration can be exported as JSON (v21 format) and imported on another device - including between Android and iOS. The format includes providers, agents, flocks, swarms, parameters, prompts, and more.")

                helpSection(icon: "brain", title: "Think Sections", content: "Some AI models (especially Claude and DeepSeek) include <think> sections in their responses showing their reasoning process. These are displayed as collapsible sections you can expand to see the model's thought process.")

                helpSection(icon: "questionmark.circle", title: "Troubleshooting", content: "- 'Error: No API key' -> Configure the provider's API key in AI Setup\n- 'Error: 401' -> Check if your API key is valid\n- 'Error: 429' -> Rate limit reached, wait and try again\n- 'Error: 500' -> Server error on the provider's side\n- Models not loading -> Check your internet connection and try refreshing")
            }
            .padding()
        }
        .background(AppColors.background)
        .navigationTitle("Help")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func helpSection(icon: String, title: String, content: String) -> some View {
        CardView {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Image(systemName: icon)
                        .foregroundStyle(AppColors.accentBlue)
                    Text(title)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundStyle(AppColors.onSurface)
                }
                Text(content)
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineSpacing(4)
            }
        }
    }
}
