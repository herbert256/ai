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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Centralized color constants used across all AI screens.
 * Replaces hardcoded Color() calls for consistency and easy theming.
 */
object AppColors {
    // Primary accent colors
    val Purple = Color(0xFF8B5CF6)          // Buttons, labels, focused borders
    val Indigo = Color(0xFF6366F1)          // Secondary buttons, selection highlights
    val Blue = Color(0xFF6B9BFF)            // Links, info text, provider editor
    val Green = Color(0xFF4CAF50)           // Success, save buttons
    val Red = Color(0xFFFF6B6B)             // Delete, error, pricing
    val RedBright = Color(0xFFFF5252)       // Error text, delete buttons
    val RedDark = Color(0xFFF44336)         // Delete button backgrounds
    val Orange = Color(0xFFFF9800)          // Warnings, variable hints

    // Card and surface colors
    val SurfaceDark = Color(0xFF2A2A2A)     // Dark neutral surface / card background
    val CardBackground = Color(0xFF2A2A3A)  // Card backgrounds
    val CardBackgroundAlt = Color(0xFF2A3A4A) // Alt card background (info cards)
    val DisabledBackground = Color(0xFF1A1A1A) // Disabled card
    val IndigoHighlight = Color(0xFF2A4A3A) // Selected/default endpoint

    // Text colors
    val TextPrimary = Color.White
    val TextSecondary = Color(0xFFAAAAAA)
    val TextTertiary = Color(0xFF888888)
    val TextDim = Color(0xFF666666)
    val TextDisabled = Color(0xFF555555)
    val TextVeryDim = Color(0xFF444444)
    val TextDarkest = Color(0xFF333333)

    // Divider colors
    val DividerDark = Color(0xFF333333)     // Dark dividers and separators

    // Border colors
    val BorderFocused = Purple
    val BorderUnfocused = Color(0xFF444444)
    val BorderBlueFocused = Blue

    // Status colors
    val StatusOk = Green
    val StatusError = RedBright
    val StatusInactive = TextTertiary
    val StatusNotUsed = TextDisabled

    // Pricing display
    val PricingReal = Red
    val PricingDefault = TextDim
    val PricingBadgeBackground = Color(0xFF666666)
    val PricingBadgeText = Color(0xFF2A2A2A)

    // Success count
    val CountGreen = Color(0xFF00E676)

    /** Shared OutlinedTextField color configuration used across ~57 call sites. */
    @Composable
    fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Blue,
        unfocusedBorderColor = BorderUnfocused,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = Color.White,
        focusedLabelColor = Blue,
        unfocusedLabelColor = Color.Gray
    )
}

/**
 * Reusable delete confirmation dialog.
 * Replaces the 8+ near-identical delete dialogs across screens.
 */
@Composable
fun DeleteConfirmationDialog(
    entityType: String,
    entityName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $entityType") },
        text = { Text("Are you sure you want to delete \"$entityName\"?") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = AppColors.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Reusable list item card with title, subtitle, and delete button.
 * Replaces duplicated card components across agents, flocks, swarms, parameters, prompts screens.
 */
@Composable
fun SettingsListItemCard(
    title: String,
    subtitle: String,
    extraLine: String? = null,
    subtitleColor: Color = AppColors.TextTertiary,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = subtitleColor
                )
                if (extraLine != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = extraLine,
                        fontSize = 12.sp,
                        color = AppColors.TextDim
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Text("X", color = AppColors.Red, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Generic title bar used across all screens in the app.
 * Shows a back button on the left, optional title in the middle, and "AI" on the right.
 *
 * @param title The screen title to display (optional)
 * @param onBackClick Callback when back button is clicked (if null, no back button shown)
 * @param onAiClick Callback when "AI" text is clicked (typically navigates to home)
 * @param backText The text/icon for the back button (default: "< Back")
 * @param leftContent Optional custom content for the left side (replaces back button if provided)
 * @param centered If true, centers the "AI" text (used on home screen)
 */
@Composable
fun TitleBar(
    title: String? = null,
    onBackClick: (() -> Unit)? = null,
    onAiClick: () -> Unit = {},
    backText: String = "< Back",
    leftContent: (@Composable RowScope.() -> Unit)? = null,
    centered: Boolean = false
) {
    if (centered) {
        // Centered layout for home screen
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "AI",
                fontSize = 36.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onAiClick() }
            )
        }
    } else {
        // Standard layout with back button and right-aligned AI
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

            // Right side: "AI" branding (same style as back button)
            TextButton(onClick = onAiClick) {
                Text("AI", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}
