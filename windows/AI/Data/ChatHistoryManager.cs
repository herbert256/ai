using System.Text.Json;

namespace AI.Data;

public class ChatHistoryManager
{
    public static readonly ChatHistoryManager Instance = new();

    private static readonly string StorageDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AI", "chat-history");

    private readonly object _lock = new();
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };

    private ChatHistoryManager()
    {
        Directory.CreateDirectory(StorageDir);
    }

    public void Save(ChatSession session)
    {
        lock (_lock)
        {
            try
            {
                var path = Path.Combine(StorageDir, $"{session.Id}.json");
                var json = JsonSerializer.Serialize(session, Options);
                File.WriteAllText(path, json);
            }
            catch { /* ignore */ }
        }
    }

    public ChatSession? Load(string id)
    {
        lock (_lock)
        {
            try
            {
                var path = Path.Combine(StorageDir, $"{id}.json");
                if (!File.Exists(path)) return null;
                return JsonSerializer.Deserialize<ChatSession>(File.ReadAllText(path));
            }
            catch { return null; }
        }
    }

    public List<ChatSession> LoadAll()
    {
        lock (_lock)
        {
            try
            {
                return Directory.GetFiles(StorageDir, "*.json")
                    .Select(f =>
                    {
                        try { return JsonSerializer.Deserialize<ChatSession>(File.ReadAllText(f)); }
                        catch { return null; }
                    })
                    .Where(s => s != null)
                    .OrderByDescending(s => s!.UpdatedAt)
                    .Cast<ChatSession>()
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

    public List<ChatSession> Search(string query)
    {
        var lowered = query.ToLowerInvariant();
        return LoadAll().Where(s =>
            s.Messages.Any(m => m.Content.ToLowerInvariant().Contains(lowered))
        ).ToList();
    }
}
