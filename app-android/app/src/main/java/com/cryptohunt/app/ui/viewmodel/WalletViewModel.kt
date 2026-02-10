package com.cryptohunt.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptohunt.app.domain.model.WalletState
import com.cryptohunt.app.domain.wallet.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val walletManager: WalletManager
) : ViewModel() {

    val walletState: StateFlow<WalletState> = walletManager.state

    fun createWallet() {
        walletManager.createWallet()
        refreshBalance()
    }

    fun importWallet(privateKey: String): Boolean {
        val success = walletManager.importWallet(privateKey)
        if (success) refreshBalance()
        return success
    }

    fun refreshBalance() {
        viewModelScope.launch {
            walletManager.refreshBalance()
        }
    }

    fun shortenedAddress(): String = walletManager.shortenedAddress()

    fun getPrivateKeyHex(): String? = walletManager.getPrivateKeyHex()

    fun logout() {
        walletManager.logout()
    }
}
