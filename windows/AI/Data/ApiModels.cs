using System.Text.Json;
using System.Text.Json.Serialization;

namespace AI.Data;

// MARK: - OpenAI Models

public class OpenAiMessage
{
    [JsonPropertyName("role")]
    public string Role { get; set; } = "";

    [JsonPropertyName("content")]
    public string? Content { get; set; }

    [JsonPropertyName("reasoning_content")]
    public string? ReasoningContent { get; set; }

    public OpenAiMessage() { }

    public OpenAiMessage(string role, string? content, string? reasoningContent = null)
    {
        Role = role;
        Content = content;
        ReasoningContent = reasoningContent;
    }
}

public class OpenAiRequest
{
    [JsonPropertyName("model")]
    public string Model { get; set; } = "";

    [JsonPropertyName("messages")]
    public List<OpenAiMessage> Messages { get; set; } = new();

    [JsonPropertyName("max_tokens")]
    public int? MaxTokens { get; set; }

    [JsonPropertyName("temperature")]
    public float? Temperature { get; set; }

    [JsonPropertyName("top_p")]
    public float? TopP { get; set; }

    [JsonPropertyName("top_k")]
    public int? TopK { get; set; }

    [JsonPropertyName("frequency_penalty")]
    public float? FrequencyPenalty { get; set; }

    [JsonPropertyName("presence_penalty")]
    public float? PresencePenalty { get; set; }

    [JsonPropertyName("stop")]
    public List<string>? Stop { get; set; }

    [JsonPropertyName("seed")]
    public int? Seed { get; set; }

    [JsonPropertyName("random_seed")]
    public int? RandomSeed { get; set; }

    [JsonPropertyName("response_format")]
    public OpenAiResponseFormat? ResponseFormat { get; set; }

    [JsonPropertyName("return_citations")]
    public bool? ReturnCitations { get; set; }

    [JsonPropertyName("search_recency_filter")]
    public string? SearchRecencyFilter { get; set; }

    [JsonPropertyName("search")]
    public bool? Search { get; set; }
}

public class OpenAiResponseFormat
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "text";

    public OpenAiResponseFormat() { }

    public OpenAiResponseFormat(string type = "text")
    {
        Type = type;
    }
}

public class OpenAiChoice
{
    [JsonPropertyName("message")]
    public OpenAiMessage Message { get; set; } = new();

    [JsonPropertyName("index")]
    public int Index { get; set; }
}

/// <summary>Cost object structure (used by Perplexity)</summary>
public class UsageCost
{
    [JsonPropertyName("total_cost")]
    public double? TotalCost { get; set; }
}

public class OpenAiUsage
{
    [JsonPropertyName("prompt_tokens")]
    public int? PromptTokens { get; set; }

    [JsonPropertyName("completion_tokens")]
    public int? CompletionTokens { get; set; }

    [JsonPropertyName("total_tokens")]
    public int? TotalTokens { get; set; }

    [JsonPropertyName("input_tokens")]
    public int? InputTokens { get; set; }

    [JsonPropertyName("output_tokens")]
    public int? OutputTokens { get; set; }

    [JsonPropertyName("cost")]
    [JsonConverter(typeof(FlexibleCostConverter))]
    public double? Cost { get; set; }

    [JsonPropertyName("cost_in_usd_ticks")]
    public long? CostInUsdTicks { get; set; }

    [JsonPropertyName("cost_usd")]
    public UsageCost? CostUsd { get; set; }

    /// <summary>Effective input token count (Chat Completions or Responses API)</summary>
    [JsonIgnore]
    public int? EffectiveInputTokens => InputTokens ?? PromptTokens;

    /// <summary>Effective output token count</summary>
    [JsonIgnore]
    public int? EffectiveOutputTokens => OutputTokens ?? CompletionTokens;
}

/// <summary>
/// Custom JsonConverter that deserializes a cost field that can be either a bare
/// double (e.g. 0.0012) or a JSON object with a "total_cost" key.
/// Serializes as a plain double.
/// </summary>
public class FlexibleCostConverter : JsonConverter<double?>
{
    public override double? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        if (reader.TokenType == JsonTokenType.Null)
        {
            return null;
        }

