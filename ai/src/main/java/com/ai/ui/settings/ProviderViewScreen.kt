package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ViewScreenTitleBar

/**
 * Read-only "View" sibling of `ProviderSettingsScreen`. Reached by
 * tapping the provider name in the HeroCard on
 * [com.ai.ui.models.ModelInfoViewScreen]. Same visual language as
 * the rest of the View family (purple gradient hero, blue section
 * headers on dark cards). The detailed content of this screen will
 * be described by the user later — this scaffold ships the minimum
 * useful set so the navigation hop works end-to-end.
 */
@Composable
fun ProviderViewScreen(
    provider: AppService,
    onOpenManage: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            // Inherit the AI Report Title when this screen is opened
            // from a report context, same convention as
            // [com.ai.ui.models.ModelInfoViewScreen].
            reportTitle = com.ai.ui.shared.LocalReportTitle.current,
            screenTitle = "Provider",
            subject = null,
            helpTopic = "provider_view",
            onOpenManage = onOpenManage,
            onBack = onBack
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ProviderHeroCard(provider = provider) }
            item { ProviderEndpointsCard(provider = provider) }
            item { ProviderCapabilitiesCard(provider = provider) }
            item { ProviderDefaultsCard(provider = provider) }
        }
    }
}

@Composable
private fun ProviderHeroCard(provider: AppService) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        AppColors.Purple.copy(alpha = 0.32f),
                        AppColors.Indigo.copy(alpha = 0.08f)
                    )
                )
            )
            .border(1.dp, AppColors.Purple.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = "🛰", fontSize = 56.sp)
            Text(
                text = provider.id,
                fontSize = 22.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                text = provider.apiFormat.name,
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProviderEndpointsCard(provider: AppService) {
    SectionCardLocal(title = "Endpoints") {
        KvRowLocal("Base URL", provider.baseUrl.ifBlank { "—" })
        if (provider.adminUrl.isNotBlank()) KvRowLocal("Admin URL", provider.adminUrl)
        if (provider.builtInEndpoints.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Built-in endpoints (${provider.builtInEndpoints.size})",
                fontSize = 12.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.SemiBold
            )
            provider.builtInEndpoints.forEach { ep ->
                Text("• ${ep.name}", fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun ProviderCapabilitiesCard(provider: AppService) {
    SectionCardLocal(title = "Capabilities") {
        KvRowLocal("Citations", if (provider.supportsCitations) "yes" else "no")
        KvRowLocal("Search recency", if (provider.supportsSearchRecency) "yes" else "no")
        KvRowLocal("Native rerank", if (provider.nativeRerankUrl != null) "yes" else "no")
        KvRowLocal("Native moderation", if (provider.nativeModerationUrl != null) "yes" else "no")
        KvRowLocal("Cost from API", if (provider.extractApiCost) "yes" else "no")
        KvRowLocal("Pricing from /models", if (provider.pricingFromModelList) "yes" else "no")
        KvRowLocal("Cross-provider catalog", if (provider.crossProviderModelList) "yes" else "no")
    }
}

@Composable
private fun ProviderDefaultsCard(provider: AppService) {
    SectionCardLocal(title = "Defaults") {
        KvRowLocal("Default model", provider.defaultModel.ifBlank { "—" })
        provider.defaultModelSource?.takeIf { it.isNotBlank() }?.let {
            KvRowLocal("Default source", it)
        }
        provider.openRouterName?.takeIf { it.isNotBlank() }?.let {
            KvRowLocal("OpenRouter prefix", it)
        }
        provider.litellmPrefix?.takeIf { it.isNotBlank() }?.let {
            KvRowLocal("LiteLLM prefix", it)
        }
    }
}

@Composable
private fun SectionCardLocal(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
        content()
    }
}

@Composable
private fun KvRowLocal(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AppColors.TextTertiary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        Text(value, color = Color.White, fontSize = 13.sp)
    }
}
