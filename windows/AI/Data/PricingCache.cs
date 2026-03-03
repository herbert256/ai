using System.Text.Json;
using System.Text.Json.Serialization;

namespace AI.Data;

public class ModelPricing
{
    [JsonPropertyName("modelId")]
    public string ModelId { get; set; } = "";

    [JsonPropertyName("promptPrice")]
    public double PromptPrice { get; set; }

    [JsonPropertyName("completionPrice")]
    public double CompletionPrice { get; set; }

    [JsonPropertyName("source")]
    public string Source { get; set; } = "";

    public ModelPricing() { }

    public ModelPricing(string modelId, double promptPrice, double completionPrice, string source)
    {
        ModelId = modelId;
        PromptPrice = promptPrice;
        CompletionPrice = completionPrice;
        Source = source;
    }
}

public class PricingCache
{
    public static readonly PricingCache Instance = new();

    private static readonly string StorageDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AI", "pricing");

    private readonly object _lock = new();
    private Dictionary<string, ModelPricing> _manualPricing = new();
    private Dictionary<string, ModelPricing> _openRouterPricing = new();
    private DateTimeOffset _openRouterTimestamp = DateTimeOffset.MinValue;
    private bool _loaded;

    private static readonly TimeSpan CacheDuration = TimeSpan.FromDays(7);

    private static readonly ModelPricing DefaultPricing = new("default", 25.0e-6, 75.0e-6, "DEFAULT");

    private static readonly Dictionary<string, ModelPricing> FallbackPricing = new()
    {
        ["deepseek-chat"] = new("deepseek-chat", 0.14e-6, 0.28e-6, "FALLBACK"),
        ["grok-3"] = new("grok-3", 3.0e-6, 15.0e-6, "FALLBACK"),
        ["grok-3-mini"] = new("grok-3-mini", 0.30e-6, 0.50e-6, "FALLBACK"),
        ["mistral-small-latest"] = new("mistral-small-latest", 0.1e-6, 0.3e-6, "FALLBACK"),
        ["sonar"] = new("sonar", 1.0e-6, 1.0e-6, "FALLBACK"),
        ["sonar-pro"] = new("sonar-pro", 3.0e-6, 15.0e-6, "FALLBACK"),
        ["llama-3.3-70b-versatile"] = new("llama-3.3-70b-versatile", 0.59e-6, 0.79e-6, "FALLBACK"),
        ["command-a-03-2025"] = new("command-a-03-2025", 2.5e-6, 10.0e-6, "FALLBACK"),
        ["jamba-mini"] = new("jamba-mini", 0.2e-6, 0.4e-6, "FALLBACK"),
        ["qwen-plus"] = new("qwen-plus", 0.8e-6, 2.0e-6, "FALLBACK"),
        ["glm-4.7-flash"] = new("glm-4.7-flash", 0.007e-6, 0.007e-6, "FALLBACK"),
        ["kimi-latest"] = new("kimi-latest", 0.55e-6, 2.19e-6, "FALLBACK"),
        ["palmyra-x-004"] = new("palmyra-x-004", 5.0e-6, 15.0e-6, "FALLBACK"),
    };

    public ModelPricing GetPricing(AppService provider, string model)
    {
        lock (_lock)
        {
            EnsureLoaded();

            bool isOpenRouter = provider.Id == "OPENROUTER";

            if (isOpenRouter)
            {
                var p = FindOpenRouterPricing(provider, model);
                if (p != null) return p;
            }

            var overrideKey = $"{provider.Id}:{model}";
            if (_manualPricing.TryGetValue(overrideKey, out var manual)) return manual;

            if (!isOpenRouter)
            {
                var p = FindOpenRouterPricing(provider, model);
                if (p != null) return p;
            }

            if (FallbackPricing.TryGetValue(model, out var fallback)) return fallback;

            return DefaultPricing;
        }
    }

    private ModelPricing? FindOpenRouterPricing(AppService provider, string model)
    {
        if (_openRouterPricing.TryGetValue(model, out var p)) return p;
        if (provider.OpenRouterName != null && _openRouterPricing.TryGetValue($"{provider.OpenRouterName}/{model}", out p)) return p;
        foreach (var kv in _openRouterPricing)
            if (kv.Key.EndsWith($"/{model}")) return kv.Value;
        return null;
    }

    public void SetManualPricing(AppService provider, string model, double promptPrice, double completionPrice)
    {
        lock (_lock)
        {
            var key = $"{provider.Id}:{model}";
            _manualPricing[key] = new ModelPricing(model, promptPrice, completionPrice, "OVERRIDE");
            SaveManualPricing();
        }
    }

    public void RemoveManualPricing(AppService provider, string model)
    {
        lock (_lock)
        {
            _manualPricing.Remove($"{provider.Id}:{model}");
            SaveManualPricing();
        }
    }

    public bool NeedsOpenRouterRefresh()
    {
        lock (_lock)
        {
            EnsureLoaded();
            if (_openRouterPricing.Count == 0) return true;
            return DateTimeOffset.UtcNow - _openRouterTimestamp > CacheDuration;
        }
    }

    public void SaveOpenRouterPricing(Dictionary<string, ModelPricing> pricing)
    {
        lock (_lock)
        {
            _openRouterPricing = pricing;
            _openRouterTimestamp = DateTimeOffset.UtcNow;
            try
            {
                Directory.CreateDirectory(StorageDir);
                var json = JsonSerializer.Serialize(pricing);
                File.WriteAllText(Path.Combine(StorageDir, "openrouter.json"), json);
                File.WriteAllText(Path.Combine(StorageDir, "openrouter_timestamp.txt"),
                    _openRouterTimestamp.ToUnixTimeMilliseconds().ToString());
            }
            catch { /* ignore */ }
        }
    }

    private void EnsureLoaded()
    {
        if (_loaded) return;
        _loaded = true;

        try
        {
            var manualPath = Path.Combine(StorageDir, "manual.json");
            if (File.Exists(manualPath))
            {
                var json = File.ReadAllText(manualPath);
                _manualPricing = JsonSerializer.Deserialize<Dictionary<string, ModelPricing>>(json) ?? new();
            }

            var orPath = Path.Combine(StorageDir, "openrouter.json");
            if (File.Exists(orPath))
            {
                var json = File.ReadAllText(orPath);
                _openRouterPricing = JsonSerializer.Deserialize<Dictionary<string, ModelPricing>>(json) ?? new();
            }

            var tsPath = Path.Combine(StorageDir, "openrouter_timestamp.txt");
            if (File.Exists(tsPath) && long.TryParse(File.ReadAllText(tsPath), out var ts))
                _openRouterTimestamp = DateTimeOffset.FromUnixTimeMilliseconds(ts);
        }
        catch { /* ignore load errors */ }
    }

    private void SaveManualPricing()
    {
        try
        {
            Directory.CreateDirectory(StorageDir);
            var json = JsonSerializer.Serialize(_manualPricing);
            File.WriteAllText(Path.Combine(StorageDir, "manual.json"), json);
        }
        catch { /* ignore */ }
    }
}
