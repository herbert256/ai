package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ModelType
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.GeneralSettings

/**
 * AI Setup → Model Types. Lets the user set a default API path for each model type.
 *
 * Resolution order at dispatch time: per-provider override (Provider Settings →
 * Definition · API → Type paths) → these defaults → ModelType.DEFAULT_PATHS
 * (the hardcoded conventional path). A blank field here means "use the hardcoded
 * default" for that type.
 */
@Composable
fun ModelTypesScreen(
    generalSettings: GeneralSettings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSave: (GeneralSettings) -> Unit
) {
    BackHandler { onBack() }

    var paths by rememberSaveable(generalSettings) {
        mutableStateOf(ModelType.ALL.associateWith { generalSettings.defaultTypePaths[it] ?: "" })
    }

    // Auto-save: whenever the user edits a field, push the trimmed map back so the
    // global ModelType.userDefaults stays in sync without needing a save button.
    LaunchedEffect(paths) {
        val cleaned = paths.mapValues { it.value.trim() }.filterValues { it.isNotBlank() }
        if (cleaned != generalSettings.defaultTypePaths) {
            onSave(generalSettings.copy(defaultTypePaths = cleaned))
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Model Types", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "Default API path per model type. Per-provider overrides win; if you " +
                "leave a field blank the built-in fallback is used.",
            fontSize = 12.sp, color = AppColors.TextTertiary
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModelType.ALL.forEach { type ->
                val current = paths[type] ?: ""
                val fallback = ModelType.DEFAULT_PATHS[type] ?: ""
                Column {
                    Text(
                        type.replaceFirstChar { it.uppercase() },
                        fontSize = 13.sp,
                        color = AppColors.TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = current,
                        onValueChange = { paths = paths + (type to it) },
                        placeholder = {
                            Text(fallback, fontSize = 12.sp, color = AppColors.TextDim)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedFieldColors()
                    )
                }
            }
        }
    }
}