        if (reader.TokenType == JsonTokenType.Number)
        {
            return reader.GetDouble();
        }

        if (reader.TokenType == JsonTokenType.StartObject)
        {
            // Parse object looking for "total_cost"
            double? result = null;
            while (reader.Read() && reader.TokenType != JsonTokenType.EndObject)
            {
                if (reader.TokenType == JsonTokenType.PropertyName)
                {
                    string? propName = reader.GetString();
                    reader.Read();
                    if (propName == "total_cost" && reader.TokenType == JsonTokenType.Number)
                    {
                        result = reader.GetDouble();
                    }
                    else
                    {
                        reader.Skip();
                    }
                }
            }
            return result;
        }

        reader.Skip();
        return null;
    }

    public override void Write(Utf8JsonWriter writer, double? value, JsonSerializerOptions options)
    {
        if (value.HasValue)
            writer.WriteNumberValue(value.Value);
        else
            writer.WriteNullValue();
    }
}

public class SearchResult
{
    [JsonPropertyName("name")]
    public string? Name { get; set; }

    [JsonPropertyName("url")]
    public string? Url { get; set; }

    [JsonPropertyName("snippet")]
    public string? Snippet { get; set; }
}

public class OpenAiResponse
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("choices")]
    public List<OpenAiChoice>? Choices { get; set; }

    [JsonPropertyName("usage")]
    public OpenAiUsage? Usage { get; set; }

    [JsonPropertyName("error")]
    public OpenAiError? Error { get; set; }

    [JsonPropertyName("citations")]
    public List<string>? Citations { get; set; }

    [JsonPropertyName("search_results")]
    public List<SearchResult>? SearchResults { get; set; }

    [JsonPropertyName("related_questions")]
    public List<string>? RelatedQuestions { get; set; }
}

public class OpenAiError
{
    [JsonPropertyName("message")]
    public string? Message { get; set; }

    [JsonPropertyName("type")]
    public string? Type { get; set; }
}

// MARK: - OpenAI Responses API Models (for GPT-5.x and newer)

/// <summary>
/// Request for the OpenAI Responses API. The "input" field can be either a plain
/// string or an array of OpenAiResponsesInputMessage objects. Use the factory
/// methods FromString / FromMessages to construct, which populate the JsonElement
/// field appropriately. The custom converter on Input handles both cases during
/// deserialization.
/// </summary>
public class OpenAiResponsesRequest
{
    [JsonPropertyName("model")]
    public string Model { get; set; } = "";

    /// <summary>
    /// Holds either a JSON string primitive or a JSON array of input messages.
    /// Populated via the FromString / FromMessages factory methods.
    /// </summary>
    [JsonPropertyName("input")]
    public JsonElement Input { get; set; }

    [JsonPropertyName("instructions")]
    public string? Instructions { get; set; }

    private OpenAiResponsesRequest() { }

    public static OpenAiResponsesRequest FromString(string model, string input, string? instructions = null)
    {
        return new OpenAiResponsesRequest
        {
            Model = model,
            Input = JsonSerializer.SerializeToElement(input),
            Instructions = instructions
        };
    }

    public static OpenAiResponsesRequest FromMessages(
        string model,
        List<OpenAiResponsesInputMessage> messages,
        string? instructions = null)
    {
        return new OpenAiResponsesRequest
        {
            Model = model,
            Input = JsonSerializer.SerializeToElement(messages),
            Instructions = instructions
        };
    }
}

public class OpenAiResponsesStreamRequest
{
    [JsonPropertyName("model")]
    public string Model { get; set; } = "";

    [JsonPropertyName("input")]
    public List<OpenAiResponsesInputMessage> Input { get; set; } = new();

    [JsonPropertyName("instructions")]
    public string? Instructions { get; set; }

    [JsonPropertyName("stream")]
    public bool Stream { get; set; } = true;

