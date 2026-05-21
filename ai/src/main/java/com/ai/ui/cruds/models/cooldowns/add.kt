package com.ai.ui.cruds.models.cooldowns

import androidx.compose.runtime.Composable
import com.ai.data.ModelCooldownStore
import com.ai.model.Settings

@Composable
internal fun CooldownAdd(
    prefill: ModelCooldownStore.CooldownEntry?,
    aiSettings: Settings,
    onSaved: (providerId: String, model: String, untilMs: Long) -> Unit,
    onBack: () -> Unit
) = CooldownForm(prefill, aiSettings, isAdd = true, onSaved, onBack)
