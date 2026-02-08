package com.cryptohunt.app.ui.screens.lobby

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepositScreen(
    onBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val walletState by viewModel.walletState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var withdrawAddress by remember { mutableStateOf("") }
    var showWithdrawDialog by remember { mutableStateOf(false) }
    var withdrawSuccess by remember { mutableStateOf<Double?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Wallet",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Balance display
            Icon(
                Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = Primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Current Balance",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "%.4f ETH".format(walletState.balanceEth),
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Wallet address card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "YOUR WALLET ADDRESS",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Address display
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                SurfaceVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                if (copied) Primary.copy(alpha = 0.5f) else DividerColor,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Text(
                            text = walletState.address,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = Primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Copy button
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(walletState.address))
                            copied = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceVariant,
                            contentColor = TextPrimary
                        )
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (copied) "Copied!" else "Copy Address",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Instructions card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        "HOW TO DEPOSIT",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    InstructionStep(
                        number = "1",
                        text = "Open MetaMask or any Ethereum wallet"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    InstructionStep(
                        number = "2",
                        text = "Make sure you're on the Base network"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    InstructionStep(
                        number = "3",
                        text = "Send ETH to your Chain-Assassin address above"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    InstructionStep(
                        number = "4",
                        text = "Wait for the transaction to confirm (usually ~2 seconds on Base)"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Divider(color = DividerColor)

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "Entry fees are typically 0.001 – 0.01 ETH per game. Deposit enough to cover the games you want to join.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // MetaMask button
            Button(
                onClick = {
                    val metamaskUri = Uri.parse(
                        "https://metamask.app.link/send/${walletState.address}@8453"
                    )
                    val intent = Intent(Intent.ACTION_VIEW, metamaskUri)
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        // MetaMask not installed — try Play Store
                        val storeUri = Uri.parse("https://metamask.io/download/")
                        context.startActivity(Intent(Intent.ACTION_VIEW, storeUri))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Warning,
                    contentColor = Background
                )
            ) {
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Open MetaMask",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Opens MetaMask with your address pre-filled on Base network",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Divider(color = DividerColor)

            Spacer(modifier = Modifier.height(32.dp))

            // Withdraw section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        "WITHDRAW",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        "Send your full balance to an external wallet",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = withdrawAddress,
                        onValueChange = {
                            withdrawAddress = it
                            withdrawSuccess = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Recipient Address") },
                        placeholder = { Text("0x...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = TextDim,
                            focusedLabelColor = Primary,
                            cursorColor = Primary
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { showWithdrawDialog = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Danger,
                            contentColor = TextPrimary
                        ),
                        enabled = withdrawAddress.startsWith("0x")
                                && withdrawAddress.length == 42
                                && walletState.balanceEth > 0
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Withdraw %.4f ETH".format(walletState.balanceEth),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (withdrawSuccess != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Sent %.4f ETH to ${withdrawAddress.take(6)}...${withdrawAddress.takeLast(4)}".format(withdrawSuccess),
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Withdraw confirmation dialog
    if (showWithdrawDialog) {
        AlertDialog(
            onDismissRequest = { showWithdrawDialog = false },
            title = { Text("Confirm Withdrawal") },
            text = {
                Column {
                    Text("Send your entire balance to:")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        withdrawAddress,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = Primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Amount: %.4f ETH".format(walletState.balanceEth),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showWithdrawDialog = false
                    val amount = viewModel.withdrawAll()
                    withdrawSuccess = amount
                }) {
                    Text("Withdraw", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWithdrawDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = Surface
        )
    }
}

@Composable
private fun InstructionStep(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Primary.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number,
                style = MaterialTheme.typography.labelSmall,
                color = Primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
