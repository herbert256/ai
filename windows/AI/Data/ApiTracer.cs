using System.Text.Json;
using System.Text.Json.Serialization;

namespace AI.Data;

public class TraceRequest
{
    [JsonPropertyName("url")]
    public string Url { get; set; } = "";

    [JsonPropertyName("method")]
    public string Method { get; set; } = "";

    [JsonPropertyName("headers")]
    public Dictionary<string, string> Headers { get; set; } = new();

    [JsonPropertyName("body")]
    public string? Body { get; set; }
}

public class TraceResponse
{
    [JsonPropertyName("statusCode")]
    public int StatusCode { get; set; }

    [JsonPropertyName("headers")]
    public Dictionary<string, string> Headers { get; set; } = new();

    [JsonPropertyName("body")]
    public string? Body { get; set; }
}

public class ApiTrace
{
    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; }

    [JsonPropertyName("hostname")]
    public string Hostname { get; set; } = "";

    [JsonPropertyName("reportId")]
    public string? ReportId { get; set; }

    [JsonPropertyName("model")]
    public string? Model { get; set; }

    [JsonPropertyName("request")]
    public TraceRequest Request { get; set; } = new();

    [JsonPropertyName("response")]
    public TraceResponse Response { get; set; } = new();

    [JsonIgnore]
    public DateTimeOffset TimestampDate => DateTimeOffset.FromUnixTimeMilliseconds(Timestamp);
}

public class TraceFileInfo
{
    public string Filename { get; set; } = "";
    public string Hostname { get; set; } = "";
    public DateTimeOffset Timestamp { get; set; }
    public int StatusCode { get; set; }
    public string? ReportId { get; set; }
    public string? Model { get; set; }
}

public class ApiTracer
{
    public static readonly ApiTracer Instance = new();

    private static readonly string TraceDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AI", "trace");

    private readonly object _lock = new();
    private long _fileSequence;
    private static readonly JsonSerializerOptions WriteOptions = new() { WriteIndented = true };

    public bool IsTracingEnabled { get; set; }
    public string? CurrentReportId { get; set; }

    private ApiTracer()
    {
        Directory.CreateDirectory(TraceDir);
    }

    public void TraceIfEnabled(
        string url, string method,
        Dictionary<string, string> requestHeaders, string? requestBody,
        int statusCode, Dictionary<string, string> responseHeaders, string? responseBody,
        DateTimeOffset timestamp)
    {
        if (!IsTracingEnabled) return;

        var hostname = "unknown";
        try { hostname = new Uri(url).Host; } catch { }

        string? model = null;
        if (requestBody != null)
        {
            try
            {
                using var doc = JsonDocument.Parse(requestBody);
                if (doc.RootElement.TryGetProperty("model", out var m))
                    model = m.GetString();
            }
            catch { }
        }

        var trace = new ApiTrace
        {
            Timestamp = timestamp.ToUnixTimeMilliseconds(),
            Hostname = hostname,
            ReportId = CurrentReportId,
            Model = model,
            Request = new TraceRequest { Url = url, Method = method, Headers = requestHeaders, Body = requestBody },
            Response = new TraceResponse { StatusCode = statusCode, Headers = responseHeaders, Body = responseBody }
        };

        SaveTrace(trace);
    }

    public Task TraceIfEnabledAsync(
        string url, string method,
        Dictionary<string, string> requestHeaders, string? requestBody,
        int statusCode, Dictionary<string, string> responseHeaders, string? responseBody,
        DateTimeOffset timestamp)
    {
        TraceIfEnabled(url, method, requestHeaders, requestBody, statusCode, responseHeaders, responseBody, timestamp);
        return Task.CompletedTask;
    }

    private void SaveTrace(ApiTrace trace)
    {
        lock (_lock)
        {
            try
            {
                _fileSequence++;
                var ts = trace.TimestampDate.ToString("yyyyMMdd_HHmmss_fff");
                var seq = Convert.ToString(_fileSequence, 36);
                var filename = $"{trace.Hostname}_{ts}_{seq}.json";
                var json = JsonSerializer.Serialize(trace, WriteOptions);
                File.WriteAllText(Path.Combine(TraceDir, filename), json);
            }
            catch { /* ignore */ }
        }
    }

    public List<TraceFileInfo> GetTraceFiles()
    {
        lock (_lock)
        {
            try
            {
                return Directory.GetFiles(TraceDir, "*.json")
                    .Select(f =>
                    {
                        try
                        {
                            var json = File.ReadAllText(f);
                            var trace = JsonSerializer.Deserialize<ApiTrace>(json);
                            if (trace == null) return null;
                            return new TraceFileInfo
                            {
                                Filename = Path.GetFileName(f),
                                Hostname = trace.Hostname,
                                Timestamp = trace.TimestampDate,
                                StatusCode = trace.Response.StatusCode,
                                ReportId = trace.ReportId,
                                Model = trace.Model
                            };
                        }
                        catch { return null; }
                    })
                    .Where(t => t != null)
                    .OrderByDescending(t => t!.Timestamp)
                    .Cast<TraceFileInfo>()
                    .ToList();
            }
            catch { return new(); }
        }
    }

    public ApiTrace? ReadTrace(string filename)
    {
        lock (_lock)
        {
            try
            {
                var path = Path.Combine(TraceDir, filename);
                if (!File.Exists(path)) return null;
                return JsonSerializer.Deserialize<ApiTrace>(File.ReadAllText(path));
            }
            catch { return null; }
        }
    }

    public void SetTracingEnabled(bool enabled) => IsTracingEnabled = enabled;
    public void SetCurrentReportId(string? id) => CurrentReportId = id;

    public void ClearTraces()
    {
        lock (_lock)
        {
            try
            {
                foreach (var f in Directory.GetFiles(TraceDir, "*.json"))
                    File.Delete(f);
            }
            catch { /* ignore */ }
        }
    }

    public int GetTraceCount()
    {
        lock (_lock)
        {
            try { return Directory.GetFiles(TraceDir, "*.json").Length; }
            catch { return 0; }
        }
    }
}
