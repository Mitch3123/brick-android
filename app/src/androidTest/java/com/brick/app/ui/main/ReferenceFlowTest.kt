package com.brick.app.ui.main

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.brick.app.ui.design.*
import com.brick.app.ui.onboarding.OnboardingScreen
import com.brick.app.ui.scan.TokenScannerHost
import com.brick.app.ui.scan.ScanTestTags
import com.brick.app.feature.nfc.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReferenceFlowTest {
    @get:Rule val rule = createComposeRule()
    private val apps = listOf(SelectableApp("app.calculator", "Calculator"), SelectableApp("app.camera", "Camera"))

    @Test fun appPickerSearchSelectionAndConfirmation() {
        var result: Set<String>? = null
        rule.setContent { AppSelectionSheet(apps, emptySet(), {}, { result = it }) }
        rule.onNodeWithText("Select apps to continue").assertIsNotEnabled()
        rule.onNodeWithText("Search apps").performTextInput("calc")
        rule.onNodeWithText("Found 1 app").assertExists()
        rule.onNodeWithText("Camera", substring = false).assertDoesNotExist()
        rule.onNodeWithText("Calculator").performClick()
        rule.onNodeWithText("Continue with 1 app").performClick()
        rule.runOnIdle { assertEquals(setOf("app.calculator"), result) }
    }

    @Test fun appPickerKeepsSelectionAcrossSearchAndShowsEmptyState() {
        rule.setContent { AppSelectionSheet(apps, setOf("app.calculator"), {}, {}) }
        rule.onNodeWithText("Search apps").performTextInput("no match")
        rule.onNodeWithText("No apps found. Try another search.").assertExists()
        rule.onNodeWithText("Continue with 1 app").assertIsEnabled()
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNodeWithText("Calculator").performClick()
        rule.onNodeWithText("Select apps to continue").assertIsNotEnabled()
    }

    @Test fun appPickerBackDoesNotCommitDraft() {
        var dismissed = false
        var confirmed = false
        rule.setContent { AppSelectionSheet(apps, emptySet(), { dismissed = true }, { confirmed = true }) }
        rule.onNodeWithText("Calculator").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.runOnIdle { assertTrue(dismissed); assertFalse(confirmed) }
    }

    @Test fun onboardingStartsWithModeAndReturnsFromPicker() {
        rule.setContent { OnboardingScreen({}) }
        rule.onNodeWithText("Choose apps").assertExists()
        rule.onNodeWithText("Choose apps").performClick()
        rule.onNodeWithText("Select apps").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Choose apps").assertExists()
    }

    @Test fun scanSheetCancelAndHelpDoNotAuthenticate() {
        val controller = RecordingScanner()
        rule.setContent { TokenScannerHost(controller) }
        rule.onNodeWithText("Ready to scan").assertExists()
        rule.onNodeWithTag(ScanTestTags.CAMERA_PREVIEW).assertDoesNotExist()
        rule.onNodeWithText("Having trouble scanning?").performClick()
        rule.onNodeWithText("Scan a QR code instead").assertExists()
        rule.onNodeWithText("Back to scanning").performClick()
        rule.onNodeWithTag(ScanTestTags.CANCEL).performClick()
        rule.runOnIdle { assertEquals(1, controller.dismissals); assertEquals(0, controller.scans) }
    }

    @Test fun scannerCloseDismissesWithoutReadingCamera() {
        val controller = RecordingScanner()
        rule.setContent { TokenScannerHost(controller) }
        rule.onNodeWithContentDescription("Close").performClick()
        rule.runOnIdle { assertEquals(1, controller.dismissals); assertEquals(0, controller.scans) }
    }

    @Test fun modeEditorPreservesExistingIdentity() {
        val mode = com.brick.app.core.database.ModeEntity(id = 42, name = "No Socials",
            modeType = com.brick.app.core.database.ModeType.BLOCK_LIST,
            packageNames = setOf("app.calculator"), isDefault = true, createdAt = 123L)
        var updated: com.brick.app.core.database.ModeEntity? = null
        rule.setContent { ReferenceTheme {
            ModesContent(listOf(mode), mode.id, false, {}, { _, _, _ -> }, { updated = it })
        } }
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("Edit mode").assertExists()
        rule.onNodeWithText("Save mode").performClick()
        rule.runOnIdle { assertEquals(mode, updated) }
    }

    @Test fun captureReferenceScreensWithoutChangingDevicePermissions() {
        var screen by mutableIntStateOf(0)
        val controller = RecordingScanner()
        val mode = com.brick.app.core.database.ModeEntity(id = 1, name = "No Socials",
            modeType = com.brick.app.core.database.ModeType.BLOCK_LIST, packageNames = setOf("app.calculator"))
        rule.setContent { ReferenceTheme {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier
                .fillMaxSize().background(ReferenceBackground)) {
                when (screen) {
                    0 -> SchedulesContent(emptyList(), listOf(mode), onSave = {}, onEnabled = { _, _ -> }, onDelete = {})
                    1 -> StatsDashboard(emptyList())
                    2 -> TokenScannerHost(controller)
                    3 -> ModesContent(listOf(mode), mode.id, false, {}, { _, _, _ -> })
                }
            }
        } }
        capture("schedule-empty")
        rule.onNodeWithContentDescription("Create new schedule").performClick()
        capture("schedule-editor")
        rule.onNodeWithContentDescription("Schedule name").performTextInput("Morning focus")
        rule.onNodeWithText("Save schedule").assertIsDisplayed()
        rule.onNodeWithText("Save schedule").performClick()
        rule.runOnIdle { screen = 1 }
        capture("activity")
        rule.runOnIdle { screen = 2 }
        capture("scanner")
        rule.onNodeWithTag(ScanTestTags.CANCEL).performClick()
        rule.runOnIdle { screen = 3 }
        rule.onNodeWithText("Add mode").performClick()
        capture("mode-editor")
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.getExternalFilesDir(null), "reference-screenshots").apply { mkdirs() }
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class RecordingScanner : TokenScanController {
        override val state = MutableStateFlow(TokenScanUiState(isScanning = true,
            intent = ScanIntent.AUTHENTICATE, medium = ScanMedium.ANY,
            phase = ScanPhase.WAITING_FOR_TOKEN, isNfcAvailable = true, isNfcEnabled = true))
        var dismissals = 0
        var scans = 0
        override fun dismiss() { dismissals++; state.value = TokenScanUiState() }
        override fun requestScan(medium: ScanMedium) { scans++ }
        override fun requestPairNfcToken(label: String) = Unit
        override fun requestPairQrToken(label: String) = Unit
        override fun onQrPayload(payload: String) { scans++ }
        override fun acknowledgeOutcome() = Unit
        override fun acknowledgeGeneratedQr() = Unit
    }
}