    public OpenAiResponsesStreamRequest() { }

    public OpenAiResponsesStreamRequest(
        string model,
        List<OpenAiResponsesInputMessage> input,
        string? instructions = null,
        bool stream = true)
    {
        Model = model;
        Input = input;
        Instructions = instructions;
        Stream = stream;
    }
}

public class OpenAiResponsesInputMessage
{
    [JsonPropertyName("role")]
    public string Role { get; set; } = "";

    [JsonPropertyName("content")]
    public string Content { get; set; } = "";
}

public class OpenAiResponsesOutputContent
{
    [JsonPropertyName("type")]
    public string? Type { get; set; }

    [JsonPropertyName("text")]
    public string? Text { get; set; }
}

public class OpenAiResponsesOutputMessage
{
    [JsonPropertyName("type")]
    public string? Type { get; set; }

    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("status")]
    public string? Status { get; set; }

    [JsonPropertyName("role")]
    public string? Role { get; set; }

    [JsonPropertyName("content")]
    public List<OpenAiResponsesOutputContent>? Content { get; set; }
}

public class OpenAiResponsesApiResponse
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("status")]
    public string? Status { get; set; }

    [JsonPropertyName("error")]
    public OpenAiResponsesError? Error { get; set; }

    [JsonPropertyName("output")]
    public List<OpenAiResponsesOutputMessage>? Output { get; set; }

    [JsonPropertyName("usage")]
    public OpenAiUsage? Usage { get; set; }
}

public class OpenAiResponsesError
{
    [JsonPropertyName("message")]
    public string? Message { get; set; }

    [JsonPropertyName("type")]
    public string? Type { get; set; }

    [JsonPropertyName("code")]
    public string? Code { get; set; }
}

// MARK: - Anthropic Models

public class ClaudeMessage
{
    [JsonPropertyName("role")]
    public string Role { get; set; } = "";

    [JsonPropertyName("content")]
    public string Content { get; set; } = "";
}

public class ClaudeRequest
{
    [JsonPropertyName("model")]
    public string Model { get; set; } = "";

    [JsonPropertyName("max_tokens")]
    public int? MaxTokens { get; set; } = 4096;

    [JsonPropertyName("messages")]
    public List<ClaudeMessage> Messages { get; set; } = new();

    [JsonPropertyName("temperature")]
    public float? Temperature { get; set; }

    [JsonPropertyName("top_p")]
    public float? TopP { get; set; }

    [JsonPropertyName("top_k")]
    public int? TopK { get; set; }

    [JsonPropertyName("system")]
    public string? System { get; set; }

    [JsonPropertyName("stop_sequences")]
    public List<string>? StopSequences { get; set; }

    [JsonPropertyName("frequency_penalty")]
    public float? FrequencyPenalty { get; set; }

    [JsonPropertyName("presence_penalty")]
    public float? PresencePenalty { get; set; }

    [JsonPropertyName("seed")]
    public int? Seed { get; set; }

    [JsonPropertyName("search")]
    public bool? Search { get; set; }
}

public class ClaudeContentBlock
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "";

    [JsonPropertyName("text")]
    public string? Text { get; set; }
}

public class ClaudeUsage
{
    [JsonPropertyName("input_tokens")]
    public int? InputTokens { get; set; }

    [JsonPropertyName("output_tokens")]
    public int? OutputTokens { get; set; }

    [JsonPropertyName("cost")]
    public double? Cost { get; set; }

    [JsonPropertyName("cost_in_usd_ticks")]
    public long? CostInUsdTicks { get; set; }

    [JsonPropertyName("cost_usd")]
    public UsageCost? CostUsd { get; set; }
}

public class ClaudeResponse
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("content")]
    public List<ClaudeContentBlock>? Content { get; set; }

    [JsonPropertyName("usage")]
    public ClaudeUsage? Usage { get; set; }

    [JsonPropertyName("error")]
    public ClaudeError? Error { get; set; }
}

public class ClaudeError
{
    [JsonPropertyName("type")]
    public string? Type { get; set; }

