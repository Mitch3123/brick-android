package com.brick.app.feature.nfc

import android.util.Log
import com.brick.app.core.common.SystemTimeProvider
import com.brick.app.core.common.TimeProvider
import com.brick.app.core.database.TokenType
import com.brick.app.feature.qr.QrCodeGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the scanner state machine for a hosting activity.
 *
 * Authentication always goes through [TokenScanUseCase]; pairing always through
 * [TokenPairingUseCase]. There is deliberately no method that changes session state directly.
 */
class DefaultTokenScanController(
    private val scope: CoroutineScope,
    private val scanUseCase: TokenScanUseCase,
    private val pairingUseCase: TokenPairingUseCase,
    private val isBricked: () -> Boolean,
    private val nfc: NfcGateway = UnavailableNfcGateway,
    private val timeProvider: TimeProvider = SystemTimeProvider()
) : TokenScanController {

    private companion object {
        const val TAG = "TokenScanController"

        /**
         * A single physical presentation of a token routinely produces several deliveries:
         * the NFC radio redelivers a held tag, and a tag-launch intent can arrive alongside a
         * foreground dispatch. Within this window the same token is treated as one tap.
         */
        const val DUPLICATE_WINDOW_MS = 3_000L
    }

    private val _state = MutableStateFlow(
        TokenScanUiState(isNfcAvailable = nfc.isAvailable, isNfcEnabled = nfc.isEnabled)
    )
    override val state: StateFlow<TokenScanUiState> = _state.asStateFlow()

    /**
     * Single-flight claim. A physical tag can be delivered twice in quick succession (NFC
     * dispatch plus a redelivered intent, or two camera frames); without an atomic claim both
     * deliveries would reach the use case and toggle the session twice.
     */
    private val inFlight = AtomicBoolean(false)

    private val lastHandled = AtomicReference<HandledToken?>(null)

    private data class HandledToken(val payload: String, val tokenType: TokenType, val atElapsedMs: Long)

    override fun requestScan(medium: ScanMedium) {
        openRequest(ScanIntent.AUTHENTICATE, medium, label = "")
    }

    override fun requestPairNfcToken(label: String) {
        if (refusePairingWhileBricked()) return
        openRequest(ScanIntent.PAIR, ScanMedium.NFC, label)
    }

    override fun requestPairQrToken(label: String) {
        if (refusePairingWhileBricked()) return
        if (!inFlight.compareAndSet(false, true)) return
        val payload = QrCodeGenerator.generateUniquePayload(label)
        scope.launch {
            try {
                _state.update { it.withRadioState().copy(phase = ScanPhase.VERIFYING) }
                val result = pairingUseCase.pairToken(payload, TokenType.QR, label)
                _state.update { current ->
                    current.copy(
                        isScanning = false,
                        intent = null,
                        phase = ScanPhase.SHOWING_RESULT,
                        lastOutcome = result.toOutcome(),
                        generatedQr = (result as? PairingResult.Paired)
                            ?.let { GeneratedQrToken(it.token.label, payload) }
                    )
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** Called by the host activity when the NFC radio delivers a tag UID. */
    fun onNfcTagUid(uid: String) {
        if (uid.isBlank()) return
        val snapshot = _state.value
        if (snapshot.isScanning && snapshot.medium == ScanMedium.QR) {
            Log.d(TAG, "Ignoring NFC tag during QR-only request")
            return
        }
        // A tag tapped with no pending request keeps the physical tap UX: it opens an
        // authenticate request explicitly, so the scanner UI reports the outcome, and the
        // token is still resolved only by [TokenScanUseCase].
        val intent = if (snapshot.isScanning) snapshot.intent ?: ScanIntent.AUTHENTICATE else ScanIntent.AUTHENTICATE
        if (!snapshot.isScanning) openRequest(ScanIntent.AUTHENTICATE, ScanMedium.NFC, label = "")
        handleToken(uid, TokenType.NFC, intent, snapshot.pendingLabel)
    }

    override fun onQrPayload(payload: String) {
        if (payload.isBlank()) return
        val snapshot = _state.value
        if (!snapshot.isScanning || snapshot.medium == ScanMedium.NFC) {
            Log.d(TAG, "Ignoring QR payload: no QR-capable request active")
            return
        }
        handleToken(payload, TokenType.QR, snapshot.intent ?: ScanIntent.AUTHENTICATE, snapshot.pendingLabel)
    }

    override fun dismiss() {
        nfc.disableDispatch()
        _state.update {
            it.withRadioState().copy(
                isScanning = false,
                intent = null,
                pendingLabel = "",
                phase = ScanPhase.IDLE,
                lastOutcome = if (it.isScanning) TokenScanOutcome.Cancelled else it.lastOutcome
            )
        }
    }

    override fun acknowledgeOutcome() {
        _state.update {
            it.copy(
                lastOutcome = null,
                phase = if (it.isScanning) it.phase else ScanPhase.IDLE
            )
        }
    }

    override fun acknowledgeGeneratedQr() {
        _state.update { it.copy(generatedQr = null) }
    }

    /** Host activity lifecycle hooks: the NFC radio is only claimed while the activity is resumed. */
    fun onHostResumed() {
        _state.update { it.withRadioState() }
        // Claim the radio for the whole foreground lifetime: a tag tapped with no pending
        // request is still authenticated through [TokenScanUseCase] rather than reaching
        // the system tag dispatcher.
        nfc.enableDispatch()
    }

    fun onHostPaused() {
        nfc.disableDispatch()
    }

    private fun openRequest(intent: ScanIntent, medium: ScanMedium, label: String) {
        if (medium != ScanMedium.QR) nfc.enableDispatch()
        _state.update {
            it.withRadioState().copy(
                isScanning = true,
                intent = intent,
                medium = medium,
                pendingLabel = label,
                phase = ScanPhase.WAITING_FOR_TOKEN,
                lastOutcome = null
            )
        }
    }

    private fun handleToken(payload: String, tokenType: TokenType, intent: ScanIntent, label: String) {
        if (!inFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Dropping duplicate token delivery while a scan is already being verified")
            return
        }
        if (isDuplicateDelivery(payload, tokenType)) {
            Log.d(TAG, "Dropping replayed delivery of the same token within the debounce window")
            inFlight.set(false)
            return
        }
        _state.update { it.copy(phase = ScanPhase.VERIFYING) }
        scope.launch {
            try {
                // A session guard (no paired token, no configured mode) surfaces as an exception
                // from the use case. It is reported, never swallowed into a silent state change.
                val outcome = try {
                    when (intent) {
                        ScanIntent.PAIR -> pairingUseCase.pairToken(payload, tokenType, label).toOutcome()
                        ScanIntent.AUTHENTICATE -> TokenScanOutcome.Scanned(
                            scanUseCase.handleTokenScanned(payload, tokenType)
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Token scan refused: ${e.message}")
                    TokenScanOutcome.Failed(e.message ?: "Could not start a session with this token")
                }
                _state.update {
                    it.withRadioState().copy(
                        isScanning = false,
                        intent = null,
                        pendingLabel = "",
                        phase = ScanPhase.SHOWING_RESULT,
                        lastOutcome = outcome
                    )
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * True when [payload] is the same token the previous delivery already resolved, within
     * [DUPLICATE_WINDOW_MS]. Uses the monotonic clock so a wall-clock change cannot widen or
     * collapse the window.
     */
    private fun isDuplicateDelivery(payload: String, tokenType: TokenType): Boolean {
        val now = timeProvider.elapsedRealtime()
        val previous = lastHandled.get()
        val isReplay = previous != null &&
            previous.payload == payload &&
            previous.tokenType == tokenType &&
            now - previous.atElapsedMs in 0 until DUPLICATE_WINDOW_MS
        if (isReplay) return true
        lastHandled.set(HandledToken(payload, tokenType, now))
        return false
    }

    private fun refusePairingWhileBricked(): Boolean {
        if (!isBricked()) return false
        Log.w(TAG, "Pairing refused: a session is active")
        _state.update {
            it.withRadioState().copy(
                isScanning = false,
                intent = null,
                phase = ScanPhase.SHOWING_RESULT,
                lastOutcome = TokenScanOutcome.Failed("Unbrick with a paired token before pairing a new one")
            )
        }
        return true
    }

    private fun TokenScanUiState.withRadioState(): TokenScanUiState =
        copy(isNfcAvailable = nfc.isAvailable, isNfcEnabled = nfc.isEnabled)

    private fun PairingResult.toOutcome(): TokenScanOutcome = when (this) {
        is PairingResult.Paired -> TokenScanOutcome.Paired(token.label, token.tokenType)
        is PairingResult.AlreadyPaired -> TokenScanOutcome.Failed("\"${token.label}\" is already paired")
        PairingResult.RefusedWhileBricked ->
            TokenScanOutcome.Failed("Unbrick with a paired token before pairing a new one")
        is PairingResult.Error -> TokenScanOutcome.Failed(message)
    }
}
