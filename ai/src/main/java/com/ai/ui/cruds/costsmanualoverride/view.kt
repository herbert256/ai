package com.ai.ui.cruds.costsmanualoverride

import androidx.compose.runtime.Composable
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudViewPage
import com.ai.ui.shared.formatTokenPricePerMillion

@Composable
internal fun CostOverrideView(
    row: CostOverrideRow,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    CrudViewPage(
        title = "Manual cost override",
        onEdit = onEdit, onCopy = onCopy, onDelete = onDelete, onBack = onBack,
        deleteName = "${row.providerId} · ${row.model}",
        helpTopic = "cost_config"
    ) {
        CrudField("Provider", row.providerId)
        CrudField("Model", row.model)
        CrudField("Input price", formatTokenPricePerMillion(row.promptPrice))
        CrudField("Output price", formatTokenPricePerMillion(row.completionPrice))
    }
}
