package com.cryptohunt.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptohunt.app.ui.theme.*

@Composable
fun StatusBar(
    killCount: Int,
    playersRemaining: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusItem(label = "KILLS", value = "$killCount", valueColor = Primary)
        StatusDivider()
        StatusItem(label = "ALIVE", value = "$playersRemaining", valueColor = TextPrimary)
    }
}

@Composable
private fun StatusItem(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = valueColor,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun StatusDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(32.dp)
            .padding(vertical = 4.dp)
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = DividerColor,
                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                end = androidx.compose.ui.geometry.Offset(0f, size.height),
                strokeWidth = 1f
            )
        }
    }
}
