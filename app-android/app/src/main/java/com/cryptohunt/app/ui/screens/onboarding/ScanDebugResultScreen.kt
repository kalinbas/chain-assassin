package com.cryptohunt.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptohunt.app.ui.testing.TestTags
import com.cryptohunt.app.ui.theme.Background
import com.cryptohunt.app.ui.theme.CardBackground
import com.cryptohunt.app.ui.theme.Danger
import com.cryptohunt.app.ui.theme.Primary
import com.cryptohunt.app.ui.theme.TextPrimary
import com.cryptohunt.app.ui.theme.TextSecondary
import com.cryptohunt.app.ui.viewmodel.ScanDebugViewModel

@Composable
fun ScanDebugResultScreen(
    onBackToScanner: () -> Unit,
    viewModel: ScanDebugViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TestTags.SCAN_DEBUG_RESULT_SCREEN)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Background, Color(0xFF0D0D18), Background)
                )
            )
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    viewModel.resetScan()
                    onBackToScanner()
                }
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                text = "Scan Debug Details",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Mode: ${uiState.lastScanMode?.label ?: "Unknown"}",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        uiState.lastScannedCode?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Scanned QR",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        uiState.error?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = Danger,
                fontWeight = FontWeight.Bold
            )
        }

        uiState.sentPayloadPretty?.let {
            Spacer(modifier = Modifier.height(12.dp))
            JsonCard(
                title = "Payload Sent",
                content = it
            )
        }

        uiState.serverResponsePretty?.let {
            Spacer(modifier = Modifier.height(12.dp))
            JsonCard(
                title = "Server Echo",
                content = it
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                viewModel.resetScan()
                onBackToScanner()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scan Again")
        }
    }
}

@Composable
private fun JsonCard(
    title: String,
    content: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            SelectionContainer {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextSecondary
                )
            }
        }
    }
}
