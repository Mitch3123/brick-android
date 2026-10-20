package com.brick.app.feature.nfc

import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.TokenEntity
import com.brick.app.core.database.TokenType
import com.brick.app.core.security.CryptoUtils
import com.brick.app.feature.home.SessionManager

sealed interface PairingResult {
    data class Paired(val token: TokenEntity) : PairingResult
    data class AlreadyPaired(val token: TokenEntity) : PairingResult
    object RefusedWhileBricked : PairingResult
    data class Error(val message: String) : PairingResult
}

interface TokenPairingUseCase {
    suspend fun pairToken(tokenPayload: String, tokenType: TokenType, label: String): PairingResult
}

/**
 * Stores a physical token as a SHA-256 hash; the raw UID or QR payload is never persisted.
 *
 * Pairing is refused while a session is active: otherwise a user could pair a brand new card
 * mid-session and unbrick with it, which would defeat the lock.
 */
class DefaultTokenPairingUseCase(
    private val db: BrickDatabase,
    private val sessionManager: SessionManager
) : TokenPairingUseCase {

    override suspend fun pairToken(
        tokenPayload: String,
        tokenType: TokenType,
        label: String
    ): PairingResult {
        if (tokenPayload.isBlank()) return PairingResult.Error("Empty token payload")
        return sessionManager.whileUnbricked {
            val tokenHash = CryptoUtils.sha256(tokenPayload)
            db.tokenDao().findByHash(tokenHash)?.let { return@whileUnbricked PairingResult.AlreadyPaired(it) }

            val resolvedLabel = label.trim().ifEmpty {
                when (tokenType) {
                    TokenType.NFC -> "NFC Tag"
                    TokenType.QR -> "QR Card"
                }
            }
            val isFirstToken = db.tokenDao().getTokenCount() == 0
            val entity = TokenEntity(
                tokenHash = tokenHash,
                tokenType = tokenType,
                label = resolvedLabel,
                isPrimary = isFirstToken
            )
            try {
                val id = db.tokenDao().insert(entity)
                PairingResult.Paired(entity.copy(id = id))
            } catch (e: Exception) {
                PairingResult.Error(e.message ?: "Failed to store token")
            }
        } ?: PairingResult.RefusedWhileBricked
    }
}
