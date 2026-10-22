package com.brick.app.ui.onboarding

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brick.app.feature.service.BrickAccessibilityService
import com.brick.app.feature.service.BrickDeviceAdminReceiver
import com.brick.app.ui.design.ReferenceCard as CardSurface
import com.brick.app.ui.design.ReferenceCard as DarkSurface
import com.brick.app.ui.design.ReferenceBackground as PureBlack
import com.brick.app.ui.design.ReferenceInk as PureWhite
import com.brick.app.ui.design.ReferenceMuted as TextMuted

@Composable
fun PermissionSetupScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasAccessibility by remember { mutableStateOf(false) }
    var hasOverlay by remember { mutableStateOf(false) }
    var hasDeviceAdmin by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    fun openSettings(intent: Intent) {
        settingsError = null
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) { settingsError = "This setting is not available on your phone." }
        catch (_: SecurityException) { settingsError = "Your device policy does not allow this setting. Contact your device administrator." }
    }

    fun checkPermissions() {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val expected = ComponentName(context, BrickAccessibilityService::class.java)
        hasAccessibility = enabledServices.split(':').any { ComponentName.unflattenFromString(it) == expected }

        hasOverlay = Settings.canDrawOverlays(context)

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, BrickDeviceAdminReceiver::class.java)
        hasDeviceAdmin = dpm.isAdminActive(adminComponent)
    }

    // Settings returns to this same composition; checking only at initial composition left
    // every permission showing GRANT after the user came back from enabling it.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checkPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        checkPermissions()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(24.dp)
    ) {
        com.brick.app.ui.design.RoundBackButton(onBack)
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Allow Brick to help",
            color = PureWhite,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enable app blocking below. You stay in control of these permissions in Android Settings.",
            color = TextMuted,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "App permissions",
            color = PureWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            item {
                PermissionItem(
                    title = "Accessibility Service",
                    description = "Required to detect and intercept blocked applications in real time.",
                    isGranted = hasAccessibility,
                    onGrant = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        openSettings(intent)
                    }
                )
            }
            item {
                PermissionItem(
                    title = "Display Over Other Apps",
                    description = "Displays the full-screen 'You're Bricked' shield over distracting apps.",
                    isGranted = hasOverlay,
                    onGrant = {
                        openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri()))
                    }
                )
            }
            item {
                PermissionItem(
                    title = "Device Admin (optional)",
                    description = "Adds device-admin protection for Strict Mode. Android may still allow removal through Settings.",
                    isGranted = hasDeviceAdmin,
                    onGrant = {
                        val component = ComponentName(context, BrickDeviceAdminReceiver::class.java)
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enables Strict Mode protection while bricked.")
                        }
                        openSettings(intent)
                    }
                )
            }
        }

        settingsError?.let { Text(it, color = PureWhite, modifier = Modifier.padding(vertical = 12.dp)) }
        com.brick.app.ui.design.PillButton("Continue", onContinue, enabled = hasAccessibility && hasOverlay)
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrant: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) Color(0xFF2E7D32) else PureWhite,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (isGranted) "READY" else "GRANT",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}
