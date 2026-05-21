package com.ai.ui.cruds.models.manualoverrides

import androidx.compose.runtime.Composable
import com.ai.model.ModelTypeOverride
import com.ai.model.Settings

@Composable
internal fun ManualOverrideAdd(
    prefill: ModelTypeOverride?,
    aiSettings: Settings,
    onSaved: (ModelTypeOverride) -> Unit,
    onBack: () -> Unit
) = ManualOverrideForm(prefill, aiSettings, isAdd = true, onSaved, onBack)
