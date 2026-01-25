package com.eval.ui

import android.content.Intent
import android.net.Uri
import com.eval.data.PlayerInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Player info screen wrapper for navigation.
 */
@Composable
fun PlayerInfoScreenNav(
    viewModel: GameViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    PlayerInfoScreen(
        playerInfo = uiState.playerInfo,
        isLoading = uiState.playerInfoLoading,
        error = uiState.playerInfoError,
        games = uiState.playerGames,
        gamesLoading = uiState.playerGamesLoading,
        currentPage = uiState.playerGamesPage,
        pageSize = uiState.playerGamesPageSize,
        hasMoreGames = uiState.playerGamesHasMore,
        onNextPage = { viewModel.nextPlayerGamesPage() },
        onPreviousPage = { viewModel.previousPlayerGamesPage() },
        onGameSelected = { game -> viewModel.selectGameFromPlayerInfo(game) },
        onAiReportsClick = {
            uiState.playerInfo?.let { info ->
                val serverName = if (uiState.playerInfoError != null) {
                    null
                } else {
                    "lichess.org"
                }
                viewModel.showPlayerAiReportsSelectionDialog(info.username, serverName, info)
            }
        },
        hasAiApiKeys = uiState.aiSettings.hasAnyApiKey(),
        onDismiss = onNavigateBack
    )
}

/**
 * Full screen showing player information from Lichess.
 */
