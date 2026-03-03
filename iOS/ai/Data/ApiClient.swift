import Foundation
import os

/// URLSession-based API client for all AI provider communication.
/// Handles auth per format: Bearer (OpenAI), x-api-key (Claude), ?key= query param (Gemini).
/// Supports both standard request/response and SSE streaming.
actor ApiClient {
    static let shared = ApiClient()

    private static let logger = Logger(subsystem: "com.ai", category: "ApiClient")

    private let session: URLSession
    private let streamSession: URLSession

    private init() {
        // Standard session: 30s connect, 30s write, reasonable resource timeout
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30   // connect + write timeout
        config.timeoutIntervalForResource = 120 // overall resource timeout for non-streaming
        self.session = URLSession(configuration: config)

        // Streaming session: 30s connect, 600s resource timeout for long AI responses
        let streamConfig = URLSessionConfiguration.default
        streamConfig.timeoutIntervalForRequest = 30
        streamConfig.timeoutIntervalForResource = 600  // 10 min for streaming
        self.streamSession = URLSession(configuration: streamConfig)
    }

    // MARK: - JSON Coders

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        return e
    }()

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        return d
    }()

    // MARK: - Generic HTTP Request

    /// Send an HTTP request and decode the JSON response.
    /// Returns the decoded body, HTTP status code, and response headers.
    func sendRequest<T: Decodable>(
        url: String,
        method: String = "POST",
        headers: [String: String] = [:],
        body: Data? = nil,
        responseType: T.Type
    ) async throws -> (T, Int, [String: String]) {
        guard let requestURL = URL(string: url) else {
            throw ApiError.invalidURL(url)
        }

        var request = URLRequest(url: requestURL)
        request.httpMethod = method

        // Apply headers
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }

        // Set body if present and ensure Content-Type
        if let body = body {
            request.httpBody = body
            if request.value(forHTTPHeaderField: "Content-Type") == nil {
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            }
        }

        // Trace request timestamp
        let traceTimestamp = Date()
        let requestBodyString = body.flatMap { String(data: $0, encoding: .utf8) }

        // Execute request
        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw ApiError.invalidResponse
        }

        let statusCode = httpResponse.statusCode
        let responseHeaders = Self.extractHeaders(httpResponse)
        let responseBodyString = String(data: data, encoding: .utf8)

        // Trace response
        await ApiTracer.shared.traceIfEnabled(
            url: url,
            method: method,
            requestHeaders: request.allHTTPHeaderFields ?? [:],
            requestBody: requestBodyString,
            statusCode: statusCode,
            responseHeaders: responseHeaders,
            responseBody: responseBodyString,
            timestamp: traceTimestamp
        )

        // Check for HTTP errors before decoding
        guard (200...299).contains(statusCode) else {
            let errorBody = responseBodyString ?? "No response body"
            throw ApiError.httpError(statusCode, errorBody)
        }

        // Decode response
        do {
            let decoded = try Self.decoder.decode(T.self, from: data)
            return (decoded, statusCode, responseHeaders)
        } catch {
            let bodyStr = responseBodyString ?? "Unknown"
            Self.logger.error("Decode error for \(url): \(error.localizedDescription). Body: \(bodyStr.prefix(500))")
            throw ApiError.decodingError(error, bodyStr)
        }
    }

    // MARK: - Raw Send (returns Data + status + headers)

    /// Send an HTTP request and return the raw response data, status code, and headers.
    /// Useful when you need to inspect the response before deciding how to decode it,
    /// or when handling error bodies separately.
    func sendRawRequest(
        url: String,
        method: String = "POST",
        headers: [String: String] = [:],
        body: Data? = nil
    ) async throws -> (Data, Int, [String: String]) {
        guard let requestURL = URL(string: url) else {
            throw ApiError.invalidURL(url)
        }

        var request = URLRequest(url: requestURL)
        request.httpMethod = method

        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }

        if let body = body {
            request.httpBody = body
            if request.value(forHTTPHeaderField: "Content-Type") == nil {
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            }
        }

        let traceTimestamp = Date()
        let requestBodyString = body.flatMap { String(data: $0, encoding: .utf8) }

        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw ApiError.invalidResponse
        }

        let statusCode = httpResponse.statusCode
        let responseHeaders = Self.extractHeaders(httpResponse)

        await ApiTracer.shared.traceIfEnabled(
            url: url,
            method: method,
            requestHeaders: request.allHTTPHeaderFields ?? [:],
            requestBody: requestBodyString,
            statusCode: statusCode,
            responseHeaders: responseHeaders,
            responseBody: String(data: data, encoding: .utf8),
            timestamp: traceTimestamp
        )

        return (data, statusCode, responseHeaders)
    }

    // MARK: - SSE Streaming

    /// Send a streaming request and return an AsyncThrowingStream of SSE data lines.
    /// Each yielded string is a single SSE `data:` payload (with the "data: " prefix stripped).
    /// The stream ends when the server sends `[DONE]` or the connection closes.
    func streamSSE(
        url: String,
        method: String = "POST",
        headers: [String: String] = [:],
        body: Data? = nil
    ) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    guard let requestURL = URL(string: url) else {
                        continuation.finish(throwing: ApiError.invalidURL(url))
                        return
                    }

                    var request = URLRequest(url: requestURL)
                    request.httpMethod = method

                    for (key, value) in headers {
                        request.setValue(value, forHTTPHeaderField: key)
                    }

                    if let body = body {
                        request.httpBody = body
                        if request.value(forHTTPHeaderField: "Content-Type") == nil {
                            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                        }
                    }

                    // Use streaming session with extended timeout
                    let (bytes, response) = try await streamSession.bytes(for: request)

                    guard let httpResponse = response as? HTTPURLResponse else {
                        continuation.finish(throwing: ApiError.invalidResponse)
                        return
                    }

                    // Check for HTTP errors before streaming
                    guard (200...299).contains(httpResponse.statusCode) else {
                        // Collect error body from stream
                        var errorData = Data()
                        for try await byte in bytes {
                            errorData.append(byte)
                            if errorData.count > 4096 { break }  // cap error body size
                        }
                        let errorBody = String(data: errorData, encoding: .utf8) ?? "No response body"
                        continuation.finish(throwing: ApiError.httpError(httpResponse.statusCode, errorBody))
                        return
                    }

                    // Parse SSE lines
                    for try await line in bytes.lines {
                        if Task.isCancelled {
                            continuation.finish()
                            return
                        }

                        // Skip empty lines and SSE comments
                        let trimmed = line.trimmingCharacters(in: .whitespaces)
                        if trimmed.isEmpty || trimmed.hasPrefix(":") {
                            continue
                        }

                        // Handle data: lines
                        if trimmed.hasPrefix("data: ") || trimmed.hasPrefix("data:") {
                            let payload = trimmed.hasPrefix("data: ")
                                ? String(trimmed.dropFirst(6))
                                : String(trimmed.dropFirst(5))

                            // Check for SSE stream termination
                            if payload.trimmingCharacters(in: .whitespaces) == "[DONE]" {
                                continuation.finish()
                                return
                            }

                            continuation.yield(payload)
                        }
                        // Handle event: lines (used by Anthropic)
                        else if trimmed.hasPrefix("event: ") {
                            // Event type markers - yield as-is so callers can distinguish
                            // Anthropic sends event: message_start, content_block_delta, etc.
                            // These are typically followed by data: lines which contain the JSON
                            continue
                        }
                    }

                    // Stream ended naturally
                    continuation.finish()

                } catch {
                    if !Task.isCancelled {
                        continuation.finish(throwing: error)
                    } else {
                        continuation.finish()
                    }
                }
            }

            continuation.onTermination = { @Sendable _ in
                task.cancel()
            }
        }
    }

    // MARK: - Auth Header Builders

    /// Build auth headers for an OpenAI-compatible provider.
    static func openAiHeaders(apiKey: String) -> [String: String] {
        [
            "Authorization": "Bearer \(apiKey)",
            "Content-Type": "application/json"
        ]
    }

    /// Build auth headers for Anthropic/Claude.
    static func anthropicHeaders(apiKey: String) -> [String: String] {
        [
            "x-api-key": apiKey,
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json"
        ]
    }

    /// Build auth headers for Google Gemini.
    /// Note: Gemini uses ?key= query parameter, not headers. Returns Content-Type only.
    static func geminiHeaders() -> [String: String] {
        [
            "Content-Type": "application/json"
        ]
    }

    /// Build auth headers for a given service and API key.
    static func headersForService(_ service: AppService, apiKey: String) -> [String: String] {
        switch service.apiFormat {
        case .openaiCompatible:
            return openAiHeaders(apiKey: apiKey)
        case .anthropic:
            return anthropicHeaders(apiKey: apiKey)
        case .google:
            return geminiHeaders()
        }
    }

    // MARK: - URL Builders

    /// Normalize a base URL to ensure it ends with a trailing slash.
    static func normalizeBaseUrl(_ url: String) -> String {
        url.hasSuffix("/") ? url : "\(url)/"
    }

    /// Build a chat completions URL for a provider with optional custom base URL.
    static func chatUrl(for service: AppService, customBaseUrl: String? = nil) -> String {
        let base = normalizeBaseUrl(customBaseUrl ?? service.baseUrl)
        return base + service.chatPath
    }

    /// Build a Gemini generateContent URL.
    static func geminiGenerateUrl(baseUrl: String, model: String, apiKey: String, stream: Bool = false) -> String {
        let base = normalizeBaseUrl(baseUrl)
        let action = stream ? "streamGenerateContent" : "generateContent"
        var url = "\(base)v1beta/models/\(model):\(action)?key=\(apiKey)"
        if stream { url += "&alt=sse" }
        return url
    }

    /// Build a models list URL for a provider.
    static func modelsUrl(for service: AppService, customUrl: String? = nil) -> String {
        if let custom = customUrl, !custom.isEmpty { return custom }
        guard let modelsPath = service.modelsPath else { return "" }
        let base = normalizeBaseUrl(service.baseUrl)
        return base + modelsPath
    }

    /// Build a models list URL for Gemini (uses ?key= auth).
    static func geminiModelsUrl(baseUrl: String, apiKey: String) -> String {
        let base = normalizeBaseUrl(baseUrl)
        return "\(base)v1beta/models?key=\(apiKey)"
    }

    // MARK: - JSON Encoding Helpers

    /// Encode an Encodable value to JSON Data.
    static func encode<T: Encodable>(_ value: T) throws -> Data {
        try encoder.encode(value)
    }

    /// Decode JSON Data to a Decodable type.
    static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        try decoder.decode(type, from: data)
    }

    // MARK: - Private Helpers

    /// Extract response headers as a String dictionary.
    private static func extractHeaders(_ response: HTTPURLResponse) -> [String: String] {
        var headers: [String: String] = [:]
        for (key, value) in response.allHeaderFields {
            if let k = key as? String, let v = value as? String {
                headers[k] = v
            }
        }
        return headers
    }
}

// MARK: - API Errors

enum ApiError: LocalizedError, Sendable {
    case invalidURL(String)
    case invalidResponse
    case httpError(Int, String)
    case decodingError(Error, String)
    case noApiKey
    case streamError(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL(let url):
            return "Invalid URL: \(url)"
        case .invalidResponse:
            return "Invalid response from server"
        case .httpError(let code, let msg):
            return "HTTP \(code): \(msg)"
        case .decodingError(let error, let body):
            return "Decoding error: \(error.localizedDescription). Body: \(body.prefix(200))"
        case .noApiKey:
            return "API key not configured"
        case .streamError(let msg):
            return "Stream error: \(msg)"
        }
    }
}
