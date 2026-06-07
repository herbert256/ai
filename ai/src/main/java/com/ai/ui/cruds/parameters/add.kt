package com.ai.ui.cruds.parameters

import androidx.compose.runtime.Composable
import com.ai.model.Parameters
import com.ai.model.Settings

@Composable
internal fun ParametersAdd(
    prefill: Parameters? = null,
    aiSettings: Settings,
    onSaved: (Parameters) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = ParametersEditForm(prefill, aiSettings, onSaved, onBack, onNavigateHome, forceAdd = true)
