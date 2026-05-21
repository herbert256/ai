package com.ai.ui.cruds.models.cooldowns

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.ModelCooldownStore
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudViewPage
import com.ai.ui.shared.AppColors

@Composable
internal fun CooldownView(
    item: ModelCooldownStore.CooldownEntry,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onNavigateToTrace: (String) -> Unit
) {
    CrudViewPage(
        title = "Cooldown",
        onEdit = onEdit, onCopy = onCopy, onDelete = onDelete, onBack = onBack,
        deleteName = "${item.providerId} / ${item.model}",
        helpTopic = "model_cooldowns_list"
    ) {
        CrudField("Provider", item.providerId)
        CrudField("Model", item.model)
        CrudField(
            "Availability",
            if (item.availableAtMs > System.currentTimeMillis())
                ModelCooldownStore.cooldownCaption(item.availableAtMs)
            else "expired"
        )
        val tf = item.traceFile
        if (ApiTracer.isTracingEnabled && tf != null) {
            Text(
                "🐞 View the 429 trace",
                color = AppColors.Blue, fontSize = 14.sp,
                modifier = Modifier.clickable { onNavigateToTrace(tf) }
            )
        }
    }
}
