using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace AI.Data;

/// <summary>
/// HttpClient-based API client replacing URLSession.
/// Handles auth per format: Bearer (OpenAI), x-api-key + anthropic-version (Anthropic),
/// ?key= query param (Google).
/// </summary>
public class ApiClient
{
    public static readonly ApiClient Instance = new();

    private static readonly HttpClient Http;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = null,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    static ApiClient()
    {
        Http = new HttpClient
        {
            Timeout = TimeSpan.FromSeconds(600)  // Resource timeout; per-request timeout via CancellationToken
        };
    }

    private ApiClient() { }

    // MARK: - Generic POST Request

    /// <summary>
    /// Send a JSON POST request, decode the response, and trace via ApiTracer.
    /// </summary>
    public async Task<(TRes Response, int StatusCode)> RequestAsync<TReq, TRes>(
        string url,
        TReq body,
        AppService service,
        string apiKey,
        Dictionary<string, string>? extraHeaders = null)
    {
        var requestUrl = BuildUrl(url, service, apiKey);

        using var request = new HttpRequestMessage(HttpMethod.Post, requestUrl);
        ApplyAuthHeaders(request, service, apiKey);
        request.Content = new StringContent(
            JsonSerializer.Serialize(body, JsonOptions),
            Encoding.UTF8,
            "application/json");

        if (extraHeaders != null)
        {
            foreach (var (key, value) in extraHeaders)
                request.Headers.TryAddWithoutValidation(key, value);
        }

        var requestBodyString = await (request.Content?.ReadAsStringAsync() ?? Task.FromResult(""));
        var traceTimestamp = DateTime.UtcNow;

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        HttpResponseMessage response;
        try
        {
            response = await Http.SendAsync(request, HttpCompletionOption.ResponseContentRead, cts.Token);
        }
        catch (OperationCanceledException)
        {
            throw new ApiError(ApiErrorKind.HttpError, "Request timed out (30s)");
        }

        var statusCode = (int)response.StatusCode;
        var responseBody = await response.Content.ReadAsStringAsync();

        // Collect headers for tracing
        var requestHeaders = CollectHeaders(request.Headers);
        var responseHeaders = CollectHeaders(response.Headers);

        await ApiTracer.Instance.TraceIfEnabledAsync(
            url: requestUrl,
            method: "POST",
            requestHeaders: requestHeaders,
            requestBody: requestBodyString,
            statusCode: statusCode,
            responseHeaders: responseHeaders,
            responseBody: responseBody,
            timestamp: traceTimestamp);

        if (!response.IsSuccessStatusCode)
        {
            throw new ApiError(ApiErrorKind.HttpError, $"HTTP {statusCode}: {responseBody}");
        }

        try
        {
            var decoded = JsonSerializer.Deserialize<TRes>(responseBody, JsonOptions)
                ?? throw new ApiError(ApiErrorKind.DecodingError, "Deserialized to null");
            return (decoded, statusCode);
        }
        catch (JsonException ex)
        {
            var preview = responseBody.Length > 500 ? responseBody[..500] : responseBody;
            throw new ApiError(ApiErrorKind.DecodingError, $"Deserialization error: {ex.Message}. Body: {preview}");
        }
    }

    // MARK: - GET Request

    /// <summary>
    /// Send a GET request with format-specific auth and decode the response.
    /// </summary>
    public async Task<(TRes Response, int StatusCode)> GetAsync<TRes>(
        string url,
        AppService service,
        string apiKey)
    {
        var requestUrl = BuildUrl(url, service, apiKey);

        using var request = new HttpRequestMessage(HttpMethod.Get, requestUrl);
        ApplyAuthHeaders(request, service, apiKey);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        HttpResponseMessage response;
        try
        {
            response = await Http.SendAsync(request, HttpCompletionOption.ResponseContentRead, cts.Token);
        }
        catch (OperationCanceledException)
        {
            throw new ApiError(ApiErrorKind.HttpError, "Request timed out (30s)");
        }

        var statusCode = (int)response.StatusCode;
        if (!response.IsSuccessStatusCode)
        {
            var errorBody = await response.Content.ReadAsStringAsync();
            throw new ApiError(ApiErrorKind.HttpError, $"HTTP {statusCode}: {errorBody}");
        }

        var responseBody = await response.Content.ReadAsStringAsync();

        try
        {
            var decoded = JsonSerializer.Deserialize<TRes>(responseBody, JsonOptions)
                ?? throw new ApiError(ApiErrorKind.DecodingError, "Deserialized to null");
            return (decoded, statusCode);
        }
        catch (JsonException ex)
        {
            var preview = responseBody.Length > 500 ? responseBody[..500] : responseBody;
            throw new ApiError(ApiErrorKind.DecodingError, $"Deserialization error: {ex.Message}. Body: {preview}");
        }
    }

    // MARK: - Streaming Request

