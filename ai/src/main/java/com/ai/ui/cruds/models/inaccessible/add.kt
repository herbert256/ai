package com.ai.ui.cruds.models.inaccessible

import androidx.compose.runtime.Composable
import com.ai.model.InaccessibleModel
import com.ai.model.Settings

@Composable
internal fun InaccessibleModelAdd(
    prefill: InaccessibleModel?,
    aiSettings: Settings,
    onSaved: (InaccessibleModel) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = InaccessibleModelForm(prefill, aiSettings, isAdd = true, onSaved, onBack, onNavigateHome)
