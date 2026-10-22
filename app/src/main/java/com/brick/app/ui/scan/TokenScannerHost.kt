package com.brick.app.ui.scan

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.brick.app.ui.design.PillButton
import com.brick.app.ui.design.ReferenceCard
import com.brick.app.ui.design.ReferenceInk
import com.brick.app.ui.design.ReferenceMuted
import com.brick.app.ui.design.ReferenceSheet
import com.brick.app.ui.design.RoundBackButton
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brick.app.core.database.TokenType
import com.brick.app.feature.nfc.GeneratedQrToken
import com.brick.app.feature.nfc.ScanMedium
import com.brick.app.feature.nfc.ScanPhase
import com.brick.app.feature.nfc.ScanResult
import com.brick.app.feature.nfc.TokenScanController
import com.brick.app.feature.nfc.TokenScanOutcome
import com.brick.app.feature.nfc.TokenScanUiState
import com.brick.app.feature.qr.QrCodeGenerator
import com.brick.app.feature.qr.QrImageAnalyzer
import java.util.concurrent.Executors

object ScanTestTags {
    const val OVERLAY = "token_scanner_overlay"
    const val CAMERA_PREVIEW = "token_scanner_camera"
    const val CANCEL = "token_scanner_cancel"
    const val OUTCOME = "token_scanner_outcome"
    const val PRINTABLE_QR = "token_scanner_printable_qr"
}

/**
 * Scanner surface for physical tokens, hosted above the app content by MainActivity.
 *
 * It renders only while [TokenScanController] has an active request or a result to show, so no
 * screen needs to lay it out and the camera is bound only for the duration of a QR scan.
 */
@Composable
fun TokenScannerHost(
    controller: TokenScanController,
    modifier: Modifier = Modifier
) {
    val state by controller.state.collectAsStateWithLifecycle()

    if (state.isScanning) {
        ScanOverlay(
            state = state,
            modifier = modifier,
            onQrPayload = controller::onQrPayload,
            onCancel = controller::dismiss
        )
    }

    val generatedQr = state.generatedQr
    if (generatedQr != null) {
        PrintableQrDialog(token = generatedQr, onDone = controller::acknowledgeGeneratedQr)
        return
    }

    val outcome = state.lastOutcome
    if (outcome != null && outcome !is TokenScanOutcome.Cancelled && !state.isScanning) {
        OutcomeDialog(outcome = outcome, onDismiss = controller::acknowledgeOutcome)
    }
}

/** True when the camera should be shown: QR-only requests, or ANY after the user chose QR. */
internal fun showsCamera(medium: ScanMedium, userChoseQr: Boolean): Boolean =
    medium == ScanMedium.QR || (medium == ScanMedium.ANY && userChoseQr)

@Composable
private fun ScanOverlay(
    state: TokenScanUiState,
    modifier: Modifier,
    onQrPayload: (String) -> Unit,
    onCancel: () -> Unit
) {
    var userChoseQr by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val camera = showsCamera(state.medium, userChoseQr)
    val screenHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()
    }

    ReferenceSheet(onDismiss = onCancel, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = screenHeight * 0.46f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 22.dp)
                .testTag(ScanTestTags.OVERLAY),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                RoundBackButton(onClick = onCancel, close = true, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(12.dp))
            if (showHelp) {
                HelpContent(
                    state = state,
                    canUseQr = state.medium == ScanMedium.ANY && !camera,
                    onUseQr = { userChoseQr = true; showHelp = false },
                    onBack = { showHelp = false }
                )
            } else {
                Text(
                    text = if (camera) "Scan the QR card" else "Ready to scan",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (camera) "Point the camera at your printed QR card"
                    else "Hold your phone near the Brick",
                    fontSize = 17.sp,
                    color = ReferenceInk,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                if (camera) {
                    QrCameraScanner(
                        enabled = state.phase == ScanPhase.WAITING_FOR_TOKEN,
                        onQrPayload = onQrPayload,
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                    )
                } else {
                    PhoneNearBrickIllustration()
                }
                if (state.phase == ScanPhase.VERIFYING) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(color = ReferenceInk)
                }
                if (!camera && !state.isNfcAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Text("This device has no NFC radio.", color = ReferenceMuted, fontSize = 14.sp)
                } else if (!camera && state.isNfcAvailable && !state.isNfcEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text("NFC is switched off in system settings.", color = ReferenceMuted, fontSize = 14.sp)
                }
                Spacer(Modifier.height(28.dp))
                TextButton(onClick = { showHelp = true }) {
                    Text("Having trouble scanning?", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ReferenceInk)
                }
            }
            Spacer(Modifier.height(16.dp))
            PillButton(
                text = "Cancel",
                onClick = onCancel,
                secondary = true,
                modifier = Modifier.testTag(ScanTestTags.CANCEL)
            )
        }
    }
}