    [JsonPropertyName("message")]
    public string? Message { get; set; }
}

// MARK: - Google Gemini Models

public class GeminiPart
{
    [JsonPropertyName("text")]
    public string Text { get; set; } = "";
}

public class GeminiContent
{
    [JsonPropertyName("parts")]
    public List<GeminiPart> Parts { get; set; } = new();

    [JsonPropertyName("role")]
    public string? Role { get; set; }

    public GeminiContent() { }

    public GeminiContent(List<GeminiPart> parts, string? role = null)
    {
        Parts = parts;
        Role = role;
    }
}

public class GeminiRequest
{
    [JsonPropertyName("contents")]
    public List<GeminiContent> Contents { get; set; } = new();

    [JsonPropertyName("generationConfig")]
    public GeminiGenerationConfig? GenerationConfig { get; set; }

    [JsonPropertyName("systemInstruction")]
    public GeminiContent? SystemInstruction { get; set; }

    public GeminiRequest() { }

    public GeminiRequest(
        List<GeminiContent> contents,
        GeminiGenerationConfig? generationConfig = null,
        GeminiContent? systemInstruction = null)
    {
        Contents = contents;
        GenerationConfig = generationConfig;
        SystemInstruction = systemInstruction;
    }
}

public class GeminiGenerationConfig
{
    [JsonPropertyName("temperature")]
    public float? Temperature { get; set; }

    [JsonPropertyName("topP")]
    public float? TopP { get; set; }

    [JsonPropertyName("topK")]
    public int? TopK { get; set; }

    [JsonPropertyName("maxOutputTokens")]
    public int? MaxOutputTokens { get; set; }

    [JsonPropertyName("stopSequences")]
    public List<string>? StopSequences { get; set; }

    [JsonPropertyName("frequencyPenalty")]
    public float? FrequencyPenalty { get; set; }

    [JsonPropertyName("presencePenalty")]
    public float? PresencePenalty { get; set; }

    [JsonPropertyName("seed")]
    public int? Seed { get; set; }
}

public class GeminiCandidate
{
    [JsonPropertyName("content")]
    public GeminiContent? Content { get; set; }
}

public class GeminiUsageMetadata
{
    [JsonPropertyName("promptTokenCount")]
    public int? PromptTokenCount { get; set; }

    [JsonPropertyName("candidatesTokenCount")]
    public int? CandidatesTokenCount { get; set; }

    [JsonPropertyName("totalTokenCount")]
    public int? TotalTokenCount { get; set; }

    [JsonPropertyName("cost")]
    public double? Cost { get; set; }

    [JsonPropertyName("cost_in_usd_ticks")]
    public long? CostInUsdTicks { get; set; }

    [JsonPropertyName("cost_usd")]
    public UsageCost? CostUsd { get; set; }
}

public class GeminiResponse
{
    [JsonPropertyName("candidates")]
    public List<GeminiCandidate>? Candidates { get; set; }

    [JsonPropertyName("usageMetadata")]
    public GeminiUsageMetadata? UsageMetadata { get; set; }

    [JsonPropertyName("error")]
    public GeminiError? Error { get; set; }
}

public class GeminiError
{
    [JsonPropertyName("code")]
    public int? Code { get; set; }

    [JsonPropertyName("message")]
    public string? Message { get; set; }

    [JsonPropertyName("status")]
    public string? Status { get; set; }
}

// MARK: - Model List Responses

public class OpenAiModelsResponse
{
    [JsonPropertyName("data")]
    public List<OpenAiModel>? Data { get; set; }
}

public class OpenAiModel
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("owned_by")]
    public string? OwnedBy { get; set; }
}

public class ClaudeModelsResponse
{
    [JsonPropertyName("data")]
    public List<ClaudeModelInfo>? Data { get; set; }
}

public class ClaudeModelInfo
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("display_name")]
    public string? DisplayName { get; set; }

    [JsonPropertyName("type")]
    public string? Type { get; set; }
}

