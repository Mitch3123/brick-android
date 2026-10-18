package com.brick.app

import com.brick.app.core.common.TestTimeProvider
import com.brick.app.core.common.TimeProvider
import com.brick.app.core.security.CryptoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Empirical Adversarial Test Suite for Milestone 1 Security & Time Providers.
 *
 * Stress-tests:
 * 1. Cryptographic hashing consistency, application salt integration, and avalanche behavior.
 * 2. Constant-time equality under timing and edge-case inputs (nulls, empty, extreme lengths).
 * 3. Time provider clock separation, immunity to wall-clock manipulation, and negative delta rejection.
 * 4. Realistic mid-cooldown reboot survival verifying no bypass via clock jumps or reboots.
 */
class CryptoAndTimeAdversarialTest {

    // =========================================================================
    // 1. CryptoUtils SHA-256 & Salt Adversarial Tests
    // =========================================================================

    @Test
    fun sha256_exactSaltIncorporation_matchesPrependSpecification() {
        val testInputs = listOf(
            "",
            "04a1b2c3d4",
            "BRICK-QR-V1:PRINTED_DESK_CARD_9999",
            "A".repeat(10_000),
            "\u0000\u0001\u0002\u00FF",
            "🔥 Brick Offline Focus Mode 🛡️"
        )

        val md = MessageDigest.getInstance("SHA-256")
        for (input in testInputs) {
            val appHash = CryptoUtils.sha256(input)

            // Independent oracle: manual digest of (APP_SALT + input)
            md.reset()
            md.update("BRICK_OFFLINE_SALT_V1_SECURE".toByteArray(Charsets.UTF_8))
            val expectedBytes = md.digest(input.toByteArray(Charsets.UTF_8))
            val expectedHex = expectedBytes.joinToString("") { "%02x".format(it) }

            assertEquals("Hash must strictly match SHA-256(SALT + input)", expectedHex, appHash)
            assertEquals("Hash output must be 64 hex characters", 64, appHash.length)
            assertTrue("Hash must contain only lowercase hex [0-9a-f]", appHash.matches(Regex("^[0-9a-f]{64}$")))
        }
    }

    @Test
    fun sha256_avalancheEffect_singleBitFlipCausesMassiveHashDifference() {
        val random = Random(42)
        var totalFlippedBits = 0
        val iterations = 500

        for (i in 0 until iterations) {
            val original = ByteArray(32).also { random.nextBytes(it) }
            val modified = original.clone()
            // Flip a random bit in modified
            val byteIndex = random.nextInt(32)
            val bitIndex = random.nextInt(8)
            modified[byteIndex] = (modified[byteIndex].toInt() xor (1 shl bitIndex)).toByte()

            val hash1Hex = CryptoUtils.sha256(original)
            val hash2Hex = CryptoUtils.sha256(modified)

            assertNotEquals("Hashes of inputs differing by 1 bit must never match", hash1Hex, hash2Hex)

            // Convert hex back to bytes and count bit differences
            val b1 = hash1Hex.chunked(2).map { it.toInt(16) }
            val b2 = hash2Hex.chunked(2).map { it.toInt(16) }
            var bitDiff = 0
            for (j in 0 until 32) {
                bitDiff += Integer.bitCount(b1[j] xor b2[j])
            }
            totalFlippedBits += bitDiff
        }

        val avgFlippedBits = totalFlippedBits.toDouble() / iterations
        // SHA-256 avalanche expectation is ~128 bits out of 256 (50%). Must be between 100 and 156 bits.
        assertTrue("Avalanche effect must flip roughly 50% of bits (observed: $avgFlippedBits)", avgFlippedBits in 110.0..146.0)
    }

