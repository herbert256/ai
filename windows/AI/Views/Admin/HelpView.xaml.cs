using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace AI.Views.Admin;

public partial class HelpView : UserControl
{
    public HelpView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        var sections = new (string Icon, string Title, string Description)[]
        {
            ("\u2728", "Welcome",
                "AI is a multi-platform application that provides access to 31 AI services. " +
                "Generate reports, chat with models, compare outputs across providers, and manage your AI workflow from a single interface."),

            ("\ud83d\ude80", "Getting Started",
                "Go to AI Setup to configure your providers. Enter an API key for at least one provider, then create agents to start using AI features. " +
                "Each agent links to a provider, model, and optional parameters."),

            ("\ud83d\udcca", "Reports",
                "Reports let you send a prompt to multiple AI models simultaneously and compare their outputs. " +
                "Select individual agents, flocks (groups of agents), or swarms (model combinations) to generate a multi-perspective report."),

            ("\ud83d\udcac", "Chat",
                "Chat provides a conversational interface with any configured AI model. " +
                "Supports streaming responses, chat history, and customizable parameters per conversation."),

            ("\ud83e\udd16", "Agents",
                "Agents are your configured AI endpoints. Each agent has a provider, model, and optional API key override. " +
                "Agents inherit settings from their provider unless explicitly overridden."),

            ("\ud83d\udc65", "Flocks",
                "Flocks are named groups of agents. Use flocks to quickly select multiple agents for report generation. " +
                "Each flock can have its own parameter presets and system prompt."),

            ("\ud83d\udc1d", "Swarms",
                "Swarms are collections of provider/model pairs without individual agent configuration. " +
                "They are useful for quickly comparing the same prompt across many models from different providers."),

            ("\u2699\ufe0f", "Parameters",
                "Parameter presets let you define reusable configurations (temperature, max tokens, top-p, etc.). " +
                "Parameters can be attached to agents, flocks, swarms, or providers. Multiple presets are merged with later values winning."),

            ("\ud83d\udcdd", "System Prompts",
                "System prompts define the AI's behavior and persona. Create reusable system prompts and attach them to agents, flocks, or swarms. " +
                "The system prompt is sent as the first message in each conversation."),

            ("\ud83d\udcac", "Prompts",
                "Saved prompts are reusable templates for common tasks. They support variable substitution: " +
                "@MODEL@, @PROVIDER@, @AGENT@, @SWARM@, and @NOW@ are replaced at runtime."),

            ("\ud83d\udd0d", "Model Search",
                "Browse and search available models for each provider. Fetch the latest model list from provider APIs, " +
                "and test individual models to verify connectivity."),

            ("\ud83d\udcc8", "AI Usage",
                "View usage statistics across all providers including API call counts, token usage, and estimated costs. " +
                "Costs are calculated using OpenRouter pricing data with fallback defaults."),

            ("\ud83e\uddf9", "Housekeeping",
                "Administrative tools for refreshing model lists, testing all providers, generating default agents, " +
                "exporting/importing configuration, and cleaning up stored data."),

            ("\ud83d\udd27", "Developer Mode",
                "Send direct API requests to any provider for testing and debugging. " +
                "Configure endpoint, model, parameters, and view raw responses."),

            ("\ud83d\udcdd", "API Traces",
                "View detailed logs of all API requests and responses when tracing is enabled. " +
                "Inspect request/response headers and bodies, filter by hostname, status code, or model."),

            ("\ud83d\udce6", "Export / Import",
                "Export your full configuration (providers, agents, flocks, swarms, parameters, prompts) to a JSON file. " +
                "Import configurations from other devices or platforms. Compatible with Android and macOS versions (format v11-v21).")
        };

        foreach (var (icon, title, description) in sections)
            HelpPanel.Children.Add(MakeHelpCard(icon, title, description));
    }

    private Border MakeHelpCard(string icon, string title, string description)
    {
        var stack = new StackPanel();

        var header = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 8) };
        header.Children.Add(new TextBlock
        {
            Text = icon,
            FontSize = 20,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 12, 0)
        });
        header.Children.Add(new TextBlock
        {
            Text = title,
            FontSize = 16,
            FontWeight = FontWeights.SemiBold,
            VerticalAlignment = VerticalAlignment.Center
        });
        stack.Children.Add(header);

        stack.Children.Add(new TextBlock
        {
            Text = description,
            Foreground = (SolidColorBrush)FindResource("OnSurfaceVariantBrush"),
            TextWrapping = TextWrapping.Wrap,
            LineHeight = 20
        });

        return new Border
        {
            Background = (SolidColorBrush)FindResource("CardBackgroundBrush"),
            BorderBrush = (SolidColorBrush)FindResource("CardBorderBrush"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(20),
            Margin = new Thickness(0, 0, 0, 12),
            Child = stack
        };
    }
}
