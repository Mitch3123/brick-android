package com.brick.app.feature.nfc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.brick.app.core.database.TokenType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which physical medium a scan request accepts. */
enum class ScanMedium { NFC, QR, ANY }

/** Why the scanner was opened. */
enum class ScanIntent { AUTHENTICATE, PAIR }

enum class ScanPhase { IDLE, WAITING_FOR_TOKEN, VERIFYING, SHOWING_RESULT }

sealed interface TokenScanOutcome {
    data class Paired(val label: String, val tokenType: TokenType) : TokenScanOutcome
    data class Scanned(val result: ScanResult) : TokenScanOutcome
    data class Failed(val message: String) : TokenScanOutcome
    object Cancelled : TokenScanOutcome
}

/**
 * A freshly paired QR token. Only the payload is retained; the bitmap is rendered on demand
 * from [com.brick.app.feature.qr.QrCodeGenerator] so no large object sits in UI state.
 */
data class GeneratedQrToken(val label: String, val payload: String)

data class TokenScanUiState(
    val isScanning: Boolean = false,
    val intent: ScanIntent? = null,
    val medium: ScanMedium = ScanMedium.ANY,
    val pendingLabel: String = "",
    val phase: ScanPhase = ScanPhase.IDLE,
    val isNfcAvailable: Boolean = false,
    val isNfcEnabled: Boolean = false,
    val lastOutcome: TokenScanOutcome? = null,
    val generatedQr: GeneratedQrToken? = null
)

/**
 * Entry point the UI uses to drive physical token scanning.
 *
 * Every authentication scan is resolved by [TokenScanUseCase], so an unpaired tag or QR card can
 * never change session state, and there is no code path here that unbricks without a verified token.
 */
interface TokenScanController {

    val state: StateFlow<TokenScanUiState>

    /** Opens the scanner to brick or unbrick, whichever the current session state implies. */
    fun requestScan(medium: ScanMedium = ScanMedium.ANY)

    /** Bind to "TAP TO UNBRICK". Opens the scanner; the paired token decides the outcome. */
    fun requestUnbrickScan() = requestScan()

    /** Symmetric entry for starting a session with a token tap. */
    fun requestBrickScan() = requestScan()

    /** Pair a physical NFC tag under [label]. Refused while a session is active. */
    fun requestPairNfcToken(label: String)

    /** Generate, pair and display a printable QR token under [label]. Refused while a session is active. */
    fun requestPairQrToken(label: String)

    /** Feeds a decoded QR payload into the active request. */
    fun onQrPayload(payload: String)

    /** User dismissed the scanner. */
    fun dismiss()

    /** Clears [TokenScanUiState.lastOutcome] once the UI has shown it. */
    fun acknowledgeOutcome()

    /** Dismisses the printable QR sheet. */
    fun acknowledgeGeneratedQr()
}

/**
 * Inert controller used wherever no activity is hosting the scanner (previews, isolated
 * Compose tests). Requests are dropped rather than crashing the screen under test.
 */
object NoOpTokenScanController : TokenScanController {
    private val _state = MutableStateFlow(TokenScanUiState())
    override val state: StateFlow<TokenScanUiState> = _state.asStateFlow()
    override fun requestScan(medium: ScanMedium) = Unit
    override fun requestPairNfcToken(label: String) = Unit
    override fun requestPairQrToken(label: String) = Unit
    override fun onQrPayload(payload: String) = Unit
    override fun dismiss() = Unit
    override fun acknowledgeOutcome() = Unit
    override fun acknowledgeGeneratedQr() = Unit
}

val LocalTokenScanController = staticCompositionLocalOf<TokenScanController> { NoOpTokenScanController }

/** The controller provided by the hosting activity. Never null. */
@Composable
@ReadOnlyComposable
fun rememberTokenScanController(): TokenScanController = LocalTokenScanController.current