    /// <summary>
    /// Send a POST request and return the HttpResponseMessage for SSE streaming.
    /// Caller is responsible for disposing the response.
    /// </summary>
    public async Task<HttpResponseMessage> StreamRequestAsync<TReq>(
        string url,
        TReq body,
        AppService service,
        string apiKey)
    {
        var requestUrl = BuildUrl(url, service, apiKey);

        var request = new HttpRequestMessage(HttpMethod.Post, requestUrl);
        ApplyAuthHeaders(request, service, apiKey);
        request.Content = new StringContent(
            JsonSerializer.Serialize(body, JsonOptions),
            Encoding.UTF8,
            "application/json");

        HttpResponseMessage response;
        try
        {
            // ResponseHeadersRead: return as soon as headers arrive; body streams from caller
            response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead);
        }
        catch (OperationCanceledException)
        {
            throw new ApiError(ApiErrorKind.StreamError, "Stream request timed out");
        }

        if (!response.IsSuccessStatusCode)
        {
            var errorBody = await response.Content.ReadAsStringAsync();
            response.Dispose();
            throw new ApiError(ApiErrorKind.HttpError, $"HTTP {(int)response.StatusCode}: {errorBody}");
        }

        return response;  // Caller owns disposal
    }

    // MARK: - Auth Helpers

    private static void ApplyAuthHeaders(HttpRequestMessage request, AppService service, string apiKey)
    {
        switch (service.ApiFormat)
        {
            case ApiFormat.Anthropic:
                request.Headers.TryAddWithoutValidation("x-api-key", apiKey);
                request.Headers.TryAddWithoutValidation("anthropic-version", "2023-06-01");
                break;
            case ApiFormat.Google:
                // Auth via ?key= query param appended in BuildUrl; no header needed
                break;
            case ApiFormat.OpenAiCompatible:
            default:
                request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", apiKey);
                break;
        }
    }

    /// <summary>
    /// For Google, appends ?key= to the URL. For all other formats returns url unchanged.
    /// </summary>
    private static string BuildUrl(string url, AppService service, string apiKey)
    {
        if (service.ApiFormat != ApiFormat.Google) return url;

        var separator = url.Contains('?') ? "&" : "?";
        return $"{url}{separator}key={Uri.EscapeDataString(apiKey)}";
    }

    private static Dictionary<string, string> CollectHeaders(HttpHeaders headers)
    {
        var result = new Dictionary<string, string>();
        foreach (var (key, values) in headers)
            result[key] = string.Join(", ", values);
        return result;
    }

    // MARK: - URL Builders

    /// <summary>
    /// Build a chat completions URL for a provider with optional custom base URL.
    /// </summary>
    public static string ChatUrl(AppService service, string? customBaseUrl = null)
    {
        var baseUrl = customBaseUrl ?? service.BaseUrl;
        var normalizedBase = baseUrl.EndsWith('/') ? baseUrl : baseUrl + "/";
        return normalizedBase + service.ChatPath;
    }

    /// <summary>
    /// Build a Gemini generateContent or streamGenerateContent URL.
    /// The ?key= param is embedded here for Gemini since the URL is constructed
    /// externally before being passed to the streaming/request methods.
    /// </summary>
    public static string GeminiGenerateUrl(string baseUrl, string model, string apiKey, bool stream = false)
    {
        var normalizedBase = baseUrl.EndsWith('/') ? baseUrl : baseUrl + "/";
        var action = stream ? "streamGenerateContent" : "generateContent";
        var url = $"{normalizedBase}v1beta/models/{model}:{action}?key={Uri.EscapeDataString(apiKey)}";
        if (stream) url += "&alt=sse";
        return url;
    }

    /// <summary>
    /// Build a models list URL for a provider, with optional custom URL override.
    /// </summary>
    public static string ModelsUrl(AppService service, string? customUrl = null)
    {
        if (!string.IsNullOrEmpty(customUrl)) return customUrl;
        if (string.IsNullOrEmpty(service.ModelsPath)) return "";
        var normalizedBase = service.BaseUrl.EndsWith('/') ? service.BaseUrl : service.BaseUrl + "/";
        return normalizedBase + service.ModelsPath;
    }
}

// MARK: - API Errors

public enum ApiErrorKind
{
    InvalidUrl,
    InvalidResponse,
    HttpError,
    DecodingError,
    NoApiKey,
    StreamError
}

public class ApiError : Exception
{
    public ApiErrorKind Kind { get; }

    public ApiError(ApiErrorKind kind, string detail = "") : base(BuildMessage(kind, detail))
    {
        Kind = kind;
    }

    private static string BuildMessage(ApiErrorKind kind, string detail) => kind switch
    {
        ApiErrorKind.InvalidUrl      => $"Invalid URL: {detail}",
        ApiErrorKind.InvalidResponse => "Invalid response from server",
        ApiErrorKind.HttpError       => detail,   // already formatted as "HTTP NNN: ..."
        ApiErrorKind.DecodingError   => detail,   // already formatted with context
        ApiErrorKind.NoApiKey        => "API key not configured",
        ApiErrorKind.StreamError     => $"Stream error: {detail}",
        _                            => detail
    };
}