@Composable
private fun HelpContent(
    state: TokenScanUiState,
    canUseQr: Boolean,
    onUseQr: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var settingsError by remember { mutableStateOf<String?>(null) }
    Text("Having trouble scanning?", fontSize = 26.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    val nfcStatus = when {
        state.medium == ScanMedium.QR -> null
        !state.isNfcAvailable -> "This device has no NFC radio."
        !state.isNfcEnabled -> "NFC is switched off. Turn it on in system settings."
        else -> "NFC is on."
    }
    Text(
        text = listOfNotNull(
            nfcStatus,
            "Hold the back of your phone flat against the Brick and keep it still for a moment. " +
                "Thick cases can block the signal."
        ).joinToString(" "),
        fontSize = 16.sp,
        color = ReferenceMuted,
        textAlign = TextAlign.Center
    )
    settingsError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(20.dp))
    if (state.medium != ScanMedium.QR && state.isNfcAvailable) {
        PillButton(
            text = "Open NFC settings",
            onClick = { settingsError = openNfcSettings(context) },
            secondary = true
        )
        Spacer(Modifier.height(10.dp))
    }
    if (canUseQr) {
        PillButton(text = "Scan a QR code instead", onClick = onUseQr)
        Spacer(Modifier.height(10.dp))
    }
    TextButton(onClick = onBack) {
        Text("Back to scanning", fontSize = 16.sp, color = ReferenceInk)
    }
}

/** Returns an error message, or null when a settings screen was launched. */
private fun openNfcSettings(context: Context): String? {
    val intents = listOf(
        Intent(Settings.ACTION_NFC_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS)
    )
    for (intent in intents) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return null
        } catch (_: ActivityNotFoundException) {
            // try next fallback
        }
    }
    return "Couldn't open settings on this device. Enable NFC from system settings manually."
}

@Composable
private fun QrCameraScanner(
    enabled: Boolean,
    onQrPayload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    var cameraError by remember { mutableStateOf<String?>(null) }

    if (!hasPermission) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Camera access is needed to scan a printed QR token.",
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            PillButton(
                text = "Allow camera",
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
            )
        }
        return
    }

    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }

    DisposableEffect(enabled, previewView) {
        if (!enabled) {
            return@DisposableEffect onDispose { }
        }
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var boundProvider: ProcessCameraProvider? = null
        var disposed = false

        providerFuture.addListener({
            // The provider future can complete after this effect was disposed; binding then
            // would leave a camera session running with no surface to render into.
            if (disposed) return@addListener
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                cameraError = "The camera couldn't start (${e.cause?.message ?: e.message ?: "unknown error"})."
                return@addListener
            }
            boundProvider = provider

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(analysisExecutor, QrImageAnalyzer(onPayload = onQrPayload)) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                cameraError = null
            } catch (e: IllegalArgumentException) {
                cameraError = "No usable camera was found on this device."
            } catch (e: IllegalStateException) {
                cameraError = "The camera is unavailable right now. Close other camera apps and try again."
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            boundProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize().testTag(ScanTestTags.CAMERA_PREVIEW)
        )
        cameraError?.let {
            Text(
                text = it,
                modifier = Modifier
                    .fillMaxSize()
                    .background(ReferenceCard)
                    .padding(16.dp),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ResultSheet(
    title: String,
    tag: String,
    onDismiss: () -> Unit,
    buttonText: String,
    content: @Composable ColumnScope.() -> Unit
) {
    ReferenceSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 22.dp)
                .testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                RoundBackButton(onClick = onDismiss, close = true, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = 28.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(24.dp))
            PillButton(text = buttonText, onClick = onDismiss)
        }
    }
}

@Composable
private fun OutcomeDialog(outcome: TokenScanOutcome, onDismiss: () -> Unit) {
    ResultSheet(
        title = outcomeTitle(outcome),
        tag = ScanTestTags.OUTCOME,
        onDismiss = onDismiss,
        buttonText = "OK"
    ) {
        Text(
            text = outcomeMessage(outcome),
            fontSize = 17.sp,
            color = ReferenceMuted,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PrintableQrDialog(token: GeneratedQrToken, onDone: () -> Unit) {
    val bitmap = remember(token.payload) { QrCodeGenerator.generateQrBitmap(token.payload) }
    ResultSheet(
        title = "Print \"${token.label}\"",
        tag = ScanTestTags.PRINTABLE_QR,
        onDismiss = onDone,
        buttonText = "Done"
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR token ${token.label}",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(8.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Print or photograph this code and keep it away from your phone. " +
                "Scanning it is the only way it can unlock a session.",
            fontSize = 14.sp,
            color = ReferenceMuted,
            textAlign = TextAlign.Center
        )
    }
}

private fun outcomeTitle(outcome: TokenScanOutcome): String = when (outcome) {
    is TokenScanOutcome.Paired -> "Token paired"
    is TokenScanOutcome.Failed -> "Not accepted"
    TokenScanOutcome.Cancelled -> "Cancelled"
    is TokenScanOutcome.Scanned -> when (outcome.result) {
        is ScanResult.BrickedSuccessfully -> "Session started"
        is ScanResult.UnbrickedSuccessfully -> "Unlocked"
        ScanResult.UnpairedTokenRejected -> "Token not recognised"
        is ScanResult.Error -> "Scan failed"
    }
}

private fun outcomeMessage(outcome: TokenScanOutcome): String = when (outcome) {
    is TokenScanOutcome.Paired -> {
        val medium = if (outcome.tokenType == TokenType.NFC) "NFC tag" else "QR card"
        "\"${outcome.label}\" is now a paired $medium."
    }
    is TokenScanOutcome.Failed -> outcome.message
    TokenScanOutcome.Cancelled -> "Scan cancelled."
    is TokenScanOutcome.Scanned -> when (val result = outcome.result) {
        is ScanResult.BrickedSuccessfully -> "Bricked with \"${result.tokenLabel}\"."
        is ScanResult.UnbrickedSuccessfully -> "Unbricked with \"${result.tokenLabel}\"."
        ScanResult.UnpairedTokenRejected ->
            "That token is not paired with this device, so nothing changed."
        is ScanResult.Error -> result.message
    }
}
