package com.brick.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ScheduleEntity
import com.brick.app.feature.schedules.ExactAlarmStatus
import com.brick.app.theme.CardSurface
import com.brick.app.theme.DarkSurface
import com.brick.app.theme.LightBorder
import com.brick.app.theme.PureBlack
import com.brick.app.theme.PureWhite
import com.brick.app.theme.TextMuted
import java.util.Locale

private val weekdayLabels = listOf("S", "M", "T", "W", "T", "F", "S")
private val weekdayBits = listOf(6, 0, 1, 2, 3, 4, 5)
private val weekdayNames = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

internal fun repeatLabel(mask: Int): String = when (mask) {
    ScheduleEntity.ALL_DAYS -> "Every day"
    ScheduleEntity.WEEKDAYS -> "Weekdays"
    ScheduleEntity.WEEKENDS -> "Weekends"
    else -> listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        .filterIndexed { index, _ -> mask and (1 shl index) != 0 }.joinToString(", ")
}

private fun timeLabel(hour: Int, minute: Int): String =
    "%d:%02d %s".format(Locale.ROOT, (hour + 11) % 12 + 1, minute, if (hour < 12) "am" else "pm")

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
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var hour by rememberSaveable { mutableStateOf("09") }
    var minute by rememberSaveable { mutableStateOf("00") }
    var selectedModeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var days by rememberSaveable { mutableStateOf(ScheduleEntity.WEEKDAYS) }
    val editing = schedules.find { it.id == editingId }
    val selectedMode = modes.find { it.id == selectedModeId } ?: if (editingId == null) modes.firstOrNull() else null
    fun closeEditor() { editorOpen = false; editingId = null }
    fun startNew() {
        editingId = null; name = ""; hour = "09"; minute = "00"
        selectedModeId = modes.firstOrNull()?.id; days = ScheduleEntity.WEEKDAYS; editorOpen = true
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("SCHEDULES", color = PureWhite, fontSize = 27.sp,
                fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Spacer(Modifier.height(5.dp))
            Text("Make focus part of your day.", color = TextMuted, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
        }
        if (exactAlarmStatus == ExactAlarmStatus.INEXACT_FALLBACK || exactAlarmStatus == ExactAlarmStatus.FAILED) {
            item {
                Column(Modifier.fillMaxWidth().border(1.dp, LightBorder, RoundedCornerShape(14.dp)).padding(16.dp)) {
                    Text(if (exactAlarmStatus == ExactAlarmStatus.FAILED) "Schedules could not be armed."
                        else "Schedules may start late without exact alarms.", color = PureWhite, fontSize = 13.sp)
                    if (exactAlarmStatus == ExactAlarmStatus.INEXACT_FALLBACK) {
                        Spacer(Modifier.height(8.dp))
                        Text("ALLOW EXACT ALARMS", Modifier.clickable(onClick = onOpenExactAlarmSettings),
                            color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (!editorOpen) {
            if (schedules.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().background(CardSurface, RoundedCornerShape(16.dp))
                        .padding(22.dp)) {
                        Text("A little routine goes a long way.", color = PureWhite, fontSize = 16.sp)
                        Spacer(Modifier.height(7.dp))
                        Text("Pick a time and days to start a focus session automatically. You still need a paired token to finish it.",
                            color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }
            items(schedules.size) { index ->
                val schedule = schedules[index]
                Column(Modifier.fillMaxWidth().background(CardSurface, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(schedule.name, color = PureWhite, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(timeLabel(schedule.startHour, schedule.startMinute) + "  ·  " + repeatLabel(schedule.daysOfWeek),
                                color = TextMuted, fontSize = 13.sp)
                        }
                        Switch(checked = schedule.isEnabled, enabled = !isBricked,
                            onCheckedChange = { onEnabled(schedule, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = PureBlack,
                                checkedTrackColor = PureWhite, uncheckedTrackColor = DarkSurface))
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = LightBorder)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(modes.find { it.id == schedule.modeId }?.name ?: "Mode unavailable",
                            color = TextMuted, fontSize = 12.sp)
                        Text("EDIT", Modifier.clickable(enabled = !isBricked) {
                            editingId = schedule.id; name = schedule.name
                            hour = "%02d".format(Locale.ROOT, schedule.startHour)
                            minute = "%02d".format(Locale.ROOT, schedule.startMinute)
                            days = schedule.daysOfWeek; selectedModeId = schedule.modeId
                            editorOpen = true
                        }.semantics { contentDescription = "Edit ${schedule.name}" }, color = if (isBricked) TextMuted else PureWhite,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Button(onClick = ::startNew, enabled = !isBricked, modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack)) {
                    Text("+  ADD SCHEDULE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                if (isBricked) Text("Unlock to change schedules.", color = TextMuted,
                    modifier = Modifier.padding(top = 12.dp))
            }
        } else {
            item {
                Text(if (editingId == null) "ADD SCHEDULE" else "EDIT SCHEDULE",
                    color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, onValueChange = { name = it.take(40) }, label = { Text("Name") },
                    enabled = !isBricked, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                Column(Modifier.fillMaxWidth().background(CardSurface, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Text("STARTS", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) },
                            label = { Text("Hour · 0–23") }, enabled = !isBricked, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f))
                        OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) },
                            label = { Text("Minute · 0–59") }, enabled = !isBricked, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = LightBorder)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("ENDS", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("On paired token scan", color = PureWhite, fontSize = 13.sp)
                    }
                }
            }
            item {
                Text("BRICK MODE", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (modes.isEmpty()) Text("Create a mode before scheduling focus.", color = TextMuted)
                modes.forEach { mode ->
                    Row(Modifier.fillMaxWidth().height(50.dp)
                        .semantics { contentDescription = "Mode ${mode.name}" }
                        .clickable(enabled = !isBricked) { selectedModeId = mode.id },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(mode.name, color = PureWhite, fontSize = 15.sp)
                        Text(if (selectedMode?.id == mode.id) "●" else "○",
                            color = PureWhite, fontSize = 21.sp)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("REPEAT", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(repeatLabel(days).ifBlank { "Select days" }, color = TextMuted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    weekdayLabels.forEachIndexed { index, label ->
                        val bit = 1 shl weekdayBits[index]
                        val selected = days and bit != 0
                        Column(Modifier.weight(1f).aspectRatio(1f)
                            .background(if (selected) PureWhite else CardSurface, CircleShape)
                            .clickable(enabled = !isBricked, role = Role.Checkbox, onClickLabel = weekdayNames[index]) {
                                days = days xor bit
                            }.semantics { contentDescription = weekdayNames[index]; this.selected = selected }, verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, color = if (selected) PureBlack else PureWhite,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                val h = hour.toIntOrNull(); val m = minute.toIntOrNull()
                Button(enabled = !isBricked && name.isNotBlank() && selectedMode != null &&
                    h != null && h in 0..23 && m != null && m in 0..59 && days != 0 &&
                    (editingId == null || editing != null),
                    onClick = {
                        onSave(ScheduleEntity(id = editing?.id ?: 0, name = name.trim(),
                            modeId = selectedMode!!.id, daysOfWeek = days, startHour = h!!,
                            startMinute = m!!, isEnabled = editing?.isEnabled ?: true,
                            createdAt = editing?.createdAt ?: System.currentTimeMillis()))
                        closeEditor()
                    }, modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack)) {
                    Text("SAVE SCHEDULE", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text("CANCEL", Modifier.fillMaxWidth().clickable { closeEditor() }.padding(16.dp),
                    color = TextMuted, textAlign = TextAlign.Center, fontSize = 12.sp)
                if (editing != null && !isBricked) {
                    Text("DELETE SCHEDULE", Modifier.fillMaxWidth().clickable {
                        onDelete(editing); closeEditor()
                    }.padding(16.dp), color = TextMuted, textAlign = TextAlign.Center, fontSize = 12.sp)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
