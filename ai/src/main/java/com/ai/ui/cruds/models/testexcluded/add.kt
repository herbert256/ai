package com.ai.ui.cruds.models.testexcluded

import androidx.compose.runtime.Composable
import com.ai.model.Settings
import com.ai.model.TestExcludedModel

@Composable
internal fun TestExcludedModelAdd(
    prefill: TestExcludedModel?,
    aiSettings: Settings,
    onSaved: (TestExcludedModel) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) = TestExcludedModelForm(prefill, aiSettings, isAdd = true, onSaved, onBack, onNavigateHome)
