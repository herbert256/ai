package com.ai.ui.cruds.framework

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/**
 * Uniform CRUD add/edit form scaffold: TitleBar + a top Create/Save
 * button (gated by [saveEnabled]) + the scrollable form [content]. Both
 * add.kt (isAdd=true → "Create") and edit.kt (isAdd=false → "Save")
 * delegate here.
 */
@Composable
fun CrudFormScaffold(
    title: String,
    isAdd: Boolean,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    onBack: () -> Unit,
    helpTopic: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        TitleBar(helpTopic = helpTopic, title = title, onBackClick = onBack)
        Button(
            onClick = onSave,
            enabled = saveEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text(if (isAdd) "Create" else "Save", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            content()
        }
    }
}
