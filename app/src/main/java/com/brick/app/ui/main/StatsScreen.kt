package com.brick.app.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.BrickSessionEntity
import com.brick.app.feature.stats.FocusTimeline
import com.brick.app.feature.stats.StreakCalculator
import com.brick.app.feature.stats.WeekActivity
import com.brick.app.feature.stats.WeeklyActivity
import com.brick.app.ui.design.ReferenceCard
import com.brick.app.ui.design.ReferenceInk
import com.brick.app.ui.design.ReferenceMuted
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun StatsContent() {
    val context = LocalContext.current
    val db = remember(context) { BrickDatabase.getInstance(context) }
    val sessions by remember(db) { db.sessionDao().observeCompletedSessions() }
        .collectAsState(initial = emptyList())
    StatsDashboard(sessions)
}

/** Weekly activity from real, completed sessions only. No sample numbers in the empty state. */
@Composable
fun StatsDashboard(sessions: List<BrickSessionEntity>) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = System.currentTimeMillis()
        }
    }
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    var rangeOpen by rememberSaveable { mutableStateOf(false) }
    val week = remember(sessions, weekOffset, now / 60_000) { WeeklyActivity.week(sessions, weekOffset, now) }
    val stats = remember(sessions, now / 60_000) { StreakCalculator.calculateStats(sessions, now) }
    val historyFormat = remember { SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault()) }

    LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Spacer(Modifier.height(36.dp))
            Box {
                Row(
                    Modifier.clickable { rangeOpen = true }
                        .semantics { contentDescription = "Weekly Activity range" }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Weekly Activity", color = ReferenceInk, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    Chevron()
                }
                DropdownMenu(expanded = rangeOpen, onDismissRequest = { rangeOpen = false }) {
                    DropdownMenuItem(text = { Text("This week") },
                        onClick = { weekOffset = 0; rangeOpen = false })
                    DropdownMenuItem(text = { Text("Last week") },
                        onClick = { weekOffset = -1; rangeOpen = false })
                }
            }
            Text(if (week.isCurrentWeek) "THIS WEEK" else "LAST WEEK", color = ReferenceInk,
                fontSize = 14.sp, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(18.dp))
            DashedRule(Modifier.fillMaxWidth())
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
                Spacer(Modifier.height(30.dp))
                Text("Avg Brick Time", color = ReferenceMuted, fontSize = 17.sp)
                Spacer(Modifier.height(28.dp))
                val average = week.averageMs
                if (average == null) {
                    Box(Modifier.padding(start = 3.dp).width(32.dp).height(3.dp).background(ReferenceInk))
                    Spacer(Modifier.height(30.dp))
                    Text(emptyAverageMessage(week), color = ReferenceMuted, fontSize = 17.sp, lineHeight = 24.sp)
                } else {
                    Text(WeeklyActivity.compactDuration(average), color = ReferenceInk,
                        fontSize = 38.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(30.dp))
                    Text("Across ${week.completedDays} completed ${if (week.completedDays == 1) "day" else "days"}.",
                        color = ReferenceMuted, fontSize = 17.sp)
                }
                Spacer(Modifier.height(40.dp))
                WeekChart(week)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
                if (sessions.isNotEmpty()) {
                    Spacer(Modifier.height(34.dp))
                    Row(Modifier.fillMaxWidth().background(ReferenceCard, RoundedCornerShape(24.dp)).padding(18.dp)) {
                        SummaryMetric("Current streak", "${stats.currentStreakDays} days", Modifier.weight(1f))
                        SummaryMetric("Best streak", "${stats.longestStreakDays} days", Modifier.weight(1f))
                        SummaryMetric("Sessions", "${stats.sessionCount}", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(26.dp))
                    Text("Recent sessions", color = ReferenceInk, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        items(sessions.take(20), key = { it.id }) { session ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(session.modeName, color = ReferenceInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(historyFormat.format(Date(session.startTimeMs)), color = ReferenceMuted, fontSize = 13.sp)
                }
                Text(clockDuration(FocusTimeline.sessionDurationMs(session, now)),
                    color = ReferenceInk, fontSize = 15.sp)
            }
            HorizontalDivider(Modifier.padding(horizontal = 22.dp), color = ReferenceMuted.copy(alpha = 0.25f))
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

private fun emptyAverageMessage(week: WeekActivity): String = when {
    week.isCurrentWeek && (week.completedDays == 0 || week.totalMs == 0L) -> "Your first day is underway. Check back for your average."
    week.isCurrentWeek -> "No Brick time recorded yet this week."
    else -> "No Brick time was recorded last week."
}

@Composable
private fun Chevron() {
    Canvas(Modifier.size(width = 12.dp, height = 8.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(ReferenceInk, Offset(1f, 1f), Offset(size.width / 2, size.height - 1f), stroke, StrokeCap.Round)
        drawLine(ReferenceInk, Offset(size.width / 2, size.height - 1f), Offset(size.width - 1f, 1f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun DashedRule(modifier: Modifier = Modifier) {
    Canvas(modifier.height(1.dp)) {
        drawLine(ReferenceMuted.copy(alpha = 0.6f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
    }
}

/** Sunday-to-Saturday bars over a dashed grid (quarters of the axis), labelled at the top and middle lines. */
@Composable
private fun WeekChart(week: WeekActivity) {
    val axisMax = WeeklyActivity.axisMaxMs(week.maxDayMs)
    val dayFormat = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
    val summary = week.days.joinToString("; ") {
        "${dayFormat.format(Date(it.startMs))} ${WeeklyActivity.compactDuration(it.durationMs)}"
    }
    val gridLines = 5
    val plotHeight = 174.dp
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Weekly Brick time. $summary" }) {
        Box(Modifier.fillMaxWidth().height(plotHeight)) {
            Canvas(Modifier.fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                val step = size.height / (gridLines - 1)
                val reserve = 28.dp.toPx()
                val cell = (size.width - reserve) / 7f
                // Bars first so grid lines stay visible through them.
                week.days.forEachIndexed { index, day ->
                    if (day.durationMs > 0) {
                        val h = (size.height * day.durationMs / axisMax).coerceAtLeast(4.dp.toPx())
                        val barWidth = 14.dp.toPx()
                        drawRoundRect(ReferenceInk, Offset(cell * index + (cell - barWidth) / 2, size.height - h),
                            Size(barWidth, h), CornerRadius(barWidth / 2))
                    }
                }
                repeat(gridLines) { i ->
                    val y = step * i
                    drawLine(ReferenceMuted.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y),
                        1.dp.toPx(), pathEffect = dash)
                }
            }
            Text(WeeklyActivity.compactDuration(axisMax), color = ReferenceMuted, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp))
            Text(WeeklyActivity.compactDuration(axisMax / 2), color = ReferenceMuted, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopEnd).offset(y = plotHeight / 2 + 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(end = 28.dp)) {
            week.days.forEach { day ->
                val calendar = Calendar.getInstance().apply { timeInMillis = day.startMs }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayFormat.format(Date(day.startMs)).uppercase(Locale.getDefault()),
                        color = ReferenceMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text("${calendar.get(Calendar.DAY_OF_MONTH)}", color = ReferenceMuted,
                        fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = ReferenceMuted, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, color = ReferenceInk, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            textAlign = TextAlign.Start)
    }
}

internal fun clockDuration(durationMs: Long): String {
    val minutes = durationMs.coerceAtLeast(0) / 60_000
    return "%d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)
}