    @Test
    fun sha256_largePayloadStress_handlesExtremeLengthsWithoutError() {
        val largePayload = "TOKEN_LARGE_PAYLOAD_TEST_".repeat(40_000) // 1MB payload
        val hash = CryptoUtils.sha256(largePayload)

        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("^[0-9a-f]{64}$")))
    }

    // =========================================================================
    // 2. Constant-Time Equality Tests
    // =========================================================================

    @Test
    fun constantTimeEquals_stringNullSafetyAndEdgeCases() {
        val validHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        // Null handling
        assertTrue("Both null must return true", CryptoUtils.constantTimeEquals(null as String?, null as String?))
        assertFalse("First null must return false", CryptoUtils.constantTimeEquals(null, validHash))
        assertFalse("Second null must return false", CryptoUtils.constantTimeEquals(validHash, null))

        // Empty handling
        assertTrue("Both empty must return true", CryptoUtils.constantTimeEquals("", ""))
        assertFalse("Empty vs valid must return false", CryptoUtils.constantTimeEquals("", validHash))
        assertFalse("Valid vs empty must return false", CryptoUtils.constantTimeEquals(validHash, ""))

        // Prefix and length variations
        assertFalse("Prefix match of different lengths must return false",
            CryptoUtils.constantTimeEquals(validHash, validHash.substring(0, 32)))
        assertFalse("Different length strings must return false",
            CryptoUtils.constantTimeEquals("short", "longer_string"))

        // Single character mismatch at beginning, middle, and end
        val mismatchStart = "f" + validHash.substring(1)
        val mismatchEnd = validHash.substring(0, 63) + "0"
        val mismatchMid = validHash.substring(0, 32) + "a" + validHash.substring(33)

        assertFalse(CryptoUtils.constantTimeEquals(validHash, mismatchStart))
        assertFalse(CryptoUtils.constantTimeEquals(validHash, mismatchEnd))
        assertFalse(CryptoUtils.constantTimeEquals(validHash, mismatchMid))
        assertTrue(CryptoUtils.constantTimeEquals(validHash, validHash))
    }

    @Test
    fun constantTimeEquals_byteArrayNullSafetyAndEdgeCases() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val copy = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val diff = byteArrayOf(0x01, 0x02, 0x03, 0x05)

        assertTrue(CryptoUtils.constantTimeEquals(null as ByteArray?, null as ByteArray?))
        assertFalse(CryptoUtils.constantTimeEquals(null, bytes))
        assertFalse(CryptoUtils.constantTimeEquals(bytes, null))
        assertTrue("Distinct array instances with identical bytes must return true", CryptoUtils.constantTimeEquals(bytes, copy))
        assertTrue("Same array reference must return true", CryptoUtils.constantTimeEquals(bytes, bytes))
        assertFalse(CryptoUtils.constantTimeEquals(bytes, diff))
        assertFalse(CryptoUtils.constantTimeEquals(bytes, byteArrayOf(0x01, 0x02)))
        assertTrue(CryptoUtils.constantTimeEquals(byteArrayOf(), byteArrayOf()))
    }

    // =========================================================================
    // 3. TimeProvider Clock Independence & Invariant Tests
    // =========================================================================

    @Test
    fun testTimeProvider_wallClockDrasticJumps_doNotAffectElapsedRealtime() {
        val provider = TestTimeProvider(
            initialCurrentTimeMillis = 1_700_000_000_000L,
            initialElapsedRealtime = 50_000L
        )

        // Advance wall-clock by 100 years
        val hundredYearsMs = 100L * 365 * 24 * 60 * 60 * 1000L
        provider.advanceWallClock(hundredYearsMs)
        assertEquals(50_000L, provider.elapsedRealtime())

        // Jump wall-clock back 50 years (manual user rollback or NTP correction)
        val fiftyYearsMs = 50L * 365 * 24 * 60 * 60 * 1000L
        provider.advanceWallClock(-fiftyYearsMs)
        assertEquals(50_000L, provider.elapsedRealtime())

        // Arbitrary wall-clock overrides via setWallClock
        provider.setWallClock(0L)
        assertEquals(0L, provider.currentTimeMillis())
        assertEquals("Monotonic elapsed time must remain invariant under setWallClock", 50_000L, provider.elapsedRealtime())

        provider.setWallClock(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, provider.currentTimeMillis())
        assertEquals(50_000L, provider.elapsedRealtime())
    }

    @Test
    fun testTimeProvider_elapsedRealtimeChanges_doNotAffectWallClock() {
        val provider = TestTimeProvider(
            initialCurrentTimeMillis = 1_700_000_000_000L,
            initialElapsedRealtime = 10_000L
        )

        provider.advanceElapsedRealtime(500_000L)
        assertEquals(510_000L, provider.elapsedRealtime())
        assertEquals(1_700_000_000_000L, provider.currentTimeMillis())

        provider.setElapsedRealtime(1_234_567L)
        assertEquals(1_234_567L, provider.elapsedRealtime())
        assertEquals(1_700_000_000_000L, provider.currentTimeMillis())
    }

    @Test
    fun testTimeProvider_negativeArguments_strictlyRejected() {
        val provider = TestTimeProvider()

        assertThrows(IllegalArgumentException::class.java) {
            provider.advanceTime(-1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.advanceElapsedRealtime(-1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.setElapsedRealtime(-1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.simulateReboot(postBootElapsedMs = -1L)
        }
    }

    // =========================================================================
    // 4. Mid-Cooldown Reboot Simulation Verification (User Follow-up Contract)
    // =========================================================================

    /**
     * Simulated Recovery Engine accumulator demonstrating that TestTimeProvider's
     * simulateReboot mechanics allow testing the 24h recovery cooldown without cheating:
     * 1. Monotonic elapsed time resets on reboot.
     * 2. Wall-clock manipulation forwards (e.g. +48h) does NOT shortcut the cooldown.
     * 3. Accumulated elapsed real time across reboots correctly achieves the 24h requirement.
     */
    @Test
    fun testTimeProvider_midCooldownReboot_emulationOf24HourRecovery() {
        val provider = TestTimeProvider(
            initialCurrentTimeMillis = 1_700_000_000_000L,
            initialElapsedRealtime = 15_000L // 15 seconds after initial boot
        )

        val cooldownRequiredMs = 24 * 60 * 60 * 1000L // 24 hours = 86,400,000 ms

        // Simple recovery state tracking cumulative elapsed time across boots
        var cumulativeElapsedMs = 0L
        var lastElapsedMs = provider.elapsedRealtime()

        fun updateProgress() {
            val currentElapsed = provider.elapsedRealtime()
            if (currentElapsed >= lastElapsedMs) {
                // Same boot session: accumulate difference
                cumulativeElapsedMs += (currentElapsed - lastElapsedMs)
            } else {
                // Reboot occurred: currentElapsed reset to smaller value.
                // Add the new elapsed time since boot.
                cumulativeElapsedMs += currentElapsed
            }
            lastElapsedMs = currentElapsed
        }

        fun isCooldownComplete(): Boolean = cumulativeElapsedMs >= cooldownRequiredMs

        // Step 1: Start 24h cooldown and run for 6 hours
        val sixHoursMs = 6 * 60 * 60 * 1000L
        provider.advanceTime(sixHoursMs)
        updateProgress()

        assertEquals(sixHoursMs, cumulativeElapsedMs)
        assertFalse("Cooldown must not complete after only 6 hours", isCooldownComplete())

        // Step 2: Adversary reboots device mid-cooldown AND jumps wall-clock forward by 48 hours!
        provider.simulateReboot(
            postBootElapsedMs = 2_000L, // 2s post-boot
            powerOffDurationMs = 48 * 60 * 60 * 1000L // powered off / clock advanced by 48h
        )

        // In a real device, boot receiver updates progress
        updateProgress()

        // Verify:
        // - Underlying elapsedRealtime is only 2,000ms
        assertEquals(2_000L, provider.elapsedRealtime())
        // - Wall-clock jumped by 48h, but cumulative elapsed did NOT count wall-clock jump!
        assertEquals(sixHoursMs + 2_000L, cumulativeElapsedMs)
        assertFalse("Wall-clock jump of 48h must NOT bypass cooldown mid-reboot", isCooldownComplete())

        // Step 3: Run for another 10 hours post-reboot
        val tenHoursMs = 10 * 60 * 60 * 1000L
        provider.advanceTime(tenHoursMs)
        updateProgress()

        assertEquals(sixHoursMs + 2_000L + tenHoursMs, cumulativeElapsedMs)
        assertFalse("Total elapsed ~16h must still not complete 24h cooldown", isCooldownComplete())

        // Step 4: Run for remaining ~8 hours
        val remainingMs = cooldownRequiredMs - cumulativeElapsedMs
        provider.advanceTime(remainingMs)
        updateProgress()

        assertTrue("Cooldown must complete once exactly 24 hours of real elapsed time has passed", isCooldownComplete())
    }
}
