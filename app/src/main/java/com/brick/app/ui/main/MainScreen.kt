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
import com.brick.app.core.database.BrickSessionEntity
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.feature.home.SessionManager
import com.brick.app.feature.stats.StreakCalculator
import com.brick.app.feature.stats.StreakStats
import com.brick.app.theme.CardSurface
import com.brick.app.theme.DarkSurface
import com.brick.app.theme.PureBlack
import com.brick.app.theme.PureWhite
import com.brick.app.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BRICK",
                color = PureWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SETUP", color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    modifier = Modifier.clickable { onNavigateOnboarding() })
                Text(
                    text = "HOME",
                    color = if (currentTab == MainTab.HOME) PureWhite else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { currentTab = MainTab.HOME }
                )
                Text(
                    text = "MODES",
                    color = if (currentTab == MainTab.MODES) PureWhite else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { currentTab = MainTab.MODES }
                )
                Text(
                    text = "SCHEDULES",
                    color = if (currentTab == MainTab.SCHEDULES) PureWhite else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { currentTab = MainTab.SCHEDULES }
                )
                Text(
                    text = "STATS",
                    color = if (currentTab == MainTab.STATS) PureWhite else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { currentTab = MainTab.STATS }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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

@Composable
fun SchedulesContent(
    schedules: List<ScheduleEntity>,
    modes: List<ModeEntity>,
    isBricked: Boolean = false,
    exactAlarmStatus: ExactAlarmStatus = ExactAlarmStatus.UNKNOWN,
    onOpenExactAlarmSettings: () -> Unit = {},
    onSave: (ScheduleEntity) -> Unit,
    onEnabled: (ScheduleEntity, Boolean) -> Unit,
    onDelete: (ScheduleEntity) -> Unit
) {
    var editing by remember { mutableStateOf<ScheduleEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("09") }
    var minute by remember { mutableStateOf("00") }
    var selectedModeId by remember { mutableStateOf<Long?>(null) }
    var days by remember { mutableStateOf(ScheduleEntity.WEEKDAYS) }
    val selectedMode = if (selectedModeId == null) modes.firstOrNull()
        else modes.find { it.id == selectedModeId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("SCHEDULES", color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        when (exactAlarmStatus) {
            ExactAlarmStatus.INEXACT_FALLBACK -> {
                Text("Exact alarms are unavailable. Schedules may start late.", color = TextMuted)
                Button(onClick = onOpenExactAlarmSettings) { Text("ALLOW EXACT ALARMS") }
            }
            ExactAlarmStatus.FAILED -> Text("Alarm setup failed; schedules will not run.", color = TextMuted)
            else -> Unit
        }
        schedules.forEach { schedule ->
            Card(colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${schedule.name} • %02d:%02d".format(schedule.startHour, schedule.startMinute), color = PureWhite)
                    Text("Days: ${schedule.daysOfWeek} • Mode: ${modes.find { it.id == schedule.modeId }?.name ?: "Deleted"}", color = TextMuted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = schedule.isEnabled, enabled = !isBricked,
                            onCheckedChange = { onEnabled(schedule, it) })
                        Button(enabled = !isBricked, onClick = {
                            editing = schedule
                            name = schedule.name
                            hour = "%02d".format(schedule.startHour)
                            minute = "%02d".format(schedule.startMinute)
                            selectedModeId = schedule.modeId
                            days = schedule.daysOfWeek
                        }) { Text("EDIT") }
                        Button(enabled = !isBricked, onClick = { onDelete(schedule) }) { Text("DELETE") }
                    }
                }
            }
        }
        Text(if (editing == null) "NEW SCHEDULE" else "EDIT SCHEDULE", color = PureWhite)
        OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !isBricked,
            label = { Text("Name") })
        Row {
            OutlinedTextField(value = hour, onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                label = { Text("Hour (0-23)") }, enabled = !isBricked, modifier = Modifier.weight(1f))
            OutlinedTextField(value = minute, onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                label = { Text("Minute (0-59)") }, enabled = !isBricked, modifier = Modifier.weight(1f))
        }
        Text("MODE", color = PureWhite)
        modes.forEach { mode ->
            Button(enabled = !isBricked, onClick = { selectedModeId = mode.id }) {
                Text(if (selectedMode?.id == mode.id) "✓ ${mode.name}" else mode.name)
            }
        }
        Text("DAYS", color = PureWhite)
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { index, label ->
            val bit = 1 shl index
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = days and bit != 0, enabled = !isBricked, onCheckedChange = { checked ->
                    days = if (checked) days or bit else days and bit.inv()
                })
                Text(label, color = PureWhite)
            }
        }
        val h = hour.toIntOrNull()
        val m = minute.toIntOrNull()
        Button(enabled = !isBricked && name.isNotBlank() && selectedMode != null && h != null && h in 0..23 &&
            m != null && m in 0..59 && days != 0, onClick = {
            val previous = editing
            onSave(ScheduleEntity(id = previous?.id ?: 0L, name = name.trim(),
                modeId = selectedMode!!.id, daysOfWeek = days,
                startHour = h!!, startMinute = m!!, isEnabled = previous?.isEnabled ?: true,
                createdAt = previous?.createdAt ?: System.currentTimeMillis()))
            name = ""
            editing = null
        }) { Text("SAVE SCHEDULE") }
    }
}

