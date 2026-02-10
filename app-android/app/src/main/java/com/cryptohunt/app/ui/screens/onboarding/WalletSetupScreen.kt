package com.cryptohunt.app.ui.screens.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.theme.*
import com.cryptohunt.app.ui.viewmodel.WalletViewModel

@Composable
fun WalletSetupScreen(
    isImport: Boolean,
    onComplete: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val walletState by viewModel.walletState.collectAsState()
    var privateKeyInput by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf(false) }
    var importFileError by remember { mutableStateOf<String?>(null) }
    var keySaved by remember { mutableStateOf(false) }
    // Track whether user has just successfully imported in this session
    var importSuccess by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // File picker launcher for importing backup file
    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                } ?: ""
                // Parse private key from the backup file
                val keyLine = content.lines().find { it.startsWith("Private Key:") }
                if (keyLine != null) {
                    val key = keyLine.substringAfter("Private Key:").trim().removePrefix("0x")
                    if (viewModel.importWallet(key)) {
                        importFileError = null
                        importSuccess = true
                    } else {
                        importFileError = "Could not import key from file"
                    }
                } else {
                    importFileError = "No private key found in file"
                }
            } catch (_: Exception) {
                importFileError = "Could not read file"
            }
        }
    }

    // File saver launcher
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val privateKey = viewModel.getPrivateKeyHex()
            if (privateKey != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        val content = "Chain-Assassin Wallet Backup\n" +
                            "========================\n" +
                            "Address: ${walletState.address}\n" +
                            "Private Key: $privateKey\n" +
                            "\n" +
                            "KEEP THIS FILE SAFE!\n" +
                            "Anyone with your private key can access your wallet.\n" +
                            "Use 'Import Wallet' to restore from this key."
                        stream.write(content.toByteArray())
                    }
                    keySaved = true
                } catch (_: Exception) {
                    // Silently fail
                }
            }
        }
    }

    // Auto-create wallet if not importing
    LaunchedEffect(isImport) {
        if (!isImport && !walletState.isConnected) {
            viewModel.createWallet()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = if (isImport) "Import Wallet" else "Wallet Created",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isImport) "Enter your private key to connect"
            else "Your embedded wallet is ready",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        if (isImport && !importSuccess) {
            // Import from backup file
            Button(
                onClick = {
                    importFileError = null
                    openFileLauncher.launch(arrayOf("text/plain", "*/*"))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Background
                )
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Load Backup File", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (importFileError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    importFileError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = Danger
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Select the .txt file saved during wallet creation",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Divider(color = DividerColor)

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Or enter private key manually",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Manual import form
            OutlinedTextField(
                value = privateKeyInput,
                onValueChange = {
                    privateKeyInput = it
                    importError = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Private Key") },
                placeholder = { Text("0x...") },
                isError = importError,
                supportingText = if (importError) {
                    { Text("Invalid private key", color = Danger) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = TextDim,
                    focusedLabelColor = Primary,
                    cursorColor = Primary
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    val key = privateKeyInput.removePrefix("0x")
                    if (viewModel.importWallet(key)) {
                        importSuccess = true
                    } else {
                        importError = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Primary
                ),
                enabled = privateKeyInput.isNotBlank()
            ) {
                Text("Import Key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }

        // Wallet info card (shown when connected)
        AnimatedVisibility(
            visible = walletState.isConnected,
            enter = fadeIn() + expandVertically()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Success icon
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Primary
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Address card
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
                            text = "ADDRESS",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = viewModel.shortenedAddress(),
                                style = MaterialTheme.typography.titleLarge,
                                color = Primary,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(walletState.address))
                            }) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Divider(
                            modifier = Modifier.padding(vertical = 16.dp),
                            color = DividerColor
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("BALANCE", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(
                                    "%.4f ETH".format(walletState.balanceEth),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("CHAIN", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(
                                    walletState.chainName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }

                // Save private key section (only for new wallets, not imports)
                if (!isImport) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (keySaved) Primary.copy(alpha = 0.1f) else Warning.copy(alpha = 0.1f)
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (keySaved) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (keySaved) Primary else Warning
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (keySaved) "Private key saved!" else "Save your private key to restore your wallet later",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (keySaved) Primary else Warning
                                )
                            }

                            if (!keySaved) {
                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedButton(
                                    onClick = {
                                        val addr = viewModel.shortenedAddress().replace("...", "_")
                                        saveFileLauncher.launch("cryptohunt_wallet_$addr.txt")
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Warning)
                                ) {
                                    Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Save to File", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = onComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = Background
                    )
                ) {
                    Text("Continue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
