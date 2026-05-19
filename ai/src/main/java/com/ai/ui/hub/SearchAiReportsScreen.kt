package com.ai.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/** Wrapper screen reached from the AI Reports hub's "Search AI
 *  reports" button. Surfaces the four search modes that used to
 *  live in the hub's "Search" card, in escalating-cost order. */
@Composable
fun SearchAiReportsScreen(
    onNavigateBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToQuickLocalSearch: () -> Unit,
    onNavigateToLocalSearch: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToLocalSemanticSearch: () -> Unit,
    /** Master experimental-features gate. When false the
     *  "Local semantic search" item is hidden — relies on on-device
     *  LiteRT embedders which sit behind the same toggle. */
    experimentalFeatures: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            title = "Search AI reports",
            helpTopic = "search_ai_reports_screen",
            onBackClick = onNavigateBack
        )
        Spacer(modifier = Modifier.height(8.dp))
        SearchAiReportsItem(icon = "🔍", title = "Quick local search",
            onClick = onNavigateToQuickLocalSearch)
        SearchAiReportsItem(icon = "📂", title = "Extended local search",
            onClick = onNavigateToLocalSearch)
        SearchAiReportsItem(icon = "🌐", title = "Remote semantic search",
            onClick = onNavigateToSearch)
        if (experimentalFeatures) {
            SearchAiReportsItem(icon = "📱", title = "Local semantic search",
                onClick = onNavigateToLocalSemanticSearch)
        }
    }
}

@Composable
private fun SearchAiReportsItem(icon: String, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(text = icon, fontSize = 22.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