@Composable
fun StatsContent() {
    val context = LocalContext.current
    val db = remember { BrickDatabase.getInstance(context) }
    var stats by remember { mutableStateOf(StreakStats(0, 0, 0L, 0)) }
    var sessions by remember { mutableStateOf<List<BrickSessionEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val completed = db.sessionDao().getAllCompletedSessions()
            stats = StreakCalculator.calculateStats(completed)
            sessions = completed
        }
    }

    val totalHours = stats.totalBrickedTimeMs / (1000 * 60 * 60)
    val totalMinutes = (stats.totalBrickedTimeMs / (1000 * 60)) % 60

    val history = remember(sessions) {
        sessions.sortedByDescending { it.startTimeMs }.take(MAX_HISTORY_ROWS)
    }
    val timestampFormat = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "FOCUS STATS",
            color = PureWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("CURRENT STREAK", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${stats.currentStreakDays} DAYS", color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.Black)
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("LONGEST STREAK", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${stats.longestStreakDays} DAYS", color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("TOTAL TIME BRICKED", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("${totalHours}h ${totalMinutes}m", color = PureWhite, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Completed Sessions: ${stats.sessionCount}", color = TextMuted, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "SESSION HISTORY",
            color = PureWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (history.isEmpty()) {
            Text(
                text = "No completed sessions yet.",
                color = TextMuted,
                fontSize = 12.sp
            )
        } else {
            history.forEach { session ->
                SessionHistoryRow(session = session, timestampFormat = timestampFormat)
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (sessions.size > history.size) {
                Text(
                    text = "Showing latest ${history.size} of ${sessions.size} sessions",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

private const val MAX_HISTORY_ROWS = 20

@Composable
private fun SessionHistoryRow(
    session: BrickSessionEntity,
    timestampFormat: SimpleDateFormat
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timestampFormat.format(Date(session.startTimeMs)),
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatSessionDuration(sessionDurationMs(session)),
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = session.modeName,
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Text(
                    text = "ENDED BY ${triggerLabel(session)}",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun sessionDurationMs(session: BrickSessionEntity): Long =
    session.durationMs
        ?: session.endTimeMs?.let { (it - session.startTimeMs).coerceAtLeast(0L) }
        ?: 0L

private fun formatSessionDuration(durationMs: Long): String {
    val totalMinutes = durationMs / (1000 * 60)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        totalMinutes < 1 -> "<1m"
        hours == 0L -> "${minutes}m"
        else -> "${hours}h ${minutes}m"
    }
}

private fun triggerLabel(session: BrickSessionEntity): String =
    session.unbrickTrigger?.name?.replace('_', ' ') ?: "UNKNOWN"
