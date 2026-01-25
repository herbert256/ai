package com.eval.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eval.data.AiAnalysisResponse
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * Full-screen displaying AI analysis results for the current chess position.
 * Used when clicking on an AI service logo in manual analysis stage.
 */
@Composable
fun AiAnalysisScreen(
    serviceName: String,
    result: AiAnalysisResponse?,
    isLoading: Boolean,
    uiState: GameUiState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showEmailDialog by remember { mutableStateOf(false) }

    // Load saved email from SharedPreferences
    val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    var savedEmail by remember { mutableStateOf(prefs.getString(SettingsPreferences.KEY_AI_REPORT_EMAIL, "") ?: "") }

    // Handle back navigation
    BackHandler { onDismiss() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "$serviceName Analysis",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp)
        )

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
                            text = "Analyzing position...",
                            color = Color(0xFFAAAAAA),
                            fontSize = 16.sp
                        )
                    }
                }
                result?.error != null -> {
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
                            text = result.error,
                            color = Color(0xFFAAAAAA),
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                result?.analysis != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        MarkdownText(
                            markdown = result.analysis,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color.White,
                                fontSize = 15.sp
                            )
                        )
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No analysis available",
                            color = Color(0xFFAAAAAA),
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }

        // Action buttons
        Spacer(modifier = Modifier.height(16.dp))

        if (result?.analysis != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        openAnalysisInChrome(context, serviceName, result.analysis, uiState)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4A6A8C)
                    )
                ) {
                    Text("Browser", fontSize = 14.sp)
                }
                Button(
                    onClick = { showEmailDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4A6A8C)
                    )
                ) {
                    Text("Send by email", fontSize = 14.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Close button
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

    // Email dialog
    if (showEmailDialog && result?.analysis != null) {
        SendReportEmailDialog(
            initialEmail = savedEmail,
            onDismiss = { showEmailDialog = false },
            onSendEmail = { email ->
                // Save email for next time
                prefs.edit().putString(SettingsPreferences.KEY_AI_REPORT_EMAIL, email).apply()
                savedEmail = email
                // Send the report
                sendAnalysisReportByEmail(context, serviceName, result.analysis, uiState, email)
                showEmailDialog = false
            }
        )
    }
}

/**
 * Dialog for entering email address to send the AI analysis report.
 */
@Composable
private fun SendReportEmailDialog(
    initialEmail: String,
    onDismiss: () -> Unit,
    onSendEmail: (String) -> Unit
) {
    var email by remember { mutableStateOf(initialEmail) }
    var emailError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Send Report by Email",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Enter the email address to receive the analysis report.",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        emailError = null
                    },
                    label = { Text("Email address") },
                    singleLine = true,
                    isError = emailError != null,
                    supportingText = emailError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth()
                )

                if (initialEmail.isNotBlank() && email == initialEmail) {
                    Text(
                        text = "Using previously saved email address",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF00E676)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (email.isBlank()) {
                        emailError = "Email address is required"
                    } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                        emailError = "Invalid email address"
                    } else {
                        onSendEmail(email.trim())
                    }
                }
            ) {
                Text("Send")
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
 * Sends the AI analysis report via email as an HTML attachment.
 */
private fun sendAnalysisReportByEmail(
    context: android.content.Context,
    serviceName: String,
    markdown: String,
    uiState: GameUiState,
    email: String
) {
    try {
        // Get app version for footer
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        // Generate the HTML report
        val html = convertMarkdownToHtml(serviceName, markdown, uiState, appVersion)

        // Create cache directory for AI analysis files
        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        // Write HTML to file
        val htmlFile = java.io.File(cacheDir, "analysis_report.html")
        htmlFile.writeText(html)

        // Get content URI using FileProvider
        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            htmlFile
        )

        // Create email subject
        val game = uiState.game
        val whiteName = game?.players?.white?.user?.name ?: "White"
        val blackName = game?.players?.black?.user?.name ?: "Black"
        val subject = "Chess Analysis Report - $whiteName vs $blackName ($serviceName)"

        // Create email intent with HTML attachment
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, "Please find the $serviceName chess analysis report attached.\n\nOpen the HTML file in a browser to view the full interactive report with chessboard, evaluation graphs, and move list.")
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Send report via email"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to send email: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Converts markdown to HTML and opens it in Chrome.
 */
private fun openAnalysisInChrome(context: android.content.Context, serviceName: String, markdown: String, uiState: GameUiState) {
    try {
        // Get app version for footer
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        // Convert markdown to HTML with game data
        val html = convertMarkdownToHtml(serviceName, markdown, uiState, appVersion)

        // Create cache directory for AI analysis files
        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        // Write HTML to file
        val htmlFile = java.io.File(cacheDir, "analysis.html")
        htmlFile.writeText(html)

        // Get content URI using FileProvider
        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            htmlFile
        )

        // Create intent to open in Chrome
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "text/html")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Try to open specifically in Chrome
            setPackage("com.android.chrome")
        }

        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // Chrome not installed, try any browser
            intent.setPackage(null)
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to open in browser: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}
