package com.brick.app.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.brick.app.BuildConfig
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.brick.app.feature.schedules.ScheduleAlarmReceiver
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.core.database.ScheduleEntity
import org.junit.Rule
import org.junit.Test

/** Verifies home controls route to their callbacks; authorization belongs to SessionManager. */
class MainScreenTest {
  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun homeDisplaysStateAndBrickControl() {
    composeTestRule.setContent { HomeContent(false, "Deep Work", false, {}, {}) }
    composeTestRule.onNodeWithText("UNBRICKED").assertExists()
    composeTestRule.onNodeWithText("TAP TO BRICK").assertExists()
  }

  @Test
  fun installedAppRegistersScheduleAlarmReceiver() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val receiver = context.packageManager.getReceiverInfo(
      ComponentName(context, ScheduleAlarmReceiver::class.java), 0
    )
    org.junit.Assert.assertFalse("Alarm receiver should be internal", receiver.exported)
  }

  @Test
  fun normalDebugBuildDoesNotExposeE2eClockOrResetReceiver() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    org.junit.Assert.assertFalse("Normal debug build must use system time", BuildConfig.E2E_HOOKS)
    try {
      context.packageManager.getReceiverInfo(
        ComponentName(context.packageName, "com.brick.app.feature.service.SimulateTokenScanReceiver"), 0
      )
      org.junit.Assert.fail("E2E reset/clock receiver must be absent in normal debug")
    } catch (_: PackageManager.NameNotFoundException) {
      // Expected: the hook belongs to src/e2e only.
    }
  }

  @Test
  fun modesScreenOffersEditorOnlyWhileUnlocked() {
    composeTestRule.setContent {
      ModesContent(listOf(ModeEntity(id = 1, name = "Deep Work", modeType = ModeType.BLOCK_LIST,
        packageNames = setOf("com.android.chrome"))), 1, false, {}, { _, _, _ -> })
    }
    composeTestRule.onNodeWithText("ADD MODE").performClick()
    composeTestRule.onNodeWithText("Mode name").assertExists()
    composeTestRule.onNodeWithText("SELECT INSTALLED APPS").assertExists()
    composeTestRule.onNodeWithText("Block list", substring = true).assertExists()
  }

  @Test
  fun schedulesScreenDisplaysEditableRuleAndWeekdays() {
    val mode = ModeEntity(id = 1, name = "Deep Work", modeType = ModeType.BLOCK_LIST,
      packageNames = setOf("com.android.chrome"))
    val rule = ScheduleEntity(id = 1, name = "Morning", modeId = 1,
      daysOfWeek = ScheduleEntity.WEEKDAYS, startHour = 9, startMinute = 30)
    composeTestRule.setContent {
      SchedulesContent(listOf(rule), listOf(mode), onSave = {}, onEnabled = { _, _ -> }, onDelete = {})
    }
    composeTestRule.onNodeWithText("Morning", substring = true).assertExists()
    composeTestRule.onNodeWithText("EDIT").performClick()
    composeTestRule.onNodeWithText("EDIT SCHEDULE").assertExists()
    composeTestRule.onNodeWithText("Mon").assertExists()
    composeTestRule.onNodeWithText("SAVE SCHEDULE").assertExists()
  }

  @Test
  fun lockedHomeTapRequestsScanAndDoesNotOfferPairing() {
    var scans = 0
    composeTestRule.setContent { HomeContent(true, "Deep Work", false, {}, { scans++ }) }
    composeTestRule.onNodeWithText("BRICKED").assertExists()
    composeTestRule.onNodeWithText("TAP TO UNBRICK").assertExists()
    composeTestRule.onNodeWithText("TAP TO UNBRICK").performClick()
    composeTestRule.runOnIdle { org.junit.Assert.assertEquals(1, scans) }
    composeTestRule.onNodeWithText("PAIR NFC").assertDoesNotExist()
    composeTestRule.onNodeWithText("CREATE QR TOKEN").assertDoesNotExist()
    composeTestRule.onNodeWithText("START 24-HOUR RECOVERY").assertExists()
  }
}
