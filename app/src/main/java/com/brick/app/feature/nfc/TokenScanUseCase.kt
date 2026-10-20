package com.brick.app.feature.nfc

import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.TokenType
import com.brick.app.core.database.UnbrickTrigger
import com.brick.app.core.security.CryptoUtils
import com.brick.app.feature.home.SessionManager

sealed interface ScanResult {
    data class BrickedSuccessfully(val tokenLabel: String) : ScanResult
    data class UnbrickedSuccessfully(val tokenLabel: String) : ScanResult
    object UnpairedTokenRejected : ScanResult
    data class Error(val message: String) : ScanResult
}

interface TokenScanUseCase {
    suspend fun handleTokenScanned(tokenPayload: String, tokenType: TokenType): ScanResult
}

class DefaultTokenScanUseCase(
    private val db: BrickDatabase,
    private val sessionManager: SessionManager
) : TokenScanUseCase {

    override suspend fun handleTokenScanned(tokenPayload: String, tokenType: TokenType): ScanResult {
        if (tokenPayload.isBlank()) return ScanResult.Error("Empty token payload")

        val tokenHash = CryptoUtils.sha256(tokenPayload)
        val token = db.tokenDao().findByHash(tokenHash)

        if (token == null || token.tokenType != tokenType) {
            // Unpaired token MUST be strictly rejected
            return ScanResult.UnpairedTokenRejected
        }

        return if (sessionManager.isBricked()) {
            val trigger = when (token.tokenType) {
                TokenType.NFC -> UnbrickTrigger.NFC_TAG
                TokenType.QR -> UnbrickTrigger.QR_CODE
            }
            sessionManager.unbrick(trigger = trigger, triggerToken = token)
            ScanResult.UnbrickedSuccessfully(token.label)
        } else {
            sessionManager.brick()
            ScanResult.BrickedSuccessfully(token.label)
        }
    }
}
