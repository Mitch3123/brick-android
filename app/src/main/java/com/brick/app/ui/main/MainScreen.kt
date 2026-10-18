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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.feature.home.SessionManager
import com.brick.app.feature.stats.StreakCalculator
import com.brick.app.feature.stats.StreakStats
import com.brick.app.theme.CardSurface
import com.brick.app.theme.DarkGray
import com.brick.app.theme.DarkSurface
import com.brick.app.theme.LightBorder
import com.brick.app.theme.PureBlack
import com.brick.app.theme.PureWhite
import com.brick.app.theme.TextMuted
import kotlinx.coroutines.Dispatchers
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
    val sessionManager = remember { SessionManager.getInstance(context) }
    val db = remember { BrickDatabase.getInstance(context) }

    val isBricked by sessionManager.isBrickedFlow.collectAsState()
    val activeSession by sessionManager.activeSession.collectAsState()
    val activeMode by sessionManager.activeMode.collectAsState()

    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var allModes by remember { mutableStateOf<List<ModeEntity>>(emptyList()) }
    var strictModeEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(currentTab, isBricked) {
        withContext(Dispatchers.IO) {
            allModes = db.modeDao().getAllModes()
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

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                        strictModeEnabled = strictModeEnabled,
                        onStrictModeChanged = { strictModeEnabled = it },
                        onToggleBrick = {
                            scope.launch {
                                if (isBricked) {
                                    sessionManager.unbrick()
                                } else {
                                    sessionManager.brick(strictMode = strictModeEnabled)
                                }
                            }
                        }
                    )
                }
                MainTab.MODES -> {
                    ModesContent(
                        modes = allModes,
                        activeModeId = activeMode?.id,
                        onSelectMode = { mode ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    db.modeDao().setDefaultMode(mode.id)
                                    sessionManager.refreshSession()
                                }
                            }
                        },
                        onAddMode = { name, type, packages ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val newMode = ModeEntity(
                                        name = name,
                                        modeType = type,
                                        packageNames = packages.toSet(),
                                        isDefault = false
                                    )
                                    db.modeDao().insert(newMode)
                                    allModes = db.modeDao().getAllModes()
                                }
                            }
                        }
                    )
                }
                MainTab.SCHEDULES -> {
                    SchedulesContent()
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
    onToggleBrick: () -> Unit
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
    onSelectMode: (ModeEntity) -> Unit,
    onAddMode: (String, ModeType, List<String>) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "FOCUS MODES",
            color = PureWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        modes.forEach { mode ->
            val isSelected = (mode.id == activeModeId) || mode.isDefault
            Card(
                colors = CardDefaults.cardColors(containerColor = if (isSelected) DarkGray else CardSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { onSelectMode(mode) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = mode.name,
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "${mode.modeType.name} • ${mode.packageNames.size} packages",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    if (isSelected) {
                        Text(
                            text = "ACTIVE",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SchedulesContent() {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "SCHEDULES",
            color = PureWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Daily Work Focus",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "09:00 - 17:00 • Mon, Tue, Wed, Thu, Fri",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Auto-bricks at scheduled time. Unbricking requires paired token.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun StatsContent() {
    val context = LocalContext.current
    val db = remember { BrickDatabase.getInstance(context) }
    var stats by remember { mutableStateOf(StreakStats(0, 0, 0L, 0)) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val sessions = db.sessionDao().getAllCompletedSessions()
            stats = StreakCalculator.calculateStats(sessions)
        }
    }

    val totalHours = stats.totalBrickedTimeMs / (1000 * 60 * 60)
    val totalMinutes = (stats.totalBrickedTimeMs / (1000 * 60)) % 60

    Column(modifier = Modifier.fillMaxSize()) {
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
    }
}
