package dev.triplex.telephony.plivo

import dev.triplex.telephony.plivo.ValidatedNetworkTracker.Transition
import org.junit.Assert.assertEquals
import org.junit.Test

class ValidatedNetworkTrackerTest {
    @Test
    fun wifiToLteSwitchRequestsExactlyOneValidatedHandoff() {
        val tracker = ValidatedNetworkTracker<String>()
        assertEquals(Transition.NONE, tracker.initialize("wifi"))

        assertEquals(
            Transition.VALIDATED_HANDOFF,
            tracker.onCapabilitiesChanged("lte", validatedInternet = true),
        )
        assertEquals(Transition.NONE, tracker.onLost("wifi"))
        assertEquals(
            Transition.NONE,
            tracker.onCapabilitiesChanged("lte", validatedInternet = true),
        )
    }

    @Test
    fun lostWifiWaitsThenRecoversOnlyAfterValidatedCellularNetwork() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.initialize("wifi")

        assertEquals(Transition.NETWORK_UNAVAILABLE, tracker.onLost("wifi"))
        assertEquals(
            Transition.NONE,
            tracker.onCapabilitiesChanged("lte", validatedInternet = false),
        )
        assertEquals(
            Transition.VALIDATED_HANDOFF,
            tracker.onCapabilitiesChanged("lte", validatedInternet = true),
        )
    }

    @Test
    fun losingNonCurrentNetworkDoesNotInterruptReplacement() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.initialize("wifi")
        tracker.onCapabilitiesChanged("5g", validatedInternet = true)

        assertEquals(Transition.NONE, tracker.onLost("wifi"))
        assertEquals(Transition.NETWORK_UNAVAILABLE, tracker.onLost("5g"))
    }

    @Test
    fun invalidatedCurrentNetworkBecomesUnavailable() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.initialize("wifi")

        assertEquals(
            Transition.NETWORK_UNAVAILABLE,
            tracker.onCapabilitiesChanged("wifi", validatedInternet = false),
        )
    }

    @Test
    fun resetAllowsASeparateEndpointLifecycle() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.initialize(null)
        tracker.reset()

        assertEquals(Transition.NONE, tracker.initialize("5g"))
    }

    @Test
    fun validatedHandoffInvokesPjsipIpChangeOnceWithLiveHandle() {
        val handles = mutableListOf<Long>()
        val recovery = PjsipNetworkHandoffRecovery { handle ->
            handles += handle
            0
        }

        assertEquals(0, recovery.recover(42L))
        assertEquals(listOf(42L), handles)
    }

    @Test(expected = IllegalArgumentException::class)
    fun handoffCannotRunAgainstDestroyedPjsipHandle() {
        PjsipNetworkHandoffRecovery { 0 }.recover(0L)
    }
}
