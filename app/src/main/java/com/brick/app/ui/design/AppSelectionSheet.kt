package com.brick.app.ui.design

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.brick.app.core.policy.SystemWhitelist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val MAX_MODE_APPS = 50

data class SelectableApp(val packageName: String, val label: String, val icon: Bitmap? = null)

internal fun toggleAppSelection(selected: Set<String>, packageName: String): Set<String> = when {
    packageName in selected -> selected - packageName
    selected.size < MAX_MODE_APPS -> selected + packageName
    else -> selected
}

@Composable
fun rememberSelectableApps(): State<List<SelectableApp>?> {
    val context = LocalContext.current
    return produceState<List<SelectableApp>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            val manager = context.packageManager
            manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .distinctBy { it.activityInfo.packageName }
                .filterNot { it.activityInfo.packageName == context.packageName ||
                    SystemWhitelist.isWhitelisted(it.activityInfo.packageName) }
                .map { SelectableApp(it.activityInfo.packageName, it.loadLabel(manager).toString(),
                    it.loadIcon(manager).toBitmap(96, 96)) }
                .sortedBy { it.label.lowercase() }
        }
    }
}

@Composable
fun AppSelectionSheet(apps: List<SelectableApp>?, initialSelection: Set<String>,
                      onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    var selectedList by rememberSaveable { mutableStateOf(initialSelection.toList()) }
    val selected = selectedList.toSet()
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) { apps.orEmpty().filter { it.label.contains(query, ignoreCase = true) } }
    ReferenceSheet(onDismiss) {
        Column(Modifier.fillMaxHeight(0.95f)) {
            SheetHeader("Select apps", onDismiss)
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search apps") },
                leadingIcon = { Text("⌕", fontSize = 26.sp) }, singleLine = true,
                shape = CircleShape, colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ReferenceCard, unfocusedContainerColor = ReferenceCard,
                    focusedBorderColor = ReferenceMuted, unfocusedBorderColor = ReferenceCard),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            Text(if (apps == null) "Loading apps…" else "Found ${filtered.size} ${if (filtered.size == 1) "app" else "apps"}",
                color = ReferenceMuted, modifier = Modifier.padding(24.dp, 16.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    AppSelectionRow(app, selected = app.packageName in selected,
                        onToggle = { selectedList = toggleAppSelection(selected, app.packageName).toList() })
                }
                if (apps != null && filtered.isEmpty()) item {
                    Text("No apps found. Try another search.", color = ReferenceMuted,
                        modifier = Modifier.padding(vertical = 20.dp))
                }
            }
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                if (selected.size >= MAX_MODE_APPS) Text("You can select up to $MAX_MODE_APPS apps.",
                    color = ReferenceMuted, modifier = Modifier.padding(bottom = 8.dp))
                PillButton(if (selected.isEmpty()) "Select apps to continue" else "Continue with ${selected.size} ${if (selected.size == 1) "app" else "apps"}",
                    { onConfirm(selected) }, enabled = selected.isNotEmpty())
            }
        }
    }
}

@Composable
fun AppSelectionRow(app: SelectableApp, selected: Boolean? = null, onToggle: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(ReferenceCard, RoundedCornerShape(14.dp))
        .then(if (selected != null) Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() }) else Modifier)
        .padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (app.icon != null) Image(app.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(34.dp))
        else Box(Modifier.size(34.dp).background(ReferenceBackground, CircleShape), contentAlignment = Alignment.Center) {
            Text(app.label.take(1), color = ReferenceMuted)
        }
        Text(app.label, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(start = 14.dp))
        if (selected != null) Checkbox(checked = selected, onCheckedChange = null)
    }
}
