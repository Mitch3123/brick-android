package com.brick.app.ui.main

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ScheduleEntity
import com.brick.app.feature.schedules.ExactAlarmStatus
import com.brick.app.ui.design.PillButton
import com.brick.app.ui.design.ReferenceBackground
import com.brick.app.ui.design.ReferenceCard
import com.brick.app.ui.design.ReferenceInk
import com.brick.app.ui.design.ReferenceMuted
import com.brick.app.ui.design.ReferenceSheet
import com.brick.app.ui.design.SheetHeader
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
    "%d:%02d %s".format(Locale.ROOT, (hour + 11) % 12 + 1, minute, if (hour < 12) "AM" else "PM")


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
    var hour by rememberSaveable { mutableIntStateOf(DEFAULT_HOUR) }
    var minute by rememberSaveable { mutableIntStateOf(0) }
    var selectedModeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var days by rememberSaveable { mutableIntStateOf(ScheduleEntity.WEEKDAYS) }
    val editing = schedules.find { it.id == editingId }
    val selectedMode = modes.find { it.id == selectedModeId } ?: if (editingId == null) modes.firstOrNull() else null
    fun closeEditor() { editorOpen = false; editingId = null }
    fun startNew() {
        editingId = null; name = ""; hour = DEFAULT_HOUR; minute = 0
        selectedModeId = modes.firstOrNull()?.id; days = ScheduleEntity.WEEKDAYS; editorOpen = true
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        if (exactAlarmStatus == ExactAlarmStatus.INEXACT_FALLBACK || exactAlarmStatus == ExactAlarmStatus.FAILED) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp)
                .background(ReferenceCard, RoundedCornerShape(24.dp)).padding(18.dp)) {
                Text(if (exactAlarmStatus == ExactAlarmStatus.FAILED) "Schedules could not be armed."
                    else "Schedules may start late without exact alarms.", color = ReferenceInk, fontSize = 15.sp)
                if (exactAlarmStatus == ExactAlarmStatus.INEXACT_FALLBACK) {
                    Spacer(Modifier.height(8.dp))
                    Text("Allow exact alarms", Modifier.clickable(onClick = onOpenExactAlarmSettings)
                        .padding(vertical = 4.dp), color = ReferenceInk, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (schedules.isEmpty()) {
                Text("Create routines to make blocking distractions a daily habit",
                    Modifier.align(Alignment.Center).padding(horizontal = 36.dp),
                    color = ReferenceMuted, fontSize = 21.sp, lineHeight = 29.sp,
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Spacer(Modifier.height(20.dp)) }
                    items(schedules, key = { it.id }) { schedule ->
                        ScheduleCard(schedule, modes.find { it.id == schedule.modeId }?.name, isBricked,
                            onEdit = {
                                editingId = schedule.id; name = schedule.name
                                hour = schedule.startHour; minute = schedule.startMinute
                                days = schedule.daysOfWeek; selectedModeId = schedule.modeId
                                editorOpen = true
                            },
                            onEnabled = { onEnabled(schedule, it) })
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (isBricked) Text("Unlock to change schedules.", color = ReferenceMuted, fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 10.dp))
            Text("Create new schedule", color = ReferenceMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.size(52.dp).background(ReferenceCard, CircleShape)
                .border(1.dp, ReferenceBackground, CircleShape)
                .clickable(enabled = !isBricked, role = Role.Button, onClick = ::startNew)
                .semantics { contentDescription = "Create new schedule" },
                contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(18.dp)) {
                    val stroke = 2.dp.toPx()
                    val color = if (isBricked) ReferenceMuted else ReferenceInk
                    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke, StrokeCap.Round)
                    drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke, StrokeCap.Round)
                }
            }
        }
    }

    if (editorOpen) {
        val h = hour; val m = minute
        ReferenceSheet(onDismiss = ::closeEditor) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 18.dp)) {
                SheetHeader(if (editingId == null) "Add schedule" else "Edit schedule", onBack = ::closeEditor)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(12.dp))
                    NamePill(name, enabled = !isBricked) { name = it.take(40) }
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth().background(ReferenceCard, RoundedCornerShape(32.dp))
                        .padding(horizontal = 22.dp)) {
                        val context = LocalContext.current
                        FieldRow("Starts", timeLabel(h, m), enabled = !isBricked,
                            description = "Starts ${timeLabel(h, m)}",
                            onClick = {
                                TimePickerDialog(context, { _, pickedHour, pickedMinute ->
                                    hour = pickedHour; minute = pickedMinute
                                }, h, m, DateFormat.is24HourFormat(context)).show()
                            })
                        HorizontalDivider(color = ReferenceMuted.copy(alpha = 0.25f))
                        FieldRow("Ends", "On token scan", enabled = false, description = "Ends on token scan")
                    }
                    Text("Sessions end when you scan your paired token.", Modifier.padding(start = 16.dp, top = 8.dp),
                        color = ReferenceMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    var modeMenu by rememberSaveable { mutableStateOf(false) }
                    Box {
                        Column(Modifier.fillMaxWidth().background(ReferenceCard, RoundedCornerShape(32.dp))
                            .padding(horizontal = 22.dp)) {
                            FieldRow("Brick Mode", selectedMode?.name ?: if (modes.isEmpty()) "Create a mode first" else "Choose a mode",
                                enabled = !isBricked && modes.isNotEmpty(),
                                description = "Brick Mode ${selectedMode?.name ?: "none"}",
                                onClick = { modeMenu = true })
                        }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            modes.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.name, fontWeight = if (mode.id == selectedMode?.id) FontWeight.Bold else FontWeight.Normal) },
                                    modifier = Modifier.semantics { contentDescription = "Mode ${mode.name}" },
                                    onClick = { selectedModeId = mode.id; modeMenu = false })
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Repeat", color = ReferenceMuted, fontSize = 17.sp)
                        Text(if (days == 0) "Select at least one day" else repeatLabel(days),
                            color = ReferenceMuted, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(start = 12.dp),
                            textAlign = TextAlign.End, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        weekdayLabels.forEachIndexed { index, label ->
                            val bit = 1 shl weekdayBits[index]
                            val selected = days and bit != 0
                            Box(Modifier.weight(1f).aspectRatio(1f)
                                .background(if (selected) ReferenceInk else ReferenceCard, CircleShape)
                                .clickable(enabled = !isBricked, role = Role.Checkbox, onClickLabel = weekdayNames[index]) {
                                    days = days xor bit
                                }.semantics { contentDescription = weekdayNames[index]; this.selected = selected },
                                contentAlignment = Alignment.Center) {
                                Text(label, color = if (selected) ReferenceBackground else ReferenceMuted,
                                    fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    if (editing != null && !isBricked) {
                        Spacer(Modifier.height(28.dp))
                        PillButton("Delete schedule", onClick = { onDelete(editing); closeEditor() },
                            modifier = Modifier.fillMaxWidth(), secondary = true)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Spacer(Modifier.height(12.dp))
                PillButton("Save schedule",
                    enabled = !isBricked && name.isNotBlank() && selectedMode != null &&
                        h in 0..23 && m in 0..59 && days != 0 && (editingId == null || editing != null),
                    onClick = {
                        onSave(ScheduleEntity(id = editing?.id ?: 0, name = name.trim(),
                            modeId = selectedMode!!.id, daysOfWeek = days, startHour = h,
                            startMinute = m, isEnabled = editing?.isEnabled ?: true,
                            createdAt = editing?.createdAt ?: System.currentTimeMillis()))
                        closeEditor()
                    }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

private const val DEFAULT_HOUR = 9

@Composable
private fun ScheduleCard(
    schedule: ScheduleEntity,
    modeName: String?,
    isBricked: Boolean,
    onEdit: () -> Unit,
    onEnabled: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().background(ReferenceCard, RoundedCornerShape(28.dp))
        .padding(start = 22.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(enabled = !isBricked, onClick = onEdit)
            .semantics { contentDescription = "Edit ${schedule.name}" }.padding(vertical = 4.dp)) {
            Text(schedule.name, color = ReferenceInk, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(timeLabel(schedule.startHour, schedule.startMinute) + "  ·  " + repeatLabel(schedule.daysOfWeek),
                color = ReferenceMuted, fontSize = 15.sp)
            Text(modeName ?: "Mode unavailable", color = ReferenceMuted, fontSize = 14.sp)
        }
        Switch(checked = schedule.isEnabled, enabled = !isBricked, onCheckedChange = onEnabled,
            colors = SwitchDefaults.colors(checkedThumbColor = ReferenceCard, checkedTrackColor = ReferenceInk,
                uncheckedThumbColor = ReferenceMuted, uncheckedTrackColor = ReferenceBackground,
                uncheckedBorderColor = ReferenceMuted))
    }
}

@Composable
private fun NamePill(value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(ReferenceCard, RoundedCornerShape(32.dp))
        .padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Name", color = ReferenceMuted, fontSize = 17.sp)
        Box(Modifier.weight(1f).padding(start = 16.dp), contentAlignment = Alignment.CenterEnd) {
            if (value.isEmpty()) Text("Work, Family Time, etc.", color = ReferenceMuted.copy(alpha = 0.8f),
                fontSize = 17.sp, textAlign = TextAlign.End, maxLines = 1)
            BasicTextField(value, onValueChange, enabled = enabled, singleLine = true,
                textStyle = TextStyle(color = ReferenceInk, fontFamily = com.brick.app.ui.design.ReferenceFont, fontSize = 17.sp, textAlign = TextAlign.End),
                cursorBrush = SolidColor(ReferenceInk),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Schedule name" })
        }
    }
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    enabled: Boolean,
    description: String,
    onClick: (() -> Unit)? = null
) {
    val base = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    Row(
        (if (onClick != null) base.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else base)
            .semantics { contentDescription = description }.padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = ReferenceMuted, fontSize = 17.sp)
        Text(value, color = ReferenceInk, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).padding(start = 12.dp), textAlign = TextAlign.End,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
