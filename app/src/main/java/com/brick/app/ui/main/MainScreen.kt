package com.brick.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import android.content.Intent
import com.brick.app.feature.nfc.rememberTokenScanController
import com.brick.app.core.security.RecoveryEngine
import com.brick.app.core.database.ScheduleEntity
import com.brick.app.feature.schedules.ScheduleManager
import com.brick.app.feature.schedules.ExactAlarmStatus
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.feature.home.SessionManager
import com.brick.app.theme.CardSurface
import com.brick.app.theme.DarkSurface
import com.brick.app.theme.PureBlack
import com.brick.app.theme.PureWhite
import com.brick.app.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainTab {
    HOME,
    MODES,
    SCHEDULES,
    STATS
}

@Composable
fun MainScreen(
    onNavigateOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = rememberTokenScanController()
    val sessionManager = remember { SessionManager.getInstance(context) }
    val recoveryEngine = remember { RecoveryEngine.getInstance(context) }
    val recoveryState by recoveryEngine.recoveryStateFlow.collectAsState()
    val db = remember { BrickDatabase.getInstance(context) }

    val isBricked by sessionManager.isBrickedFlow.collectAsState()
    val activeMode by sessionManager.activeMode.collectAsState()

    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    val allModes by remember(db) { db.modeDao().observeAllModes() }
        .collectAsState(initial = emptyList())
    var strictModeEnabled by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    val schedules by remember(db) { db.scheduleDao().observeAllSchedules() }
        .collectAsState(initial = emptyList())
    val liveExactAlarmStatus by ScheduleManager.exactAlarmStatus.collectAsState()
    val exactAlarmStatus = if (liveExactAlarmStatus == ExactAlarmStatus.UNKNOWN)
        ScheduleManager.lastExactAlarmStatus(context) else liveExactAlarmStatus

    // Keep the visible countdown current while the app is open. The independent
    // elapsed-realtime alarm still completes recovery if the app is backgrounded.
    LaunchedEffect(isBricked, recoveryState.isActive) {
        if (isBricked && recoveryState.isActive) {
            while (true) {
                delay(60_000L)
                withContext(Dispatchers.IO) { recoveryEngine.onTick() }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("BRICK", color = PureWhite, fontSize = 23.sp,
                fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("SETUP", color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier.clickable { onNavigateOnboarding() }.padding(12.dp))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            MainTab.entries.forEach { tab ->
                Column(Modifier.clickable { currentTab = tab }.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tab.name, color = if (currentTab == tab) PureWhite else TextMuted,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.8.sp)
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.width(32.dp).height(2.dp)
                        .background(if (currentTab == tab) PureWhite else PureBlack))
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        Box(modifier = Modifier.weight(1f)) {
            when (currentTab) {
                MainTab.HOME -> {
                    HomeContent(
                        isBricked = isBricked,
                        activeModeName = activeMode?.name ?: "Deep Work",
                        strictModeEnabled = if (isBricked) sessionManager.isStrictModeActive() else strictModeEnabled,
                        onStrictModeChanged = { if (!isBricked) strictModeEnabled = it },
                        onToggleBrick = {
                            if (isBricked) {
                                scanner.requestUnbrickScan()
                            } else {
                                scope.launch {
                                    val tokenCount = withContext(Dispatchers.IO) { db.tokenDao().getTokenCount() }
                                    val hasBlockableApp = activeMode?.packageNames?.any { pkg ->
                                        context.packageManager.getLaunchIntentForPackage(pkg) != null
                                    } == true
                                    if (tokenCount == 0) {
                                        statusMessage = "Pair a physical NFC or QR token before bricking."
                                    } else if (activeMode == null ||
                                        (activeMode?.modeType == ModeType.BLOCK_LIST && !hasBlockableApp)) {
                                        statusMessage = "Choose an installed app to block in Modes first."
                                    } else {
                                        try {
                                            sessionManager.brick(strictMode = strictModeEnabled)
                                            statusMessage = ""
                                        } catch (failure: IllegalStateException) {
                                            statusMessage = failure.message ?: "Could not start session."
                                        }
                                    }
                                }
                            }
                        },
                        onPairNfc = { scanner.requestPairNfcToken("My NFC token") },
                        onPairQr = { scanner.requestPairQrToken("My QR token") },
                        recoveryRemainingMs = if (recoveryState.isActive) recoveryState.remainingMs else null,
                        onStartRecovery = { if (isBricked) recoveryEngine.startRecovery() },
                        statusMessage = statusMessage
                    )
                }
                MainTab.MODES -> {
                    ModesContent(
                        modes = allModes,
                        activeModeId = activeMode?.id,
                        isBricked = isBricked,
                        onSelectMode = { mode ->
                            if (!isBricked) scope.launch {
                                withContext(Dispatchers.IO) {
                                    db.modeDao().setDefaultMode(mode.id)
                                    sessionManager.refreshSession()
                                }
                            }
                        },
                        onAddMode = { name, type, packages ->
                            if (!isBricked) scope.launch {
                                withContext(Dispatchers.IO) {
                                    val newMode = ModeEntity(
                                        name = name,
                                        modeType = type,
                                        packageNames = packages.toSet(),
                                        isDefault = false
                                    )
                                    val id = db.modeDao().insert(newMode)
                                    db.modeDao().setDefaultMode(id)
                                    sessionManager.refreshSession()
                                }
                            }
                        }
                    )
                }
                MainTab.SCHEDULES -> {
                    SchedulesContent(
                        schedules = schedules,
                        modes = allModes,
                        isBricked = isBricked,
                        exactAlarmStatus = exactAlarmStatus,
                        onOpenExactAlarmSettings = {
                            ScheduleManager(context).exactAlarmSettingsIntent()?.let(context::startActivity)
                        },
                        onSave = { schedule ->
                            if (!isBricked) scope.launch(Dispatchers.IO) {
                                val manager = ScheduleManager(context)
                                if (schedule.id == 0L) {
                                    val id = db.scheduleDao().insert(schedule)
                                    manager.scheduleNextAlarm(schedule.copy(id = id))
                                } else {
                                    manager.cancelAlarm(schedule.id)
                                    db.scheduleDao().update(schedule)
                                    manager.scheduleNextAlarm(schedule)
                                }
                            }
                        },
                        onEnabled = { schedule, enabled ->
                            if (!isBricked) scope.launch(Dispatchers.IO) {
                                db.scheduleDao().setEnabled(schedule.id, enabled)
                                val manager = ScheduleManager(context)
                                if (enabled) manager.scheduleNextAlarm(schedule.copy(isEnabled = true))
                                else manager.cancelAlarm(schedule.id)
                            }
                        },
                        onDelete = { schedule ->
                            if (!isBricked) scope.launch(Dispatchers.IO) {
                                ScheduleManager(context).cancelAlarm(schedule.id)
                                db.scheduleDao().delete(schedule)
                            }
                        }
                    )
                }
                MainTab.STATS -> {
                    StatsContent()
                }
            }
        }
    }
}

@Composable
fun HomeContent(
    isBricked: Boolean,
    activeModeName: String,
    strictModeEnabled: Boolean,
    onStrictModeChanged: (Boolean) -> Unit,
    onToggleBrick: () -> Unit,
    onPairNfc: () -> Unit = {},
    onPairQr: () -> Unit = {},
    recoveryRemainingMs: Long? = null,
    onStartRecovery: () -> Unit = {},
    statusMessage: String = ""
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Dominant State Indicator
        Text(
            text = if (isBricked) "BRICKED" else "UNBRICKED",
            color = PureWhite,
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Text(
                text = activeModeName.uppercase(),
                color = PureWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Large Physical Tap Button
        Box(
            modifier = Modifier
                .border(2.dp, PureWhite, RoundedCornerShape(100.dp))
                .clickable { onToggleBrick() }
                .padding(horizontal = 48.dp, vertical = 18.dp)
        ) {
            Text(
                text = if (isBricked) "TAP TO UNBRICK" else "TAP TO BRICK",
                color = PureWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = 2.sp
            )
        }

        if (!isBricked) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPairNfc) { Text("PAIR NFC") }
                Button(onClick = onPairQr) { Text("CREATE QR TOKEN") }
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            if (recoveryRemainingMs == null) {
                Button(onClick = onStartRecovery) { Text("START 24-HOUR RECOVERY") }
            } else {
                val hours = recoveryRemainingMs / 3_600_000L
                val minutes = (recoveryRemainingMs / 60_000L) % 60L
                Text("Emergency recovery: ${hours}h ${minutes}m remaining",
                    color = TextMuted, textAlign = TextAlign.Center, fontSize = 13.sp)
            }
            Text("Scan a paired token to unlock.",
                color = TextMuted, textAlign = TextAlign.Center, fontSize = 13.sp)
        }
        if (statusMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(statusMessage, color = TextMuted, textAlign = TextAlign.Center, fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.height(48.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Text(
                text = "Strict Mode (Anti-Tamper)",
                color = TextMuted,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = strictModeEnabled,
                enabled = !isBricked,
                onCheckedChange = onStrictModeChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PureBlack,
                    checkedTrackColor = PureWhite,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = DarkSurface
                )
            )
        }
    }
}

@Composable
fun ModesContent(
    modes: List<ModeEntity>,
    activeModeId: Long?,
    isBricked: Boolean = false,
    onSelectMode: (ModeEntity) -> Unit,
    onAddMode: (String, ModeType, List<String>) -> Unit
) {
    val context = LocalContext.current
    val installedApps = remember(context) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    var modeName by remember { mutableStateOf("") }
    var modeType by remember { mutableStateOf(ModeType.BLOCK_LIST) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showEditor by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("FOCUS MODES", color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        modes.forEach { mode ->
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = !isBricked) { onSelectMode(mode) }) {
                Column(Modifier.padding(14.dp)) {
                    Text(mode.name + if (mode.id == activeModeId) " • ACTIVE" else "", color = PureWhite)
                    Text("${mode.modeType.name} • ${mode.packageNames.size} apps", color = TextMuted)
                }
            }
        }
        Button(enabled = !isBricked, onClick = { showEditor = !showEditor }) { Text("ADD MODE") }
        if (showEditor && !isBricked) {
            OutlinedTextField(value = modeName, onValueChange = { modeName = it }, label = { Text("Mode name") })
            Row {
                Button(onClick = { modeType = ModeType.BLOCK_LIST }) { Text(if (modeType == ModeType.BLOCK_LIST) "✓ Block list" else "Block list") }
                Button(onClick = { modeType = ModeType.ALLOW_LIST }) { Text(if (modeType == ModeType.ALLOW_LIST) "✓ Allow list" else "Allow list") }
            }
            Text("SELECT INSTALLED APPS", color = PureWhite)
            installedApps.forEach { (pkg, label) ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        selected = if (pkg in selected) selected - pkg else selected + pkg
                    }) {
                    Checkbox(checked = pkg in selected, onCheckedChange = {
                        selected = if (it) selected + pkg else selected - pkg
                    })
                    Text(label, color = PureWhite)
                }
            }
            Button(enabled = modeName.isNotBlank() && selected.isNotEmpty(), onClick = {
                onAddMode(modeName.trim(), modeType, selected.toList())
                showEditor = false
                modeName = ""
                selected = emptySet()
            }) { Text("SAVE MODE") }
        }
    }
}
