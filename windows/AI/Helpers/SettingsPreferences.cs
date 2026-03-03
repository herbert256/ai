using System.Text.Json;
using AI.Data;
using AI.ViewModels;

namespace AI.Helpers;

public static class SettingsPreferences
{
    private static readonly string StorageDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AI");

    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    static SettingsPreferences()
    {
        Directory.CreateDirectory(StorageDir);
    }

    // Settings

    public static ViewModels.Settings LoadSettings()
    {
        try
        {
            var path = Path.Combine(StorageDir, "settings.json");
            if (!File.Exists(path)) return new();
            var json = File.ReadAllText(path);
            return JsonSerializer.Deserialize<ViewModels.Settings>(json, Options) ?? new();
        }
        catch { return new(); }
    }

    public static void SaveSettings(ViewModels.Settings settings)
    {
        try
        {
            var json = JsonSerializer.Serialize(settings, Options);
            File.WriteAllText(Path.Combine(StorageDir, "settings.json"), json);
        }
        catch { /* ignore */ }
    }

    // General Settings

    public static GeneralSettings LoadGeneralSettings()
    {
        try
        {
            var path = Path.Combine(StorageDir, "general.json");
            if (!File.Exists(path)) return new();
            return JsonSerializer.Deserialize<GeneralSettings>(File.ReadAllText(path), Options) ?? new();
        }
        catch { return new(); }
    }

    public static void SaveGeneralSettings(GeneralSettings settings)
    {
        try
        {
            var json = JsonSerializer.Serialize(settings, Options);
            File.WriteAllText(Path.Combine(StorageDir, "general.json"), json);
        }
        catch { /* ignore */ }
    }

    // Usage Stats

    public static Dictionary<string, UsageStats> LoadUsageStats()
    {
        try
        {
            var path = Path.Combine(StorageDir, "usage-stats.json");
            if (!File.Exists(path)) return new();
            return JsonSerializer.Deserialize<Dictionary<string, UsageStats>>(File.ReadAllText(path), Options) ?? new();
        }
        catch { return new(); }
    }

    public static void SaveUsageStats(Dictionary<string, UsageStats> stats)
    {
        try
        {
            var json = JsonSerializer.Serialize(stats, Options);
            File.WriteAllText(Path.Combine(StorageDir, "usage-stats.json"), json);
        }
        catch { /* ignore */ }
    }

    // Prompt History

    public static List<PromptHistoryEntry> LoadPromptHistory()
    {
        try
        {
            var path = Path.Combine(StorageDir, "prompt-history.json");
            if (!File.Exists(path)) return new();
            return JsonSerializer.Deserialize<List<PromptHistoryEntry>>(File.ReadAllText(path), Options) ?? new();
        }
        catch { return new(); }
    }

    public static void SavePromptHistory(List<PromptHistoryEntry> history)
    {
        try
        {
            var json = JsonSerializer.Serialize(history, Options);
            File.WriteAllText(Path.Combine(StorageDir, "prompt-history.json"), json);
        }
        catch { /* ignore */ }
    }

    public static void AddPromptHistoryEntry(string title, string prompt)
    {
        var history = LoadPromptHistory();
        history.Insert(0, new PromptHistoryEntry(title, prompt));
        if (history.Count > 100) history = history.Take(100).ToList();
        SavePromptHistory(history);
    }
}
