package com.cryptohunt.app

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.cryptohunt.app.ui.testing.TestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class OnboardingLobbyFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun onboarding_to_lobby_to_game_detail() {
        handlePermissionDialogs()

        if (hasTag(TestTags.WELCOME_CREATE_WALLET_BUTTON)) {
            composeRule.onNodeWithTag(TestTags.WELCOME_CREATE_WALLET_BUTTON).performClick()
            waitForTag(TestTags.WALLET_SETUP_CONTINUE_BUTTON, timeoutMs = 30_000)
            composeRule.onNodeWithTag(TestTags.WALLET_SETUP_CONTINUE_BUTTON).performClick()
        }

        if (hasTag(TestTags.PERMISSIONS_SCREEN) && hasTag(TestTags.PERMISSIONS_GRANT_BUTTON)) {
            composeRule.onNodeWithTag(TestTags.PERMISSIONS_GRANT_BUTTON).performClick()
            handlePermissionDialogs()
        }

        waitForTag(TestTags.GAME_BROWSER_SCREEN, timeoutMs = 90_000)
        composeRule.waitUntil(timeoutMillis = 90_000) { hasText("Downtown Warmup") }
        composeRule.onNodeWithText("Downtown Warmup").performClick()

        waitForTag(TestTags.GAME_DETAIL_SCREEN, timeoutMs = 30_000)
        composeRule.waitUntil(timeoutMillis = 30_000) {
            hasTag(TestTags.GAME_DETAIL_REGISTER_BUTTON) || hasTag(TestTags.GAME_DETAIL_VIEW_REGISTRATION_BUTTON)
        }
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
            if (!clicked) {
                return
            }
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
}
