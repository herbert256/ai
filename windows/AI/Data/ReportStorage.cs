using System.Text.Json;

namespace AI.Data;

public class ReportStorage
{
    public static readonly ReportStorage Instance = new();

    private static readonly string StorageDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AI", "reports");

    private readonly object _lock = new();
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };

    private ReportStorage()
    {
        Directory.CreateDirectory(StorageDir);
    }

    public void Save(StoredReport report)
    {
        lock (_lock)
        {
            try
            {
                var path = Path.Combine(StorageDir, $"{report.Id}.json");
                var json = JsonSerializer.Serialize(report, Options);
                File.WriteAllText(path, json);
            }
            catch { /* ignore */ }
        }
    }

    public StoredReport? Load(string id)
    {
        lock (_lock)
        {
            try
            {
                var path = Path.Combine(StorageDir, $"{id}.json");
                if (!File.Exists(path)) return null;
                var json = File.ReadAllText(path);
                return JsonSerializer.Deserialize<StoredReport>(json);
            }
            catch { return null; }
        }
    }

    public List<StoredReport> LoadAll()
    {
        lock (_lock)
        {
            try
            {
                return Directory.GetFiles(StorageDir, "*.json")
                    .Select(f =>
                    {
                        try { return JsonSerializer.Deserialize<StoredReport>(File.ReadAllText(f)); }
                        catch { return null; }
                    })
                    .Where(r => r != null)
                    .OrderByDescending(r => r!.Timestamp)
                    .Cast<StoredReport>()
                    .ToList();
            }
            catch { return new(); }
        }
    }

    public void Delete(string id)
    {
        lock (_lock)
        {
            try { File.Delete(Path.Combine(StorageDir, $"{id}.json")); }
            catch { /* ignore */ }
        }
    }

    public void DeleteAll()
    {
        lock (_lock)
        {
            try
            {
                foreach (var f in Directory.GetFiles(StorageDir, "*.json"))
                    File.Delete(f);
            }
            catch { /* ignore */ }
        }
    }

    public int Count()
    {
        lock (_lock)
        {
            try { return Directory.GetFiles(StorageDir, "*.json").Length; }
            catch { return 0; }
        }
    }
}
