package com.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Generic title bar used across all screens in the app.
 * Shows a back button on the left, optional title in the middle, and "AI" on the right.
 *
 * @param title The screen title to display (optional)
 * @param onBackClick Callback when back button is clicked (if null, no back button shown)
 * @param onAiClick Callback when "AI" text is clicked (typically navigates to home)
 * @param backText The text/icon for the back button (default: "< Back")
 * @param leftContent Optional custom content for the left side (replaces back button if provided)
 */
@Composable
fun AiTitleBar(
    title: String? = null,
    onBackClick: (() -> Unit)? = null,
    onAiClick: () -> Unit = {},
    backText: String = "< Back",
    leftContent: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: custom content or back button
        if (leftContent != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                leftContent()
            }
        } else if (onBackClick != null) {
            TextButton(onClick = onBackClick) {
                Text(backText, color = Color.White, fontSize = 16.sp)
            }
        } else {
            Spacer(modifier = Modifier.width(1.dp))
        }

        // Middle: title (if provided)
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
        }

        // Right side: "AI" branding
        Text(
            text = "AI",
            fontSize = 36.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.clickable { onAiClick() }
        )
    }
}

/**
 * Title bar icon button used in the Game screen.
 */
@Composable
fun TitleBarIcon(
    icon: String,
    onClick: () -> Unit,
    fontSize: Int = 30,
    size: Int = 44,
    offsetY: Int = -3
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = icon,
            fontSize = fontSize.sp,
            color = Color.White,
            modifier = Modifier.offset(y = offsetY.dp)
        )
    }
}
