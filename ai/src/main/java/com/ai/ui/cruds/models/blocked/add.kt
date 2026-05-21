package com.ai.ui.cruds.models.blocked

import androidx.compose.runtime.Composable
import com.ai.model.BlockedModel
import com.ai.model.Settings

/** Add a new blocked model. [prefill] is set when arriving via Copy. */
@Composable
internal fun BlockedModelAdd(
    prefill: BlockedModel?,
    aiSettings: Settings,
    onSaved: (BlockedModel) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = BlockedModelForm(prefill, aiSettings, isAdd = true, onSaved, onBack, onNavigateHome)
