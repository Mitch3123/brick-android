package com.brick.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brick.app.ui.design.*

@Composable
fun SettingsContent(isBricked: Boolean, onSetup: () -> Unit, onManageModes: () -> Unit,
                    onPairNfc: () -> Unit, onPairQr: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", fontSize = 30.sp, modifier = Modifier.padding(vertical = 24.dp))
        SettingsRow("Manage modes", "Choose which apps to block", true, onManageModes)
        SettingsRow("App permissions & setup", "Review permissions and onboarding", !isBricked, onSetup)
        SettingsRow("Pair a Brick", "Connect a physical NFC tag", !isBricked, onPairNfc)
        SettingsRow("Create a QR token", "Use a printed code to unbrick", !isBricked, onPairQr)
        if (isBricked) Text("Unbrick with your paired token before changing setup.", color = ReferenceMuted)
        Spacer(Modifier.weight(1f))
        Text("Brick • 1.0", color = ReferenceMuted, modifier = Modifier.padding(bottom = 20.dp))
    }
}

@Composable
private fun SettingsRow(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(ReferenceCard, RoundedCornerShape(24.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(20.dp)) {
        Text(title, color = if (enabled) ReferenceInk else ReferenceMuted, fontSize = 18.sp)
        Text(detail, color = ReferenceMuted, fontSize = 14.sp)
    }
}