@Composable
fun PlayerInfoScreen(
    playerInfo: PlayerInfo?,
    isLoading: Boolean,
    error: String?,
    games: List<com.eval.data.LichessGame>,
    gamesLoading: Boolean,
    currentPage: Int,
    pageSize: Int,
    hasMoreGames: Boolean,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    onGameSelected: (com.eval.data.LichessGame) -> Unit,
    onAiReportsClick: () -> Unit,
    hasAiApiKeys: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // Handle back navigation
    BackHandler { onDismiss() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))  // Dark blue background
            .padding(16.dp)
    ) {
        // Header with username and title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = playerInfo?.username ?: "Player Info",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (playerInfo?.title != null) {
                Surface(
                    color = Color(0xFFFFD700),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = playerInfo.title,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.Black
                    )
                }
            }
        }

        // Content area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF6B9BFF),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Loading player info...",
                            color = Color(0xFFAAAAAA),
                            fontSize = 16.sp
                        )
                    }
                }
                error != null && playerInfo == null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF5252)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error,
                            color = Color(0xFFAAAAAA),
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                playerInfo != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Error message (if profile not found but we're showing minimal info)
                        if (error != null) {
                            Text(
                                text = error,
                                color = Color(0xFFFF9800),
                                fontSize = 14.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }

                        // Profile URL
                        if (playerInfo.profileUrl != null) {
                            ClickableUrlText(
                                text = playerInfo.profileUrl,
                                url = playerInfo.profileUrl,
                                context = context
                            )
                        }

                        // Real name
                        if (playerInfo.name != null) {
                            PlayerInfoRow("Name", playerInfo.name)
                        }

                        // Location/Country
                        val location = listOfNotNull(playerInfo.location, playerInfo.country)
                            .joinToString(", ")
                        if (location.isNotBlank()) {
                            PlayerInfoRow("Location", location)
                        }

                        // Bio
                        if (playerInfo.bio != null && playerInfo.bio.isNotBlank()) {
                            Text(
                                text = "Bio",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color(0xFFAAAAAA)
                            )
                            TextWithClickableUrls(
                                text = playerInfo.bio,
                                context = context
                            )
                        }

                        // Ratings section
                        val hasRatings = listOfNotNull(
                            playerInfo.bulletRating,
                            playerInfo.blitzRating,
                            playerInfo.rapidRating,
                            playerInfo.classicalRating,
                            playerInfo.dailyRating
                        ).isNotEmpty()

                        if (hasRatings) {
                            HorizontalDivider(color = Color(0xFF404040))
                            Text(
                                text = "Ratings",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                if (playerInfo.bulletRating != null) {
                                    RatingBadge("Bullet", playerInfo.bulletRating)
                                }
                                if (playerInfo.blitzRating != null) {
                                    RatingBadge("Blitz", playerInfo.blitzRating)
                                }
                                if (playerInfo.rapidRating != null) {
                                    RatingBadge("Rapid", playerInfo.rapidRating)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                if (playerInfo.classicalRating != null) {
                                    RatingBadge("Classical", playerInfo.classicalRating)
                                }
                                if (playerInfo.dailyRating != null) {
                                    RatingBadge("Daily", playerInfo.dailyRating)
                                }
                            }
                        }

                        // Game statistics section
                        val hasStats = playerInfo.totalGames != null ||
                                playerInfo.wins != null ||
                                playerInfo.losses != null ||
                                playerInfo.draws != null

                        if (hasStats) {
                            HorizontalDivider(color = Color(0xFF404040))
                            Text(
                                text = "Game Statistics",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                if (playerInfo.totalGames != null) {
                                    StatBadge("Total", playerInfo.totalGames.toString(), Color(0xFF64B5F6))
                                }
                                if (playerInfo.wins != null) {
                                    StatBadge("Wins", playerInfo.wins.toString(), Color(0xFF00E676))
                                }
                                if (playerInfo.losses != null) {
                                    StatBadge("Losses", playerInfo.losses.toString(), Color(0xFFFF5252))
                                }
                                if (playerInfo.draws != null) {
                                    StatBadge("Draws", playerInfo.draws.toString(), Color(0xFF90A4AE))
                                }
                            }
                        }

                        // Play time
                        if (playerInfo.playTimeSeconds != null) {
                            val seconds = playerInfo.playTimeSeconds
                            val hours = seconds / 3600
                            val days = hours / 24
                            val playTimeText = when {
                                days > 0 -> "$days days ${hours % 24} hours"
                                hours > 0 -> "$hours hours"
                                else -> "${seconds / 60} minutes"
                            }
                            PlayerInfoRow("Play Time", playTimeText)
                        }

                        // Account info
                        HorizontalDivider(color = Color(0xFF404040))
                        Text(
                            text = "Account",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )

                        // Online status
                        if (playerInfo.online != null) {
                            val online = playerInfo.online
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(
                                            if (online) Color(0xFF00E676) else Color(0xFF757575),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (online) "Online" else "Offline",
                                    fontSize = 16.sp,
                                    color = if (online) Color(0xFF00E676) else Color(0xFF757575)
                                )
                            }
                        }

                        // Streamer status
                        if (playerInfo.isStreamer == true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "📺",
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Streamer",
                                    fontSize = 16.sp,
                                    color = Color(0xFFE040FB) // Purple for streamers
                                )
                            }
                        }

                        // Created date
                        if (playerInfo.createdAt != null) {
                            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                .format(java.util.Date(playerInfo.createdAt))
                            PlayerInfoRow("Member Since", date)
                        }

                        // Last seen
                        if (playerInfo.lastOnline != null) {
                            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(playerInfo.lastOnline))
                            PlayerInfoRow("Last Seen", date)
                        }

                        // Followers
                        if (playerInfo.followers != null) {
                            PlayerInfoRow("Followers", playerInfo.followers.toString())
                        }

                        // Games section
                        HorizontalDivider(color = Color(0xFF404040))
                        Text(
                            text = "Recent Games",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )

                        // Only show full loading indicator on initial load (when games is empty)
                        if (gamesLoading && games.isEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFF6B9BFF),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Loading games...",
                                    color = Color(0xFFAAAAAA),
                                    fontSize = 14.sp
                                )
                            }
                        } else if (games.isEmpty() && !gamesLoading) {
                            Text(
                                text = "No games found",
                                color = Color(0xFFAAAAAA),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            // Games table
                            val startIndex = currentPage * pageSize
                            val endIndex = minOf(startIndex + pageSize, games.size)
                            val currentGames = games.subList(startIndex, endIndex)
                            val totalPages = (games.size + pageSize - 1) / pageSize

                            // Table header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White)
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Opponent",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.Black,
                                    modifier = Modifier.weight(1.5f)
                                )
                                Text(
                                    text = "Format",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.Black,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "Result",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.Black,
                                    modifier = Modifier.weight(0.8f),
                                    textAlign = TextAlign.Center
                                )
                            }

                            // Table rows
                            currentGames.forEach { game ->
                                val isUserWhite = game.players.white.user?.name?.equals(playerInfo.username, ignoreCase = true) == true
                                val opponent = if (isUserWhite) {
                                    game.players.black.user?.name ?: "Anonymous"
                                } else {
                                    game.players.white.user?.name ?: "Anonymous"
                                }
                                val format = buildString {
                                    append(game.speed.replaceFirstChar { it.uppercase() })
                                    game.clock?.let { clock ->
                                        val minutes = clock.initial / 60
                                        val increment = clock.increment
                                        append(" $minutes+$increment")
                                    }
                                }
                                val (resultText, resultColor) = when {
                                    game.winner == "white" && isUserWhite -> "Won" to Color(0xFF00E676)
                                    game.winner == "black" && !isUserWhite -> "Won" to Color(0xFF00E676)
                                    game.winner == "white" && !isUserWhite -> "Lost" to Color(0xFFFF5252)
                                    game.winner == "black" && isUserWhite -> "Lost" to Color(0xFFFF5252)
                                    game.winner == null -> "Draw" to Color(0xFF90A4AE)
                                    else -> "?" to Color(0xFFAAAAAA)
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onGameSelected(game) }
                                        .background(Color(0xFF2D2D4A))
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = opponent,
                                        fontSize = 13.sp,
                                        color = Color.White,
                                        modifier = Modifier.weight(1.5f),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = format,
                                        fontSize = 12.sp,
                                        color = Color(0xFFAAAAAA),
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.Center,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = resultText,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = resultColor,
                                        modifier = Modifier.weight(0.8f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            // Pagination controls - show if we have more than one page or there might be more to fetch
                            val nextPageStartIndex = (currentPage + 1) * pageSize
                            val canGoNext = nextPageStartIndex < games.size || hasMoreGames
                            val showPagination = games.size > pageSize || hasMoreGames || currentPage > 0

                            if (showPagination) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Previous button
                                    if (currentPage > 0) {
                                        TextButton(onClick = onPreviousPage) {
                                            Text(
                                                text = "← Previous $pageSize",
                                                color = Color(0xFF64B5F6),
                                                fontSize = 14.sp
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.width(1.dp))
                                    }

                                    // Page indicator
                                    Text(
                                        text = if (hasMoreGames) "Page ${currentPage + 1}" else "Page ${currentPage + 1} of $totalPages",
                                        color = Color(0xFFAAAAAA),
                                        fontSize = 12.sp
                                    )

                                    // Next button - show if there are more games in cache or more to fetch
                                    if (canGoNext) {
                                        TextButton(
                                            onClick = onNextPage,
                                            enabled = !gamesLoading
                                        ) {
                                            if (gamesLoading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    color = Color(0xFF64B5F6),
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Text(
                                                    text = "Next $pageSize →",
                                                    color = Color(0xFF64B5F6),
                                                    fontSize = 14.sp
                                                )
                                            }
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.width(1.dp))
                                    }
                                }
                            }

                            // Show count info
                            Text(
                                text = if (hasMoreGames) {
                                    "Showing ${startIndex + 1}-$endIndex (${games.size} loaded)"
                                } else {
                                    "Showing ${startIndex + 1}-$endIndex of ${games.size} games"
                                },
                                color = Color(0xFF757575),
                                fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }

                        // Add some bottom padding for scrolling
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No player information available",
                            color = Color(0xFFAAAAAA),
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }

        // AI reports about player button (only show if we have API keys and player info)
        if (hasAiApiKeys && playerInfo != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAiReportsClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8B5CF6)  // Purple color for AI
                )
            ) {
                Text(
                    text = "AI reports about player",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }

        // Close button at the bottom
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3A5A7C)
            )
        ) {
            Text(
                text = "Close",
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

/**
 * Clickable URL text that opens the URL in Chrome when tapped.
 */
@Composable
private fun ClickableUrlText(
    text: String,
    url: String,
    context: android.content.Context
) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = Color(0xFF64B5F6),
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            } catch (e: Exception) {
                // URL could not be opened
            }
        }
    )
}

