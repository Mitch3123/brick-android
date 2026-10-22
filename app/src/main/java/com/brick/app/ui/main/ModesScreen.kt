package com.brick.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.ui.design.*

@Composable
fun ModesContent(modes: List<ModeEntity>, activeModeId: Long?, isBricked: Boolean = false,
                 onSelectMode: (ModeEntity) -> Unit, onAddMode: (String, ModeType, List<String>) -> Unit,
                 onUpdateMode: (ModeEntity) -> Unit = {}) {
    val apps by rememberSelectableApps()
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var modeName by rememberSaveable { mutableStateOf("") }
    var allowList by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Manage modes", fontSize = 28.sp, modifier = Modifier.padding(vertical = 24.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(modes, key = { it.id }) { mode ->
                Row(Modifier.fillMaxWidth().background(ReferenceCard, RoundedCornerShape(24.dp))
                    .clickable(enabled = !isBricked) { onSelectMode(mode) }.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(mode.name, fontSize = 20.sp)
                        Text("${if (mode.modeType == ModeType.BLOCK_LIST) "Blocks" else "Allows"} ${mode.packageNames.size} apps",
                            color = ReferenceMuted, fontSize = 15.sp)
                    }
                    RadioButton(mode.id == activeModeId, onClick = null, enabled = !isBricked)
                    TextButton(enabled = !isBricked, onClick = {
                        editingId = mode.id; modeName = mode.name
                        allowList = mode.modeType == ModeType.ALLOW_LIST
                        selected = mode.packageNames.toList(); showEditor = true
                    }) { Text("Edit") }
                }
            }
        }
        if (isBricked) Text("Unlock to change modes.", color = ReferenceMuted, modifier = Modifier.padding(vertical = 12.dp))
        PillButton("Add mode", { editingId = null; modeName = ""; selected = emptyList(); allowList = false; showEditor = true }, enabled = !isBricked)
    }
    if (showEditor && !isBricked) ReferenceSheet({ showEditor = false }) {
        SheetHeader(if (editingId == null) "Create mode" else "Edit mode", { showEditor = false })
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(modeName, { modeName = it.take(40) }, label = { Text("Mode name") },
                singleLine = true, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (allowList) "Allow list" else "Block list", modifier = Modifier.weight(1f))
                Switch(allowList, { allowList = it })
            }
            Text(if (allowList) "Only selected apps stay available. Phone and emergency apps always stay available."
                else "Selected apps are blocked while bricked.", color = ReferenceMuted)
            PillButton(if (selected.isEmpty()) "Choose apps" else "${selected.size} apps selected", { showPicker = true }, secondary = true)
            PillButton("Save mode", {
                val type = if (allowList) ModeType.ALLOW_LIST else ModeType.BLOCK_LIST
                val existing = modes.find { it.id == editingId }
                if (existing != null) onUpdateMode(existing.copy(name = modeName.trim(), modeType = type, packageNames = selected.toSet()))
                else if (editingId == null) onAddMode(modeName.trim(), type, selected)
                showEditor = false; modeName = ""; selected = emptyList()
            }, enabled = modeName.isNotBlank() && selected.isNotEmpty())
        }
    }
    if (showPicker && !isBricked) AppSelectionSheet(apps, selected.toSet(), { showPicker = false }) {
        selected = it.toList(); showPicker = false
    }
}
