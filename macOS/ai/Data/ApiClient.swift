import Foundation
import os

/// URLSession-based API client replacing Retrofit + OkHttp.
/// Handles auth per format: Bearer (OpenAI), x-api-key (Claude), ?key= query param (Gemini).
actor ApiClient {
    static let shared = ApiClient()

    private static let logger = Logger(subsystem: "com.ai.macAI", category: "ApiClient")

    private let session: URLSession

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 600  // 10 min for long AI responses
        self.session = URLSession(configuration: config)
    }

    // MARK: - Generic Request

    /// Send a JSON request and decode the response.
    func request<Req: Encodable, Res: Decodable>(
        url: String,
        method: String = "POST",
        body: Req,
        service: AppService,
        apiKey: String,
        extraHeaders: [String: String] = [:]
    ) async throws -> (Res, HTTPURLResponse) {
        guard let requestURL = URL(string: url) else {
            throw ApiError.invalidURL(url)
        }

        var request = URLRequest(url: requestURL)
        request.httpMethod = method

        // Set auth headers based on API format
        switch service.apiFormat {
        case .anthropic:
            request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        case .google:
            // Google uses ?key= query param, handled in URL construction
            break
        case .openaiCompatible:
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }

        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        // Extra headers
        for (key, value) in extraHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }

        // Encode body
        let encoder = JSONEncoder()
        request.httpBody = try encoder.encode(body)

        // Trace request if enabled
        let traceTimestamp = Date()
        let requestBodyString = String(data: request.httpBody ?? Data(), encoding: .utf8)

        // Execute
        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw ApiError.invalidResponse
        }

        // Trace response if enabled
        await ApiTracer.shared.traceIfEnabled(
            url: url,
            method: method,
            requestHeaders: request.allHTTPHeaderFields ?? [:],
            requestBody: requestBodyString,
            statusCode: httpResponse.statusCode,
            responseHeaders: httpResponse.allHeaderFields as? [String: String] ?? [:],
            responseBody: String(data: data, encoding: .utf8),
            timestamp: traceTimestamp
        )

        // Decode response
        let decoder = JSONDecoder()
        do {
            let decoded = try decoder.decode(Res.self, from: data)
            return (decoded, httpResponse)
        } catch {
            // Try to get error message from raw response
            let bodyStr = String(data: data, encoding: .utf8) ?? "Unknown"
            Self.logger.error("Decode error for \(url): \(error.localizedDescription). Body: \(bodyStr.prefix(500))")
            throw ApiError.decodingError(error, bodyStr)
        }
    }

    // MARK: - GET Request

    func get<Res: Decodable>(
        url: String,
        service: AppService,
        apiKey: String
    ) async throws -> (Res, HTTPURLResponse) {
        guard var requestURL = URL(string: url) else {
            throw ApiError.invalidURL(url)
        }

        var request: URLRequest

        switch service.apiFormat {
        case .google:
            // Add ?key= query param for Gemini
            var components = URLComponents(url: requestURL, resolvingAgainstBaseURL: false)!
            var items = components.queryItems ?? []
            items.append(URLQueryItem(name: "key", value: apiKey))
            components.queryItems = items
            requestURL = components.url!
            request = URLRequest(url: requestURL)
        case .anthropic:
            request = URLRequest(url: requestURL)
            request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        case .openaiCompatible:
            request = URLRequest(url: requestURL)
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }

        request.httpMethod = "GET"

        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw ApiError.invalidResponse
        }

        let decoded = try JSONDecoder().decode(Res.self, from: data)
        return (decoded, httpResponse)
    }

    // MARK: - Streaming Request

    /// Send a streaming request and return raw bytes for SSE parsing.
    func streamRequest<Req: Encodable>(
        url: String,
        body: Req,
        service: AppService,
        apiKey: String
    ) async throws -> (URLSession.AsyncBytes, HTTPURLResponse) {
        guard let requestURL = URL(string: url) else {
            throw ApiError.invalidURL(url)
        }

        var request = URLRequest(url: requestURL)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        switch service.apiFormat {
        case .anthropic:
            request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        case .google:
            break
        case .openaiCompatible:
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }

        request.httpBody = try JSONEncoder().encode(body)

        let (bytes, response) = try await session.bytes(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw ApiError.invalidResponse
        }

        return (bytes, httpResponse)
    }

    // MARK: - Build URLs

    /// Build a chat completions URL for a provider with optional custom base URL.
    static func chatUrl(for service: AppService, customBaseUrl: String? = nil) -> String {
        let base = customBaseUrl ?? service.baseUrl
        let normalizedBase = base.hasSuffix("/") ? base : "\(base)/"
        return normalizedBase + service.chatPath
    }

    /// Build a Gemini URL for generate content.
    static func geminiGenerateUrl(baseUrl: String, model: String, apiKey: String, stream: Bool = false) -> String {
        let base = baseUrl.hasSuffix("/") ? baseUrl : "\(baseUrl)/"
        let action = stream ? "streamGenerateContent" : "generateContent"
        var url = "\(base)v1beta/models/\(model):\(action)?key=\(apiKey)"
        if stream { url += "&alt=sse" }
        return url
    }

    /// Build a models list URL for a provider.
    static func modelsUrl(for service: AppService, customUrl: String? = nil) -> String {
        if let custom = customUrl, !custom.isEmpty { return custom }
        guard let modelsPath = service.modelsPath else { return "" }
        let base = service.baseUrl.hasSuffix("/") ? service.baseUrl : "\(service.baseUrl)/"
        return base + modelsPath
    }
}

// MARK: - API Errors

enum ApiError: LocalizedError {
    case invalidURL(String)
    case invalidResponse
    case httpError(Int, String)
    case decodingError(Error, String)
    case noApiKey
    case streamError(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL(let url): return "Invalid URL: \(url)"
        case .invalidResponse: return "Invalid response from server"
        case .httpError(let code, let msg): return "HTTP \(code): \(msg)"
        case .decodingError(let error, let body): return "Decoding error: \(error.localizedDescription). Body: \(body.prefix(200))"
        case .noApiKey: return "API key not configured"
        case .streamError(let msg): return "Stream error: \(msg)"
        }
    }
}
