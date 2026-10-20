package com.brick.app.feature.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.brick.app.core.common.TestTimeProvider
import com.brick.app.core.common.TimeProvider
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.core.database.TokenEntity
import com.brick.app.core.database.TokenType
import com.brick.app.core.security.CryptoUtils
import com.brick.app.feature.home.SessionManager
import com.brick.app.feature.nfc.DefaultTokenPairingUseCase
import com.brick.app.feature.nfc.DefaultTokenScanController
import com.brick.app.feature.nfc.NfcGateway
import com.brick.app.feature.nfc.PairingResult
import com.brick.app.feature.nfc.ScanMedium
import com.brick.app.feature.nfc.ScanPhase
import com.brick.app.feature.nfc.ScanResult
import com.brick.app.feature.nfc.TokenPairingUseCase
import com.brick.app.feature.nfc.TokenScanOutcome
import com.brick.app.feature.nfc.TokenScanUseCase
import com.brick.app.feature.qr.LuminancePlaneExtractor
import com.brick.app.feature.qr.QrCodeGenerator
import com.brick.app.feature.qr.QrPayloadDecoder
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the physical scanning path that has no Android dependency: real QR encoding, decoding
 * from a camera-shaped luminance plane, and the scan controller state machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhysicalTokenScanTest {

    // ---------------------------------------------------------------------
    // QR generation and decoding
    // ---------------------------------------------------------------------

    @Test
    fun generatedPayload_isPrefixedAndUnique() {
        val first = QrCodeGenerator.generateUniquePayload("Desk Card")
        val second = QrCodeGenerator.generateUniquePayload("Desk Card")

        assertTrue(QrCodeGenerator.isBrickPayload(first))
        assertTrue(first.startsWith("${QrCodeGenerator.PREFIX}DESK_CARD_"))
        assertFalse("Each printed card must carry a distinct payload", first == second)
    }

    @Test
    fun generatedQr_decodesBackToTheExactPayload() {
        val payload = QrCodeGenerator.generateUniquePayload("Primary")

        val decoded = QrPayloadDecoder().decode(
            luminance = renderLuminance(payload, scale = 6),
            width = moduleCount(payload) * 6,
            height = moduleCount(payload) * 6
        )

        assertEquals(payload, decoded)
    }

    @Test
    fun generatedQr_keepsAFourModuleQuietZone() {
        val modules = QrCodeGenerator.encodeToModules("BRICK-QR-V1:QUIET_ZONE")
        val size = modules.size

        for (band in 0 until 4) {
            assertFalse("top row $band must be light", modules[band].any { it })
            assertFalse("bottom row $band must be light", modules[size - 1 - band].any { it })
            assertFalse("left column $band must be light", modules.any { it[band] })
            assertFalse("right column $band must be light", modules.any { it[size - 1 - band] })
        }
    }

    @Test
    fun decoder_rejectsAFrameWithNoCode() {
        val blank = ByteArray(64 * 64) { 0xFF.toByte() }

        assertNull(QrPayloadDecoder().decode(blank, 64, 64))
    }

    // ---------------------------------------------------------------------
    // Camera plane repacking
    // ---------------------------------------------------------------------

    @Test
    fun paddedAndStridedCameraPlane_stillDecodes() {
        val payload = QrCodeGenerator.generateUniquePayload("Strided")
        val scale = 6
        val side = moduleCount(payload) * scale
        val dense = renderLuminance(payload, scale)

        // Shape the plane like a real camera buffer: interleaved samples and padded rows.
        val pixelStride = 2
        val rowStride = side * pixelStride + 37
        val padded = ByteArray(rowStride * side) { 0x7F }
        for (y in 0 until side) {
            for (x in 0 until side) {
                padded[y * rowStride + x * pixelStride] = dense[y * side + x]
            }
        }

        val frame = LuminancePlaneExtractor.extract(
            buffer = ByteBuffer.wrap(padded),
            rowStride = rowStride,
            pixelStride = pixelStride,
            left = 0,
            top = 0,
            width = side,
            height = side
        )

        assertNotNull(frame)
        assertEquals(side * side, frame!!.data.size)
        assertEquals(payload, QrPayloadDecoder().decode(frame.data, frame.width, frame.height))
    }

    @Test
    fun truncatedCameraPlane_isRejectedInsteadOfReadingOutOfBounds() {
        val rowStride = 64
        val truncated = ByteArray(rowStride * 10)

        assertNull(
            LuminancePlaneExtractor.extract(
                buffer = ByteBuffer.wrap(truncated),
                rowStride = rowStride,
                pixelStride = 1,
                left = 0,
                top = 0,
                width = 64,
                height = 32
            )
        )
    }

    // ---------------------------------------------------------------------
    // Scan controller state machine
    // ---------------------------------------------------------------------

    @Test
    fun nfcScan_routesThroughTheScanUseCaseAndReportsTheResult() {
        val harness = harness()

        harness.controller.requestUnbrickScan()
        assertTrue(harness.controller.state.value.isScanning)
        assertTrue("NFC radio must be claimed for a tag scan", harness.nfc.dispatchEnabled)

        harness.controller.onNfcTagUid("04a1b2c3")

        assertEquals(listOf("04a1b2c3" to TokenType.NFC), harness.scanUseCase.calls)
        assertEquals(
            TokenScanOutcome.Scanned(ScanResult.UnbrickedSuccessfully("Primary Card")),
            harness.controller.state.value.lastOutcome
        )
        assertFalse(harness.controller.state.value.isScanning)
        assertEquals(ScanPhase.SHOWING_RESULT, harness.controller.state.value.phase)
    }

    @Test
    fun unpairedToken_isReportedAndNeverPairedBehindTheScenes() {
        val harness = harness()
        harness.scanUseCase.result = ScanResult.UnpairedTokenRejected

        harness.controller.requestUnbrickScan()
        harness.controller.onNfcTagUid("deadbeef")

        assertEquals(
            TokenScanOutcome.Scanned(ScanResult.UnpairedTokenRejected),
            harness.controller.state.value.lastOutcome
        )
        assertTrue("A rejected scan must not pair anything", harness.pairingUseCase.calls.isEmpty())
    }

    @Test
    fun sessionGuardRejection_isReportedAndLeavesNoStuckScan() {
        val harness = harness()
        harness.scanUseCase.failWith =
            IllegalStateException("Pair a token and configure a mode before bricking")

        harness.controller.requestBrickScan()
        harness.controller.onNfcTagUid("04a1b2c3")

        assertEquals(
            TokenScanOutcome.Failed("Pair a token and configure a mode before bricking"),
            harness.controller.state.value.lastOutcome
        )
        assertFalse(harness.controller.state.value.isScanning)
        assertEquals(ScanPhase.SHOWING_RESULT, harness.controller.state.value.phase)

        // The single-flight claim must have been released, so a retry still works.
        harness.scanUseCase.failWith = null
        harness.controller.requestBrickScan()
        harness.controller.onNfcTagUid("0b0b0b0b")
        assertTrue(harness.controller.state.value.lastOutcome is TokenScanOutcome.Scanned)
    }

    @Test
    fun replayedTagDelivery_togglesOnlyOnce() {
        val harness = harness()

        harness.controller.requestUnbrickScan()
        harness.controller.onNfcTagUid("04a1b2c3")
        // Radio redelivery plus a tag-launch intent for the very same physical tap.
        harness.controller.onNfcTagUid("04a1b2c3")
        harness.time.advanceElapsedRealtime(2_500L)
        harness.controller.onNfcTagUid("04a1b2c3")

        assertEquals(1, harness.scanUseCase.calls.size)
    }

    @Test
    fun theSameTagAfterTheDebounceWindow_togglesAgain() {
        val harness = harness()

        harness.controller.requestUnbrickScan()
        harness.controller.onNfcTagUid("04a1b2c3")
        harness.time.advanceElapsedRealtime(3_001L)
        harness.controller.onNfcTagUid("04a1b2c3")

        assertEquals(2, harness.scanUseCase.calls.size)
    }

    @Test
    fun tagTappedWithNoPendingRequest_stillAuthenticatesThroughTheUseCase() {
        val harness = harness()

        harness.controller.onNfcTagUid("04a1b2c3")

        assertEquals(listOf("04a1b2c3" to TokenType.NFC), harness.scanUseCase.calls)
        assertNotNull(harness.controller.state.value.lastOutcome)
    }

    @Test
    fun qrPayloadDuringAnNfcOnlyRequest_isIgnored() {
        val harness = harness()

        harness.controller.requestPairNfcToken("Keyring")
        harness.controller.onQrPayload("BRICK-QR-V1:SOMETHING_ELSE")

        assertTrue(harness.scanUseCase.calls.isEmpty())
        assertTrue(harness.pairingUseCase.calls.isEmpty())
        assertEquals(ScanMedium.NFC, harness.controller.state.value.medium)
    }

    @Test
    fun qrPayloadWithNoActiveRequest_isIgnored() {
        val harness = harness()

        harness.controller.onQrPayload("BRICK-QR-V1:STRAY_CARD")

        assertTrue(harness.scanUseCase.calls.isEmpty())
        assertNull(harness.controller.state.value.lastOutcome)
    }

    @Test
    fun pairingIsRefusedWhileBricked_forBothMediums() {
        val harness = harness(isBricked = true)

        harness.controller.requestPairNfcToken("Sneaky Tag")
        val nfcOutcome = harness.controller.state.value.lastOutcome
        harness.controller.requestPairQrToken("Sneaky Card")
        val qrOutcome = harness.controller.state.value.lastOutcome

        assertTrue(nfcOutcome is TokenScanOutcome.Failed)
        assertTrue(qrOutcome is TokenScanOutcome.Failed)
        assertFalse("Refused pairing must not open the scanner", harness.controller.state.value.isScanning)
        assertTrue(harness.pairingUseCase.calls.isEmpty())
        assertNull(harness.controller.state.value.generatedQr)
    }

    @Test
    fun sessionStartedBetweenRequestAndTap_refusesPairingAtTapTime() {
        val bricked = BrickedFlag(false)
        val harness = harness(brickedFlag = bricked)

        harness.controller.requestPairNfcToken("Keyring")
        assertTrue("Pairing may be requested while unbricked", harness.controller.state.value.isScanning)

        // A schedule (or another token tap) starts a session before the tag is presented.
        bricked.value = true
        harness.controller.onNfcTagUid("0a0b0c0d")

        val outcome = harness.controller.state.value.lastOutcome
        assertTrue("Refusal must reach the UI", outcome is TokenScanOutcome.Failed)
        assertFalse(harness.controller.state.value.isScanning)
    }

    @Test
    fun tagTappedWithNoPendingRequest_canNeverPair() {
        val harness = harness()

        harness.controller.onNfcTagUid("04a1b2c3")

        assertTrue(
            "An intent-delivered tag must only ever authenticate",
            harness.pairingUseCase.calls.isEmpty()
        )
        assertEquals(1, harness.scanUseCase.calls.size)
    }

    @Test
    fun qrPairing_generatesAPrintablePayloadAndPairsIt() {
        val harness = harness()

        harness.controller.requestPairQrToken("Desk Card")

        val generated = harness.controller.state.value.generatedQr
        assertNotNull(generated)
        assertTrue(QrCodeGenerator.isBrickPayload(generated!!.payload))
        assertEquals(listOf(generated.payload to TokenType.QR), harness.pairingUseCase.calls)
        assertEquals(TokenScanOutcome.Paired("Desk Card", TokenType.QR), harness.controller.state.value.lastOutcome)

        harness.controller.acknowledgeGeneratedQr()
        assertNull(harness.controller.state.value.generatedQr)
    }

    @Test
    fun nfcPairing_storesTheTagUnderTheRequestedLabel() {
        val harness = harness()

        harness.controller.requestPairNfcToken("Keyring")
        harness.controller.onNfcTagUid("0a0b0c0d")

        assertEquals(listOf("0a0b0c0d" to TokenType.NFC), harness.pairingUseCase.calls)
        assertEquals("Keyring", harness.pairingUseCase.lastLabel)
        assertTrue(harness.scanUseCase.calls.isEmpty())
    }

    @Test
    fun dismiss_cancelsTheRequestAndReleasesTheRadio() {
        val harness = harness()

        harness.controller.requestUnbrickScan()
        harness.controller.dismiss()

        assertFalse(harness.controller.state.value.isScanning)
        assertEquals(TokenScanOutcome.Cancelled, harness.controller.state.value.lastOutcome)
        assertFalse(harness.nfc.dispatchEnabled)

        harness.controller.acknowledgeOutcome()
        assertNull(harness.controller.state.value.lastOutcome)
        assertEquals(ScanPhase.IDLE, harness.controller.state.value.phase)
    }

    @Test
    fun hostLifecycle_claimsAndReleasesTheRadio() {
        val harness = harness()

        harness.controller.onHostResumed()
        assertTrue(harness.nfc.dispatchEnabled)

        harness.controller.onHostPaused()
        assertFalse(harness.nfc.dispatchEnabled)
    }

    // ---------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------

    private class RecordingScanUseCase : TokenScanUseCase {
        val calls = mutableListOf<Pair<String, TokenType>>()
        var result: ScanResult = ScanResult.UnbrickedSuccessfully("Primary Card")
        var failWith: Exception? = null
        override suspend fun handleTokenScanned(tokenPayload: String, tokenType: TokenType): ScanResult {
            calls += tokenPayload to tokenType
            failWith?.let { throw it }
            return result
        }
    }

    private class BrickedFlag(initial: Boolean) {
        @Volatile
        var value: Boolean = initial
    }

    /** Mirrors [DefaultTokenPairingUseCase]'s execution-time refusal while a session is active. */
    private class RecordingPairingUseCase(private val bricked: BrickedFlag) : TokenPairingUseCase {
        val calls = mutableListOf<Pair<String, TokenType>>()
        var lastLabel: String? = null
        override suspend fun pairToken(
            tokenPayload: String,
            tokenType: TokenType,
            label: String
        ): PairingResult {
            if (bricked.value) return PairingResult.RefusedWhileBricked
            calls += tokenPayload to tokenType
            lastLabel = label
            return PairingResult.Paired(
                TokenEntity(id = 1, tokenHash = "hash", tokenType = tokenType, label = label)
            )
        }
    }

    private class FakeNfcGateway : NfcGateway {
        override val isAvailable = true
        override val isEnabled = true
        var dispatchEnabled = false
        override fun enableDispatch() { dispatchEnabled = true }
        override fun disableDispatch() { dispatchEnabled = false }
    }

    private class MutableTimeProvider : TimeProvider {
        private var elapsed = 10_000L
        override fun currentTimeMillis(): Long = 1_700_000_000_000L + elapsed
        override fun elapsedRealtime(): Long = elapsed
        fun advanceElapsedRealtime(deltaMs: Long) { elapsed += deltaMs }
    }

    private class Harness(
        val controller: DefaultTokenScanController,
        val scanUseCase: RecordingScanUseCase,
        val pairingUseCase: RecordingPairingUseCase,
        val nfc: FakeNfcGateway,
        val time: MutableTimeProvider
    )

    private fun harness(
        isBricked: Boolean = false,
        brickedFlag: BrickedFlag = BrickedFlag(isBricked)
    ): Harness {
        val scanUseCase = RecordingScanUseCase()
        val pairingUseCase = RecordingPairingUseCase(brickedFlag)
        val nfc = FakeNfcGateway()
        val time = MutableTimeProvider()
        return Harness(
            controller = DefaultTokenScanController(
                scope = CoroutineScope(UnconfinedTestDispatcher()),
                scanUseCase = scanUseCase,
                pairingUseCase = pairingUseCase,
                isBricked = { brickedFlag.value },
                nfc = nfc,
                timeProvider = time
            ),
            scanUseCase = scanUseCase,
            pairingUseCase = pairingUseCase,
            nfc = nfc,
            time = time
        )
    }

    private fun moduleCount(payload: String): Int = QrCodeGenerator.encodeToModules(payload).size

    /** Renders [payload] as a dense 8-bit grayscale frame, as a camera would see a printed card. */
    private fun renderLuminance(payload: String, scale: Int): ByteArray {
        val modules = QrCodeGenerator.encodeToModules(payload)
        val side = modules.size * scale
        val out = ByteArray(side * side)
        for (y in 0 until side) {
            val row = modules[y / scale]
            for (x in 0 until side) {
                out[y * side + x] = if (row[x / scale]) 0x00 else 0xFF.toByte()
            }
        }
        return out
    }
}