public class GeminiModelsResponse
{
    [JsonPropertyName("models")]
    public List<GeminiModel>? Models { get; set; }
}

public class GeminiModel
{
    [JsonPropertyName("name")]
    public string? Name { get; set; }

    [JsonPropertyName("displayName")]
    public string? DisplayName { get; set; }

    [JsonPropertyName("supportedGenerationMethods")]
    public List<string>? SupportedGenerationMethods { get; set; }
}

// MARK: - Streaming Models

public class OpenAiStreamChunk
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("choices")]
    public List<StreamChoice>? Choices { get; set; }

    [JsonPropertyName("created")]
    public long? Created { get; set; }
}

public class StreamChoice
{
    [JsonPropertyName("index")]
    public int? Index { get; set; }

    [JsonPropertyName("delta")]
    public StreamDelta? Delta { get; set; }

    [JsonPropertyName("finish_reason")]
    public string? FinishReason { get; set; }
}

public class StreamDelta
{
    [JsonPropertyName("role")]
    public string? Role { get; set; }

    [JsonPropertyName("content")]
    public string? Content { get; set; }

    [JsonPropertyName("reasoning_content")]
    public string? ReasoningContent { get; set; }
}

public class ClaudeStreamEvent
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "";

    [JsonPropertyName("index")]
    public int? Index { get; set; }

    [JsonPropertyName("delta")]
    public ClaudeStreamDelta? Delta { get; set; }

    [JsonPropertyName("content_block")]
    public ClaudeStreamContentBlock? ContentBlock { get; set; }
}

public class ClaudeStreamDelta
{
    [JsonPropertyName("type")]
    public string? Type { get; set; }

    [JsonPropertyName("text")]
    public string? Text { get; set; }

    [JsonPropertyName("stop_reason")]
    public string? StopReason { get; set; }
}

public class ClaudeStreamContentBlock
{
    [JsonPropertyName("type")]
    public string? Type { get; set; }

    [JsonPropertyName("text")]
    public string? Text { get; set; }
}

public class GeminiStreamChunk
{
    [JsonPropertyName("candidates")]
    public List<GeminiStreamCandidate>? Candidates { get; set; }
}

public class GeminiStreamCandidate
{
    [JsonPropertyName("content")]
    public GeminiContent? Content { get; set; }

    [JsonPropertyName("finishReason")]
    public string? FinishReason { get; set; }
}

// MARK: - Streaming Request Models

public class OpenAiStreamRequest
{
    [JsonPropertyName("model")]
    public string Model { get; set; } = "";

    [JsonPropertyName("messages")]
    public List<OpenAiMessage> Messages { get; set; } = new();

    [JsonPropertyName("stream")]
    public bool Stream { get; set; } = true;

    [JsonPropertyName("max_tokens")]
    public int? MaxTokens { get; set; }

    [JsonPropertyName("temperature")]
    public float? Temperature { get; set; }

    [JsonPropertyName("top_p")]
    public float? TopP { get; set; }

    [JsonPropertyName("top_k")]
    public int? TopK { get; set; }

    [JsonPropertyName("frequency_penalty")]
    public float? FrequencyPenalty { get; set; }

    [JsonPropertyName("presence_penalty")]
    public float? PresencePenalty { get; set; }

    [JsonPropertyName("stop")]
    public List<string>? Stop { get; set; }

    [JsonPropertyName("seed")]
    public int? Seed { get; set; }

    [JsonPropertyName("random_seed")]
    public int? RandomSeed { get; set; }

    [JsonPropertyName("response_format")]
    public OpenAiResponseFormat? ResponseFormat { get; set; }

    [JsonPropertyName("return_citations")]
    public bool? ReturnCitations { get; set; }

    [JsonPropertyName("search_recency_filter")]
    public string? SearchRecencyFilter { get; set; }

    [JsonPropertyName("search")]
    public bool? Search { get; set; }
}

public class ClaudeStreamRequest
{
    [JsonPropertyName("model")]
    public string Model { get; set; } = "";

