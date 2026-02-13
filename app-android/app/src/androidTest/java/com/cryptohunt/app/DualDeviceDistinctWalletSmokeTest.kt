package com.cryptohunt.app

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.cryptohunt.app.ui.testing.TestTags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class DualDeviceDistinctWalletSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun import_wallet_and_open_registered_game_detail() {
        val args = InstrumentationRegistry.getArguments()
        val simulationTitle = args.getString("sim_title") ?: error("Missing instrumentation argument: sim_title")
        val importPrivateKey = args.getString("import_private_key")
            ?: error("Missing instrumentation argument: import_private_key")

        handlePermissionDialogs()
        dismissSystemUiErrorDialogs()

        if (hasTag(TestTags.WELCOME_IMPORT_WALLET_BUTTON)) {
            composeRule.onNodeWithTag(TestTags.WELCOME_IMPORT_WALLET_BUTTON).performClick()
        }

        waitForTag(TestTags.WALLET_SETUP_SCREEN, timeoutMs = 30_000)
        composeRule.onNodeWithTag(TestTags.WALLET_SETUP_PRIVATE_KEY_FIELD).performTextClearance()
        composeRule.onNodeWithTag(TestTags.WALLET_SETUP_PRIVATE_KEY_FIELD).performTextInput(importPrivateKey)
        composeRule.onNodeWithTag(TestTags.WALLET_SETUP_IMPORT_KEY_BUTTON).performClick()

        waitForTag(TestTags.WALLET_SETUP_CONTINUE_BUTTON, timeoutMs = 30_000)
        composeRule.onNodeWithTag(TestTags.WALLET_SETUP_CONTINUE_BUTTON).performClick()

        if (hasTag(TestTags.PERMISSIONS_SCREEN) && hasTag(TestTags.PERMISSIONS_GRANT_BUTTON)) {
            composeRule.onNodeWithTag(TestTags.PERMISSIONS_GRANT_BUTTON).performClick()
            handlePermissionDialogs()
        }

        waitForTag(TestTags.GAME_BROWSER_SCREEN, timeoutMs = 60_000)
        waitForTextWithDialogRecovery(simulationTitle, timeoutMs = 90_000)
        composeRule.onNodeWithText(simulationTitle).performClick()

        composeRule.waitUntil(timeoutMillis = 60_000) {
            hasTag(TestTags.REGISTERED_DETAIL_SCREEN) || hasTag(TestTags.GAME_DETAIL_SCREEN)
        }
        assertTrue(
            "Expected registered detail screen for simulation wallet, but game detail screen was shown.",
            hasTag(TestTags.REGISTERED_DETAIL_SCREEN)
        )
    }

    private fun waitForTag(tag: String, timeoutMs: Long) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) { hasTag(tag) }
    }

    private fun hasTag(tag: String): Boolean {
        return composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    private fun hasText(text: String): Boolean {
        return composeRule.onAllNodesWithText(text, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    private fun handlePermissionDialogs() {
        repeat(8) {
            val clicked = clickPermissionAllowButton()
            if (!clicked) return
            SystemClock.sleep(350)
        }
    }

    private fun clickPermissionAllowButton(): Boolean {
        val allowResourceIds = listOf(
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.android.packageinstaller:id/permission_allow_button"
        )
        for (resId in allowResourceIds) {
            val button = device.findObject(By.res(resId))
            if (button != null) {
                button.click()
                return true
            }
        }

        val allowTexts = listOf(
            "Allow",
            "ALLOW",
            "Only this time",
            "While using the app",
            "Allow only while using the app"
        )
        for (text in allowTexts) {
            val button = device.findObject(By.text(text))
            if (button != null) {
                button.click()
                return true
            }
        }

        return false
    }

    private fun dismissSystemUiErrorDialogs() {
        repeat(4) {
            val error = device.findObject(By.textContains("isn't responding")) ?: return
            val waitButton = device.findObject(By.text("Wait"))
                ?: device.findObject(By.text("WAIT"))
            val closeButton = device.findObject(By.text("Close app"))
                ?: device.findObject(By.text("CLOSE APP"))
            when {
                waitButton != null -> waitButton.click()
                closeButton != null -> closeButton.click()
                else -> return
            }
            SystemClock.sleep(250)
        }
    }

    private fun waitForTextWithDialogRecovery(text: String, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            dismissSystemUiErrorDialogs()
            if (hasText(text)) return
            SystemClock.sleep(500)
        }
        error("Timed out waiting for text: $text")
    }
}