/**
 * Text with embedded URLs that are clickable and open in Chrome.
 * Detects http:// and https:// URLs in the text and makes them clickable.
 */
@Composable
private fun TextWithClickableUrls(
    text: String,
    context: android.content.Context
) {
    val urlPattern = Regex("""(https?://[^\s]+)""")
    val matches = urlPattern.findAll(text).toList()

    if (matches.isEmpty()) {
        // No URLs found, just display plain text
        Text(
            text = text,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    } else {
        // Build annotated string with clickable URLs
        val annotatedString = buildAnnotatedString {
            var lastIndex = 0
            matches.forEach { match ->
                // Add text before the URL
                if (match.range.first > lastIndex) {
                    append(text.substring(lastIndex, match.range.first))
                }
                // Add the URL with annotation
                val url = match.value
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(
                    style = SpanStyle(
                        color = Color(0xFF64B5F6),
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(url)
                }
                pop()
                lastIndex = match.range.last + 1
            }
            // Add remaining text after the last URL
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }

        ClickableText(
            text = annotatedString,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            onClick = { offset ->
                annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()?.let { annotation ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // URL could not be opened
                        }
                    }
            }
        )
    }
}

/**
 * Row displaying a label and value for player info.
 */
@Composable
private fun PlayerInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = Color(0xFFAAAAAA)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Badge displaying a rating category and value.
 */
@Composable
private fun RatingBadge(category: String, rating: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = rating.toString(),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = Color(0xFF64B5F6)
        )
        Text(
            text = category,
            fontSize = 11.sp,
            color = Color(0xFFAAAAAA)
        )
    }
}

/**
 * Badge displaying a statistic with label and value.
 */
@Composable
private fun StatBadge(label: String, value: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = color
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color(0xFFAAAAAA)
        )
    }
}
