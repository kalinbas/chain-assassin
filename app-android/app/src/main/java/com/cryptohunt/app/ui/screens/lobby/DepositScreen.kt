package com.cryptohunt.app.ui.screens.lobby

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.domain.chain.ChainConfig
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepositScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val walletState by viewModel.walletState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var keyCopied by remember { mutableStateOf(false) }
    var keySavedToFile by remember { mutableStateOf(false) }

    // File saver launcher for backup before logout
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val privateKey = viewModel.getPrivateKeyHex()
            if (privateKey != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        val content = "CryptoHunt Wallet Backup\n" +
                            "========================\n" +
                            "Address: ${walletState.address}\n" +
                            "Private Key: $privateKey\n" +
                            "\n" +
                            "KEEP THIS FILE SAFE!\n" +
                            "Anyone with your private key can access your wallet.\n" +
                            "Use 'Import Wallet' to restore from this key."
                        stream.write(content.toByteArray())
                    }
                    keySavedToFile = true
                } catch (_: Exception) { }
            }
        }
    }

    // Refresh balance when screen opens
    LaunchedEffect(Unit) {
        viewModel.refreshBalance()
    }

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

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                walletState.chainName,
                style = MaterialTheme.typography.bodySmall,
                color = TextDim
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Refresh balance button
            OutlinedButton(
                onClick = {
                    isRefreshing = true
                    viewModel.refreshBalance()
                    isRefreshing = false
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Refresh Balance", fontWeight = FontWeight.Bold)
            }

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
                        text = "Switch to the Base Sepolia testnet"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    InstructionStep(
                        number = "3",
                        text = "Send ETH to your CryptoHunt address above"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    InstructionStep(
                        number = "4",
                        text = "Balance updates automatically after new blocks (or tap Refresh)"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Divider(color = DividerColor)

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "Entry fees are typically 0.001 – 0.01 ETH per game. " +
                                "Get testnet ETH from a Base Sepolia faucet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // MetaMask button — fixed chain ID to Base Sepolia (84532)
            Button(
                onClick = {
                    val metamaskUri = Uri.parse(
                        "https://metamask.app.link/send/${walletState.address}@${ChainConfig.CHAIN_ID}"
                    )
                    val intent = Intent(Intent.ACTION_VIEW, metamaskUri)
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
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
                "Opens MetaMask with your address pre-filled on Base Sepolia",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // View on explorer
            OutlinedButton(
                onClick = {
                    val uri = Uri.parse("${ChainConfig.EXPLORER_URL}/address/${walletState.address}")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary)
            ) {
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("View on BaseScan", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Divider(color = DividerColor)

            Spacer(modifier = Modifier.height(24.dp))

            // Logout button
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                border = BorderStroke(1.dp, Danger.copy(alpha = 0.5f))
            ) {
                Icon(
                    Icons.Default.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Logout", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Disconnect wallet from this device",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Logout warning dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Danger,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Logout Warning", color = TextPrimary)
                }
            },
            text = {
                Column {
                    Text(
                        "If you logout without saving your private key, " +
                            "you will permanently lose access to this wallet " +
                            "and any funds it contains.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Danger
                    )

                    Spacer(Modifier.height(16.dp))

                    Text(
                        "YOUR PRIVATE KEY",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )

                    Spacer(Modifier.height(8.dp))

                    // Private key display
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceVariant, RoundedCornerShape(8.dp))
                            .border(1.dp, DividerColor, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = viewModel.getPrivateKeyHex() ?: "unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Warning
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Copy key button
                    OutlinedButton(
                        onClick = {
                            val key = viewModel.getPrivateKeyHex()
                            if (key != null) {
                                clipboardManager.setText(AnnotatedString(key))
                                keyCopied = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (keyCopied) "Copied!" else "Copy Key")
                    }

                    Spacer(Modifier.height(8.dp))

                    // Save to file button
                    OutlinedButton(
                        onClick = {
                            saveFileLauncher.launch("cryptohunt_wallet_backup.txt")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (keySavedToFile) Primary else Warning
                        )
                    ) {
                        Icon(
                            if (keySavedToFile) Icons.Default.CheckCircle else Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (keySavedToFile) "Key Saved!" else "Save to File")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                        onLogout()
                    }
                ) {
                    Text("Logout", color = Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
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
