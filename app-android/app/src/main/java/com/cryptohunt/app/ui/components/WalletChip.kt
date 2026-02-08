package com.cryptohunt.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptohunt.app.ui.theme.*

@Composable
fun WalletChip(
    shortenedAddress: String,
    balanceEth: Double,
    modifier: Modifier = Modifier
) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                "$shortenedAddress \u2022 %.3f".format(balanceEth),
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary
            )
        },
        leadingIcon = {
            Icon(
                Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Primary
            )
        },
        colors = AssistChipDefaults.assistChipColors(containerColor = SurfaceVariant),
        modifier = modifier
    )
}
