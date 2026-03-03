using System.Text.Json.Serialization;
using AI.Data;

namespace AI.ViewModels;

public class GeneralSettings
{
    [JsonPropertyName("userName")]
    public string UserName { get; set; } = "user";

    [JsonPropertyName("developerMode")]
    public bool DeveloperMode { get; set; } = true;

    [JsonPropertyName("huggingFaceApiKey")]
    public string HuggingFaceApiKey { get; set; } = "";

    [JsonPropertyName("openRouterApiKey")]
    public string OpenRouterApiKey { get; set; } = "";

    [JsonPropertyName("defaultEmail")]
    public string DefaultEmail { get; set; } = "";
}

public enum SidebarSection
{
    Hub,
    NewReport,
    ReportHistory,
    PromptHistory,
    Chat,
    ChatHistory,
    DualChat,
    ModelSearch,
    Statistics,
    Settings,
    Setup,
    Housekeeping,
    Traces,
    Developer,
    Help
}

public enum SidebarGroup
{
    Main,
    Reports,
    Chat,
    Tools,
    Admin
}

public static class SidebarExtensions
{
    public static string DisplayName(this SidebarSection section) => section switch
    {
        SidebarSection.Hub => "Hub",
        SidebarSection.NewReport => "New Report",
        SidebarSection.ReportHistory => "Report History",
        SidebarSection.PromptHistory => "Prompt History",
        SidebarSection.Chat => "Chat",
        SidebarSection.ChatHistory => "Chat History",
        SidebarSection.DualChat => "Dual Chat",
        SidebarSection.ModelSearch => "Model Search",
        SidebarSection.Statistics => "AI Usage",
        SidebarSection.Settings => "Settings",
        SidebarSection.Setup => "AI Setup",
        SidebarSection.Housekeeping => "Housekeeping",
        SidebarSection.Traces => "API Traces",
        SidebarSection.Developer => "Developer",
        SidebarSection.Help => "Help",
        _ => section.ToString()
    };

    public static string Icon(this SidebarSection section) => section switch
    {
        SidebarSection.Hub => "\uE80F",           // Home
        SidebarSection.NewReport => "\uE8A5",     // Document
        SidebarSection.ReportHistory => "\uE81C",  // History
        SidebarSection.PromptHistory => "\uE8BD",  // Chat
        SidebarSection.Chat => "\uE8F2",           // Message
        SidebarSection.ChatHistory => "\uE838",    // Archive
        SidebarSection.DualChat => "\uE8A9",       // Split
        SidebarSection.ModelSearch => "\uE721",    // Search
        SidebarSection.Statistics => "\uE9D2",     // Chart
        SidebarSection.Settings => "\uE713",       // Settings
        SidebarSection.Setup => "\uE90F",          // Wrench
        SidebarSection.Housekeeping => "\uE90F",   // Tool
        SidebarSection.Traces => "\uE968",         // Network
        SidebarSection.Developer => "\uE756",      // Terminal
        SidebarSection.Help => "\uE897",           // Help
        _ => "\uE8A5"
    };

    public static SidebarGroup Group(this SidebarSection section) => section switch
    {
        SidebarSection.Hub => SidebarGroup.Main,
        SidebarSection.NewReport or SidebarSection.ReportHistory or SidebarSection.PromptHistory => SidebarGroup.Reports,
        SidebarSection.Chat or SidebarSection.ChatHistory or SidebarSection.DualChat => SidebarGroup.Chat,
        SidebarSection.ModelSearch or SidebarSection.Statistics => SidebarGroup.Tools,
        _ => SidebarGroup.Admin
    };

    public static SidebarSection[] SectionsForGroup(this SidebarGroup group)
    {
        return Enum.GetValues<SidebarSection>().Where(s => s.Group() == group).ToArray();
    }
}