/**
 * Pairing must be impossible while a session is active, otherwise a user could pair a new card
 * mid-session and unlock with it. Verified against the real database and session state machine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TokenPairingGuardTest {

    private lateinit var context: Context
    private lateinit var db: BrickDatabase
    private lateinit var sessionManager: SessionManager
    private lateinit var pairingUseCase: DefaultTokenPairingUseCase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BrickDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val timeProvider = TestTimeProvider(
            initialCurrentTimeMillis = 1_700_000_000_000L,
            initialElapsedRealtime = 10_000L
        )
        TestTimeProvider.setInstance(timeProvider)
        sessionManager = SessionManager.createWithDatabase(context, timeProvider, db)
        pairingUseCase = DefaultTokenPairingUseCase(db, sessionManager)
    }

    @After
    fun tearDown() {
        db.close()
        TestTimeProvider.setInstance(null)
    }

    @Test
    fun firstPairedToken_isStoredHashedAndMarkedPrimary() = runBlocking {
        val payload = "04a1b2c3d4e5f6"

        val result = pairingUseCase.pairToken(payload, TokenType.NFC, "Primary Card")

        assertTrue(result is PairingResult.Paired)
        val stored = db.tokenDao().getPrimaryToken()
        assertNotNull(stored)
        assertEquals(CryptoUtils.sha256(payload), stored!!.tokenHash)
        assertEquals("Primary Card", stored.label)
        assertFalse("The raw UID must never be persisted", stored.tokenHash.contains(payload))
    }

    @Test
    fun pairingTheSameTokenTwice_isReportedNotDuplicated() = runBlocking {
        val payload = "BRICK-QR-V1:DESK_CARD_01"
        pairingUseCase.pairToken(payload, TokenType.QR, "Desk Card")

        val second = pairingUseCase.pairToken(payload, TokenType.QR, "Desk Card Copy")

        assertTrue(second is PairingResult.AlreadyPaired)
        assertEquals(1, db.tokenDao().getTokenCount())
    }

    @Test
    fun pairingWhileBricked_isRefusedSoANewCardCannotUnlockTheSession() = runBlocking {
        pairingUseCase.pairToken("04a1b2c3d4e5f6", TokenType.NFC, "Primary Card")
        db.modeDao().insert(
            ModeEntity(
                name = "Deep Work",
                modeType = ModeType.BLOCK_LIST,
                packageNames = setOf("com.example.social"),
                isDefault = true
            )
        )
        sessionManager.refreshSession()
        sessionManager.brick()
        assertTrue(sessionManager.isBricked())

        val nfcResult = pairingUseCase.pairToken("99998888", TokenType.NFC, "Sneaky Tag")
        val qrResult = pairingUseCase.pairToken(
            QrCodeGenerator.generateUniquePayload("Sneaky"), TokenType.QR, "Sneaky Card"
        )

        assertEquals(PairingResult.RefusedWhileBricked, nfcResult)
        assertEquals(PairingResult.RefusedWhileBricked, qrResult)
        assertEquals("No token may be added mid-session", 1, db.tokenDao().getTokenCount())
        assertTrue("The session must still be active", sessionManager.isBricked())
    }

    @Test
    fun anAlreadyOpenedPairingRequestCannotPairAfterABrick() = runBlocking {
        pairingUseCase.pairToken("04a1b2c3d4e5f6", TokenType.NFC, "Primary Card")
        val modeId = db.modeDao().insert(ModeEntity(
            name = "Deep Work", modeType = ModeType.BLOCK_LIST,
            packageNames = setOf("com.example.social"), isDefault = true
        ))
        sessionManager.brick(modeId = modeId)

        // The screen may have been opened while idle; the actual insert must be checked
        // against the active session, not the UI state that started the request.
        val result = pairingUseCase.pairToken("later-tag", TokenType.NFC, "Later Tag")
        assertEquals(PairingResult.RefusedWhileBricked, result)
        assertEquals(1, db.tokenDao().getTokenCount())
    }

    @Test
    fun blankPayload_isRejected() = runBlocking {
        assertTrue(pairingUseCase.pairToken("   ", TokenType.NFC, "Blank") is PairingResult.Error)
        assertEquals(0, db.tokenDao().getTokenCount())
    }
}