    [JsonPropertyName("messages")]
    public List<ClaudeMessage> Messages { get; set; } = new();

    [JsonPropertyName("stream")]
    public bool Stream { get; set; } = true;

    [JsonPropertyName("max_tokens")]
    public int MaxTokens { get; set; } = 4096;

    [JsonPropertyName("temperature")]
    public float? Temperature { get; set; }

    [JsonPropertyName("top_p")]
    public float? TopP { get; set; }

    [JsonPropertyName("top_k")]
    public int? TopK { get; set; }

    [JsonPropertyName("system")]
    public string? System { get; set; }

    [JsonPropertyName("stop_sequences")]
    public List<string>? StopSequences { get; set; }

    [JsonPropertyName("frequency_penalty")]
    public float? FrequencyPenalty { get; set; }

    [JsonPropertyName("presence_penalty")]
    public float? PresencePenalty { get; set; }

    [JsonPropertyName("seed")]
    public int? Seed { get; set; }

    [JsonPropertyName("search")]
    public bool? Search { get; set; }
}

// MARK: - OpenRouter Model Info

public class OpenRouterModelInfo
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("name")]
    public string? Name { get; set; }

    [JsonPropertyName("description")]
    public string? Description { get; set; }

    [JsonPropertyName("context_length")]
    public int? ContextLength { get; set; }

    [JsonPropertyName("pricing")]
    public OpenRouterPricing? Pricing { get; set; }

    [JsonPropertyName("top_provider")]
    public OpenRouterTopProvider? TopProvider { get; set; }

    [JsonPropertyName("architecture")]
    public OpenRouterArchitecture? Architecture { get; set; }

    [JsonPropertyName("per_request_limits")]
    public OpenRouterLimits? PerRequestLimits { get; set; }

    [JsonPropertyName("supported_parameters")]
    public List<string>? SupportedParameters { get; set; }
}

public class OpenRouterPricing
{
    [JsonPropertyName("prompt")]
    public string? Prompt { get; set; }

    [JsonPropertyName("completion")]
    public string? Completion { get; set; }

    [JsonPropertyName("image")]
    public string? Image { get; set; }

    [JsonPropertyName("request")]
    public string? Request { get; set; }
}

public class OpenRouterTopProvider
{
    [JsonPropertyName("context_length")]
    public int? ContextLength { get; set; }

    [JsonPropertyName("max_completion_tokens")]
    public int? MaxCompletionTokens { get; set; }

    [JsonPropertyName("is_moderated")]
    public bool? IsModerated { get; set; }
}

public class OpenRouterArchitecture
{
    [JsonPropertyName("modality")]
    public string? Modality { get; set; }

    [JsonPropertyName("tokenizer")]
    public string? Tokenizer { get; set; }

    [JsonPropertyName("instruct_type")]
    public string? InstructType { get; set; }
}

public class OpenRouterLimits
{
    [JsonPropertyName("prompt_tokens")]
    public int? PromptTokens { get; set; }

    [JsonPropertyName("completion_tokens")]
    public int? CompletionTokens { get; set; }
}

public class OpenRouterModelsDetailedResponse
{
    [JsonPropertyName("data")]
    public List<OpenRouterModelInfo> Data { get; set; } = new();
}

// MARK: - Hugging Face Model Info

public class HuggingFaceModelInfo
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("modelId")]
    public string? ModelId { get; set; }

    [JsonPropertyName("author")]
    public string? Author { get; set; }

    [JsonPropertyName("downloads")]
    public long? Downloads { get; set; }

    [JsonPropertyName("likes")]
    public int? Likes { get; set; }

    [JsonPropertyName("tags")]
    public List<string>? Tags { get; set; }

    [JsonPropertyName("pipeline_tag")]
    public string? PipelineTag { get; set; }

    [JsonPropertyName("library_name")]
    public string? LibraryName { get; set; }

    [JsonPropertyName("createdAt")]
    public string? CreatedAt { get; set; }

    [JsonPropertyName("lastModified")]
    public string? LastModified { get; set; }
}
