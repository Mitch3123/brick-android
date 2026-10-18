package com.brick.app

import com.brick.app.core.security.CryptoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Unit tests verifying SHA-256 token hashing consistency, application salt integration,
 * constant-time string comparison, and collision resistance.
 */
class CryptoUtilsTest {

    @Test
    fun hashingConsistency_sameInput_producesIdenticalHash() {
        val input = "NFC_CARD_UID_04A1B2C3D4"
        val hash1 = CryptoUtils.sha256(input)
        val hash2 = CryptoUtils.sha256(input)

        assertEquals("Hashes of identical string inputs must be strictly identical", hash1, hash2)
        assertEquals("SHA-256 hexadecimal output must be exactly 64 characters long", 64, hash1.length)
        assertTrue("SHA-256 hash must contain only lowercase hexadecimal characters", hash1.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun hashingConsistency_byteArrayOverload_matchesStringOverload() {
        val text = "BRICK-QR-V1:PRINTED_DESK_CARD_9999"
        val stringHash = CryptoUtils.sha256(text)
        val byteHash = CryptoUtils.sha256(text.toByteArray(Charsets.UTF_8))

        assertEquals("ByteArray and String overloads must produce identical hashes for equivalent bytes", stringHash, byteHash)
    }

    @Test
    fun saltIntegration_hashDiffersFromUnsaltedSha256() {
        val rawInput = "04a1b2c3d4e5f6"
        val saltedAppHash = CryptoUtils.sha256(rawInput)

        // Compute unsalted SHA-256 manually
        val unsaltedDigest = MessageDigest.getInstance("SHA-256")
            .digest(rawInput.toByteArray(Charsets.UTF_8))
        val unsaltedHash = unsaltedDigest.joinToString("") { "%02x".format(it) }

        assertNotEquals(
            "CryptoUtils hash must differ from unsalted SHA-256 to ensure salt is applied",
            unsaltedHash,
            saltedAppHash
        )
    }

    @Test
    fun saltIntegration_matchesSha256WithAppSaltPrepended() {
        val rawInput = "TOKEN_PAYLOAD_ABC"
        val expectedInput = (CryptoUtils.APP_SALT + rawInput).toByteArray(Charsets.UTF_8)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(expectedInput)
        val expectedHash = expectedDigest.joinToString("") { "%02x".format(it) }

        val actualHash = CryptoUtils.sha256(rawInput)

        assertEquals("CryptoUtils hash must match SHA-256 of APP_SALT + input", expectedHash, actualHash)
    }

    @Test
    fun differentInputs_produceDifferentHashes() {
        val hashA = CryptoUtils.sha256("TOKEN_A")
        val hashB = CryptoUtils.sha256("TOKEN_B")
        val hashEmpty = CryptoUtils.sha256("")
        val hashSingleBitDiff = CryptoUtils.sha256(byteArrayOf(0x01, 0x02, 0x03))
        val hashSingleBitDiff2 = CryptoUtils.sha256(byteArrayOf(0x01, 0x02, 0x04))

        assertNotEquals(hashA, hashB)
        assertNotEquals(hashA, hashEmpty)
        assertNotEquals(hashSingleBitDiff, hashSingleBitDiff2)
    }

    @Test
    fun constantTimeEquals_stringComparison_evaluatesCorrectly() {
        val hash = CryptoUtils.sha256("TEST_TOKEN")
        val identical = CryptoUtils.sha256("TEST_TOKEN")
        val different = CryptoUtils.sha256("OTHER_TOKEN")

        assertTrue("Identical strings must return true", CryptoUtils.constantTimeEquals(hash, identical))
        assertFalse("Different strings of equal length must return false", CryptoUtils.constantTimeEquals(hash, different))
        assertFalse("Different strings of different length must return false", CryptoUtils.constantTimeEquals(hash, "short_hash"))
        assertTrue("Both null strings must return true", CryptoUtils.constantTimeEquals(null as String?, null as String?))
        assertFalse("Null compared with non-null must return false", CryptoUtils.constantTimeEquals(hash, null))
        assertFalse("Non-null compared with null must return false", CryptoUtils.constantTimeEquals(null, hash))
        assertTrue("Both empty strings must return true", CryptoUtils.constantTimeEquals("", ""))
    }

    @Test
    fun constantTimeEquals_byteArrayComparison_evaluatesCorrectly() {
        val bytes1 = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val bytes2 = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val bytesDiff = byteArrayOf(0x01, 0x02, 0x03, 0x05)
        val bytesShort = byteArrayOf(0x01, 0x02)

        assertTrue(CryptoUtils.constantTimeEquals(bytes1, bytes2))
        assertFalse(CryptoUtils.constantTimeEquals(bytes1, bytesDiff))
        assertFalse(CryptoUtils.constantTimeEquals(bytes1, bytesShort))
        assertTrue(CryptoUtils.constantTimeEquals(null as ByteArray?, null as ByteArray?))
        assertFalse(CryptoUtils.constantTimeEquals(bytes1, null))
    }
}
