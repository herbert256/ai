using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace AI.Data;

/// <summary>
/// Mutable registry of AI service providers.
/// On first launch, loads from embedded Resources/setup.json.
/// Subsequent launches load from %AppData%/AI/provider-registry.json.
/// Supports CRUD operations for fully data-driven provider management.
/// </summary>
public class ProviderRegistry
{
    // Singleton
    public static readonly ProviderRegistry Instance = new();

    private static readonly string StorageDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AI");
    private static readonly string StoragePath =
        Path.Combine(StorageDir, "provider-registry.json");
    private const string EmbeddedResourceName = "AI.Resources.setup.json";

    private readonly List<AppService> _providers = new();
    private readonly object _lock = new();
    private bool _initialized;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    private ProviderRegistry() { }

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    /// <summary>
    /// Initialize the registry. Must be called before any provider access.
    /// Loads from %AppData%/AI/provider-registry.json if it exists,
    /// otherwise falls back to the embedded setup.json resource.
    /// </summary>
    public void Initialize()
    {
        lock (_lock)
        {
            if (_initialized) return;

            if (File.Exists(StoragePath))
            {
                try
                {
                    var json = File.ReadAllText(StoragePath);
                    var defs = JsonSerializer.Deserialize<List<ProviderDefinition>>(json, JsonOptions);
                    if (defs != null && defs.Count > 0)
                    {
                        _providers.Clear();
                        _providers.AddRange(defs.Select(d => d.ToAppService()));
                    }
                    else
                    {
                        LoadFromEmbeddedResource();
                    }
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"[ProviderRegistry] Error loading from file: {ex.Message}");
                    LoadFromEmbeddedResource();
                }
            }
            else
            {
                LoadFromEmbeddedResource();
            }

            // Safety net: if providers list is still empty, try embedded resource again
            if (_providers.Count == 0)
            {
                System.Diagnostics.Debug.WriteLine("[ProviderRegistry] No providers loaded, falling back to embedded resource");
                LoadFromEmbeddedResource();
            }

            _initialized = true;
            System.Diagnostics.Debug.WriteLine($"[ProviderRegistry] Initialized with {_providers.Count} providers");
        }
    }

    // -------------------------------------------------------------------------
    // Embedded resource loading
    // -------------------------------------------------------------------------

    private void LoadFromEmbeddedResource()
    {
        try
        {
            var assembly = System.Reflection.Assembly.GetExecutingAssembly();
            using var stream = assembly.GetManifestResourceStream(EmbeddedResourceName);
            if (stream == null)
            {
                System.Diagnostics.Debug.WriteLine($"[ProviderRegistry] Embedded resource '{EmbeddedResourceName}' not found");
                return;
            }

            using var reader = new StreamReader(stream);
            var json = reader.ReadToEnd();

            var setup = JsonSerializer.Deserialize<ConfigSetup>(json, JsonOptions);
            var defs = setup?.ProviderDefinitions;
            if (defs == null || defs.Count == 0)
            {
                System.Diagnostics.Debug.WriteLine("[ProviderRegistry] No providerDefinitions in setup.json");
                return;
            }

            _providers.Clear();
            _providers.AddRange(defs.Select(d => d.ToAppService()));
            Save();
            System.Diagnostics.Debug.WriteLine($"[ProviderRegistry] Loaded {_providers.Count} providers from embedded setup.json");
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[ProviderRegistry] Error loading embedded resource: {ex.Message}");
        }
    }

    // -------------------------------------------------------------------------
    // Synchronous accessors
    // -------------------------------------------------------------------------

    /// <summary>Returns a snapshot of all registered providers.</summary>
    public List<AppService> GetAll()
    {
        lock (_lock)
        {
            return new List<AppService>(_providers);
        }
    }

    /// <summary>Find a provider by its ID string, or null if not found.</summary>
    public AppService? FindById(string id)
    {
        lock (_lock)
        {
            return _providers.FirstOrDefault(p => p.Id == id);
        }
    }

    // -------------------------------------------------------------------------
    // CRUD operations
    // -------------------------------------------------------------------------

    /// <summary>Add a new provider and persist.</summary>
    public void Add(AppService service)
    {
        lock (_lock)
        {
            _providers.Add(service);
            Save();
        }
    }

    /// <summary>Update an existing provider matched by Id and persist.</summary>
    public void Update(AppService service)
    {
        lock (_lock)
        {
            var index = _providers.FindIndex(p => p.Id == service.Id);
            if (index >= 0)
            {
                _providers[index] = service;
                Save();
            }
        }
    }

    /// <summary>Remove a provider by Id and persist.</summary>
    public void Remove(string id)
    {
        lock (_lock)
        {
            _providers.RemoveAll(p => p.Id == id);
            Save();
        }
    }

    /// <summary>
    /// Ensure providers from a list exist in the registry (used during import).
    /// Adds any that are missing by Id.
    /// </summary>
    public void EnsureProviders(IEnumerable<AppService> services)
    {
        lock (_lock)
        {
            var changed = false;
            foreach (var service in services)
            {
                if (_providers.All(p => p.Id != service.Id))
                {
                    _providers.Add(service);
                    changed = true;
                }
            }
            if (changed) Save();
        }
    }

    /// <summary>Re-initialize from embedded resource (for reset/refresh).</summary>
    public void ResetToDefaults()
    {
        lock (_lock)
        {
            _initialized = false;
            _providers.Clear();

            if (File.Exists(StoragePath))
            {
                try { File.Delete(StoragePath); }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"[ProviderRegistry] Error deleting storage file: {ex.Message}");
                }
            }

            LoadFromEmbeddedResource();
            _initialized = true;
        }
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    /// <summary>Persist current providers to %AppData%/AI/provider-registry.json.</summary>
    public void Save()
    {
        try
        {
            Directory.CreateDirectory(StorageDir);
            var defs = _providers.Select(ProviderDefinition.FromAppService).ToList();
            var json = JsonSerializer.Serialize(defs, JsonOptions);
            File.WriteAllText(StoragePath, json);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[ProviderRegistry] Error saving providers: {ex.Message}");
        }
    }
}

// ---------------------------------------------------------------------------
// Setup JSON structure
// ---------------------------------------------------------------------------

/// <summary>Minimal structure for parsing setup.json (and config export).</summary>
internal class ConfigSetup
{
    [JsonPropertyName("version")]
    public int? Version { get; set; }

    [JsonPropertyName("providerDefinitions")]
    public List<ProviderDefinition>? ProviderDefinitions { get; set; }

    // Also accept a "providers" key at the top level (legacy / alternate format)
    [JsonPropertyName("providers")]
    public JsonElement? Providers { get; set; }
}
