package com.brick.app.ui.scan

import android.Manifest
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.brick.app.feature.nfc.ScanIntent
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

@Composable
private fun ScanOverlay(
    state: TokenScanUiState,
    modifier: Modifier,
    onQrPayload: (String) -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .testTag(ScanTestTags.OVERLAY),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title(state),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                text = instructions(state),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            if (state.medium != ScanMedium.NFC) {
                QrCameraScanner(
                    enabled = state.phase == ScanPhase.WAITING_FOR_TOKEN,
                    onQrPayload = onQrPayload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                )
            }

            if (state.phase == ScanPhase.VERIFYING) {
                CircularProgressIndicator(color = Color.White)
            }

            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag(ScanTestTags.CANCEL)
            ) {
                Text(text = "Cancel", color = Color.White)
            }
        }
    }
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

    if (!hasPermission) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Camera access is needed to scan a printed QR token.",
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Allow camera")
            }
        }
        return
    }

    val previewView = remember { PreviewView(context) }

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
            val provider = runCatching { providerFuture.get() }.getOrNull()
                ?: return@addListener
            boundProvider = provider

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(analysisExecutor, QrImageAnalyzer(onPayload = onQrPayload)) }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            boundProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.testTag(ScanTestTags.CAMERA_PREVIEW)
    )
}

@Composable
private fun OutcomeDialog(outcome: TokenScanOutcome, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ScanTestTags.OUTCOME),
        title = { Text(outcomeTitle(outcome)) },
        text = { Text(outcomeMessage(outcome)) },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } }
    )
}

@Composable
private fun PrintableQrDialog(token: GeneratedQrToken, onDone: () -> Unit) {
    val bitmap = remember(token.payload) { QrCodeGenerator.generateQrBitmap(token.payload) }
    AlertDialog(
        onDismissRequest = onDone,
        modifier = Modifier.testTag(ScanTestTags.PRINTABLE_QR),
        title = { Text("Print \"${token.label}\"") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR token ${token.label}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .padding(8.dp)
                    )
                }
                Text(
                    text = "Print or photograph this code and keep it away from your phone. " +
                        "Scanning it is the only way it can unlock a session.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = { Button(onClick = onDone) { Text("Done") } }
    )
}

private fun title(state: TokenScanUiState): String = when {
    state.intent == ScanIntent.PAIR -> "Pair a token"
    state.medium == ScanMedium.NFC -> "Tap your NFC tag"
    state.medium == ScanMedium.QR -> "Scan your QR card"
    else -> "Scan your token"
}

private fun instructions(state: TokenScanUiState): String {
    val nfcHint = when {
        state.medium == ScanMedium.QR -> null
        !state.isNfcAvailable -> "This device has no NFC radio."
        !state.isNfcEnabled -> "NFC is switched off in system settings."
        else -> "Hold the tag against the back of the phone."
    }
    val qrHint = if (state.medium != ScanMedium.NFC) "Or point the camera at your printed QR card." else null
    return listOfNotNull(nfcHint, qrHint).joinToString(" ").ifEmpty { "Present your paired token." }
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
