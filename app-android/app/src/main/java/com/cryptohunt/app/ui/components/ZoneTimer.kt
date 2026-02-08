package com.cryptohunt.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.util.TimeUtils

@Composable
fun ZoneTimer(
    secondsRemaining: Int,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = when {
            secondsRemaining <= 60 -> Danger
            secondsRemaining <= 120 -> Warning
            else -> TextSecondary
        },
        label = "zoneColor"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Default.RadioButtonChecked,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color
        )
        Column {
            Text("ZONE", style = MaterialTheme.typography.labelSmall, color = TextDim)
            Text(
                text = TimeUtils.formatCountdown(secondsRemaining),
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
