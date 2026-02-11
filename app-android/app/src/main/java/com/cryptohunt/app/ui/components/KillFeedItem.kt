package com.cryptohunt.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptohunt.app.domain.model.KillEvent
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.util.TimeUtils

@Composable
fun KillFeedItem(
    event: KillEvent,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = TimeUtils.formatTimestamp(event.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = TextDim
        )
        Spacer(Modifier.width(12.dp))
        if (event.isNoCheckIn) {
            Text(
                text = "#${event.targetNumber}",
                style = MaterialTheme.typography.labelMedium,
                color = Danger,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = " no check-in",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        } else {
            Text(
                text = "#${event.hunterNumber}",
                style = MaterialTheme.typography.labelMedium,
                color = Primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = " eliminated ",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = "#${event.targetNumber}",
                style = MaterialTheme.typography.labelMedium,
                color = Danger,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.weight(1f))
        if (event.zone.isNotEmpty()) {
            Text(
                text = event.zone,
                style = MaterialTheme.typography.labelSmall,
                color = TextDim
            )
        }
    }
}
