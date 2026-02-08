package com.cryptohunt.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.cryptohunt.app.ui.theme.TextPrimary

@Composable
fun PlayerBadge(
    number: Int,
    modifier: Modifier = Modifier
) {
    Text(
        text = "#$number",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = TextPrimary,
        modifier = modifier
    )
}
