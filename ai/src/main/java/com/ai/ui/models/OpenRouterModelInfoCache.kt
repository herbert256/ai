package com.ai.ui.models

import com.ai.data.ApiFactory
import com.ai.data.OpenRouterModelInfo
import com.ai.data.withTraceCategory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object OpenRouterModelInfoCache {
    @Volatile private var apiKey: String? = null
    @Volatile private var openRouterModels: List<OpenRouterModelInfo>? = null
    @Volatile private var fetchedAt: Long = 0L
    private val lock = Mutex()

    private const val TTL_MS = 6L * 60 * 60 * 1000

    private fun cachedFor(apiKey: String): List<OpenRouterModelInfo>? {
        val fresh = System.currentTimeMillis() - fetchedAt < TTL_MS
        return if (this.apiKey == apiKey && fresh) openRouterModels else null
    }

    suspend fun getOpenRouterModels(apiKey: String): List<OpenRouterModelInfo> {
        if (apiKey.isBlank()) return emptyList()
        cachedFor(apiKey)?.let { return it }
        return lock.withLock {
            cachedFor(apiKey)?.let { return@withLock it }
            val api = ApiFactory.createOpenRouterModelsApi("https://openrouter.ai/api/")
            val response = withTraceCategory("info/provider") {
                api.listModelsDetailed("Bearer $apiKey")
            }
            val models = if (response.isSuccessful) response.body()?.data ?: emptyList() else emptyList()
            this.apiKey = apiKey
            openRouterModels = models
            fetchedAt = System.currentTimeMillis()
            models
        }
    }
}
