package com.brick.app

import com.brick.app.core.common.TestTimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying [TestTimeProvider] mechanics, wall-clock independence,
 * and mid-cooldown reboot simulation.
 */
class TimeProviderTest {

    private lateinit var timeProvider: TestTimeProvider

    @Before
    fun setUp() {
        timeProvider = TestTimeProvider(
            initialCurrentTimeMillis = 1_700_000_000_000L,
            initialElapsedRealtime = 10_000L
        )
    }

    @Test
    fun advanceTime_advancesBothElapsedAndWallClock() {
        timeProvider.advanceTime(5_000L)

        assertEquals(15_000L, timeProvider.elapsedRealtime())
        assertEquals(1_700_000_005_000L, timeProvider.currentTimeMillis())
    }

    @Test
    fun advanceWallClock_doesNotAffectElapsedRealtime() {
        // Simulate user advancing system clock by 25 hours
        val jump25Hours = 25 * 60 * 60 * 1000L
        timeProvider.advanceWallClock(jump25Hours)

        assertEquals(1_700_000_000_000L + jump25Hours, timeProvider.currentTimeMillis())
        assertEquals(
            "Monotonic elapsed real time must remain strictly unchanged when wall-clock is manipulated",
            10_000L,
            timeProvider.elapsedRealtime()
        )
    }

    @Test
    fun advanceElapsedRealtime_doesNotAffectWallClock() {
        timeProvider.advanceElapsedRealtime(3_000L)

        assertEquals(13_000L, timeProvider.elapsedRealtime())
        assertEquals(1_700_000_000_000L, timeProvider.currentTimeMillis())
    }

    @Test
    fun simulateReboot_resetsElapsedRealtimeAndAdvancesWallClock() {
        // Run device for 4 hours
        timeProvider.advanceTime(4 * 60 * 60 * 1000L)
        val preRebootWall = timeProvider.currentTimeMillis()

        // Simulate reboot: device turns off for 60 seconds and boots up with 800ms elapsed
        timeProvider.simulateReboot(postBootElapsedMs = 800L, powerOffDurationMs = 60_000L)

        assertEquals(800L, timeProvider.elapsedRealtime())
        assertEquals(preRebootWall + 60_000L, timeProvider.currentTimeMillis())
    }

    @Test
    fun advanceTime_negativeDelta_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            timeProvider.advanceTime(-100L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            timeProvider.advanceElapsedRealtime(-50L)
        }
    }
}
