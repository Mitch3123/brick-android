package com.brick.app.ui.main

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.BrickSessionEntity
import com.brick.app.feature.stats.FocusTimeline
import com.brick.app.feature.stats.StreakCalculator
import kotlinx.coroutines.delay
import com.brick.app.theme.CardSurface
import com.brick.app.theme.LightBorder
import com.brick.app.theme.PureWhite
import com.brick.app.theme.TextMuted
import java.text.SimpleDateFormat
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

/** Statistics show real, completed sessions only. No sample numbers in the empty state. */
@Composable
fun StatsDashboard(sessions: List<BrickSessionEntity>) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = System.currentTimeMillis()
        }
    }
    val days = remember(sessions, now / 60_000) { FocusTimeline.recentDays(sessions, nowMs = now) }
    val stats = remember(sessions, now / 60_000) { StreakCalculator.calculateStats(sessions, now) }
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val historyFormat = remember { SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault()) }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Text("YOUR FOCUS", color = PureWhite, fontSize = 27.sp,
                fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text("One session at a time.", color = TextMuted, fontSize = 14.sp)
            Spacer(Modifier.height(30.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FocusHeadline("TODAY", clockDuration(days.first().durationMs), Modifier.weight(1f))
                Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(58.dp).background(LightBorder))
                FocusHeadline("DAILY AVERAGE · 7 DAYS",
                    clockDuration(FocusTimeline.averageLastSevenDays(days)), Modifier.weight(1f))
            }
            Spacer(Modifier.height(34.dp))
            Text("THE LAST 15 DAYS", color = TextMuted, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(12.dp))
        }
        // Keep the three-column grid inside the parent scroll rather than nesting scrollables.
        items(days.chunked(3)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { day ->
                    Column(
                        Modifier.weight(1f).height(106.dp)
                            .background(CardSurface, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 13.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(dateFormat.format(Date(day.startMs)).uppercase(Locale.getDefault()),
                            color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Column {
                            Text("${day.durationMs / 3_600_000}h", color = PureWhite,
                                fontSize = 23.sp, fontWeight = FontWeight.Medium)
                            Text("${(day.durationMs / 60_000) % 60}m", color = TextMuted, fontSize = 16.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        item {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = LightBorder)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryMetric("CURRENT STREAK", "${stats.currentStreakDays} days", Modifier.weight(1f))
                SummaryMetric("BEST STREAK", "${stats.longestStreakDays} days", Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryMetric("FOCUS TIME", clockDuration(sessions.sumOf { FocusTimeline.sessionDurationMs(it, now) }), Modifier.weight(1f))
                SummaryMetric("SESSIONS", "${stats.sessionCount}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(32.dp))
            Text("RECENT SESSIONS", color = PureWhite, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(12.dp))
            if (sessions.isEmpty()) {
                Text("Your focus sessions will show up here after you unbrick.",
                    color = TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(24.dp))
            }
        }
        items(sessions.take(20), key = { it.id }) { session ->
            Row(Modifier.fillMaxWidth().padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(session.modeName, color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(historyFormat.format(Date(session.startTimeMs)), color = TextMuted, fontSize = 12.sp)
                }
                Text(clockDuration(FocusTimeline.sessionDurationMs(session, now)),
                    color = PureWhite, fontSize = 14.sp)
            }
            HorizontalDivider(color = LightBorder)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FocusHeadline(label: String, time: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1)
        Spacer(Modifier.height(7.dp))
        Text(time, color = PureWhite, fontSize = 34.sp, fontWeight = FontWeight.Light,
            maxLines = 1)
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(value, color = PureWhite, fontSize = 19.sp, fontWeight = FontWeight.Medium)
    }
}

internal fun clockDuration(durationMs: Long): String {
    val minutes = durationMs.coerceAtLeast(0) / 60_000
    return "%d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)
}
