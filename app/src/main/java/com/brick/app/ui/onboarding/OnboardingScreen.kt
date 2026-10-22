package com.brick.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.withTransaction
import com.brick.app.R
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.feature.home.SessionManager
import com.brick.app.feature.nfc.rememberTokenScanController
import com.brick.app.ui.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen(onFinishOnboarding: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember(context) { BrickDatabase.getInstance(context) }
    val scanner = rememberTokenScanController()
    val scope = rememberCoroutineScope()
    val apps by rememberSelectableApps()
    val tokens by remember(db) { db.tokenDao().observeTokenCount() }.collectAsState(initial = 0)
    var step by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(step > 0 && !pickerOpen && !saving) { step-- }

    fun finishSetup() {
        if (saving || selected.isEmpty() || tokens == 0) return
        saving = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val manager = SessionManager.getInstance(context)
                    val saved = manager.whileUnbricked {
                    db.withTransaction {
                        val names = db.modeDao().getAllModes().map { it.name }.toSet()
                        val name = generateSequence(1) { it + 1 }
                            .map { if (it == 1) "No Socials" else "No Socials ($it)" }
                            .first { it !in names }
                        val mode = ModeEntity(name = name, modeType = ModeType.BLOCK_LIST, packageNames = selected.toSet())
                        val id = db.modeDao().insert(mode)
                        db.modeDao().setDefaultMode(id)
                    }
                    true
                    } ?: false
                    check(saved) { "Unbrick before changing your mode." }
                    manager.refreshSession()
                }
                onFinishOnboarding()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not save setup. Please try again."
            } finally { saving = false }
        }
    }

    ReferenceTheme {
        if (step == 2) {
            PermissionSetupScreen(onContinue = { step = 3 }, onBack = { step = 1 },
                modifier = modifier.background(ReferenceBackground).safeDrawingPadding())
        } else {
            Column(modifier.fillMaxSize().background(ReferenceBackground).safeDrawingPadding().padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(20.dp))
                if (step > 0) RoundBackButton({ step-- }) else Spacer(Modifier.height(48.dp))
                Spacer(Modifier.height(18.dp))
                when (step) {
                    0 -> {
                        OnboardingHeading("Nice, let's call your first mode\nNo Socials")
                        Spacer(Modifier.height(12.dp))
                        Text("Now let's choose the apps to block when you use it.", color = ReferenceMuted,
                            fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Image(painterResource(R.drawable.onboarding_social_apps), null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 24.dp, bottom = 95.dp, start = 35.dp, end = 35.dp))
                        PillButton("Choose apps", { pickerOpen = true })
                        Text("Block up to 50 distractions for this mode. You can edit anytime.",
                            color = ReferenceMuted, fontSize = 15.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                    }
                    1 -> {
                        OnboardingHeading("No Socials is ready to block ${selected.size} " +
                            if (selected.size == 1) "distraction" else "distractions")
                        Spacer(Modifier.height(20.dp))
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(apps.orEmpty().filter { it.packageName in selected }, key = { it.packageName }) {
                                AppSelectionRow(it)
                            }
                        }
                        PillButton("Finish setup", { step = 2 })
                        TextButton({ pickerOpen = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                            Text("Edit selection", fontSize = 16.sp)
                        }
                    }
                    3 -> {
                        Text("PRACTICE RUN", fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        OnboardingHeading("Try your first Brick")
                        Spacer(Modifier.height(12.dp))
                        Text("On the next screen, hold your phone near the Brick.\nNo apps will be blocked.",
                            color = ReferenceMuted, fontSize = 19.sp, lineHeight = 27.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp)
                            .background(ReferenceCard, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                com.brick.app.ui.scan.ScanIllustration(Modifier.size(180.dp))
                                Text("Brick", fontSize = 30.sp)
                                Text("Pair your NFC tag or a printed QR token", color = ReferenceMuted,
                                    textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp))
                            }
                        }
                        error?.let { Text(it, color = ReferenceInk, modifier = Modifier.padding(bottom = 12.dp)) }
                        if (tokens > 0) PillButton(if (saving) "Saving…" else "Continue to Brick", ::finishSetup, enabled = !saving)
                        else PillButton("Try it now", { scanner.requestPairNfcToken("My Brick") })
                        TextButton({ scanner.requestPairQrToken("My QR token") }, enabled = !saving,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Text("Use a QR token instead", fontSize = 16.sp)
                        }
                    }
                }
            }
        }
        if (pickerOpen) AppSelectionSheet(apps, selected.toSet(), { pickerOpen = false }) {
            selected = it.toList(); pickerOpen = false; step = 1
        }
    }
}

@Composable
private fun OnboardingHeading(text: String) {
    Text(text, fontSize = 28.sp, lineHeight = 36.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth())
}
