package dev.triplex.telephony.plivo

/** Pure, lock-free-by-contract state machine; the Android adapter supplies locking. */
internal class ValidatedNetworkTracker<T : Any> {
    enum class Transition {
        NONE,
        NETWORK_UNAVAILABLE,
        VALIDATED_HANDOFF,
    }

    private var initialized = false
    private var current: T? = null

    fun initialize(initialValidatedNetwork: T?): Transition {
        check(!initialized) { "Network tracker already initialized" }
        initialized = true
        current = initialValidatedNetwork
        return if (initialValidatedNetwork == null) {
            Transition.NETWORK_UNAVAILABLE
        } else {
            Transition.NONE
        }
    }

    fun onCapabilitiesChanged(network: T, validatedInternet: Boolean): Transition {
        check(initialized) { "Network tracker is not initialized" }
        if (!validatedInternet) {
            return onLost(network)
        }
        if (current == network) {
            return Transition.NONE
        }
        current = network
        return Transition.VALIDATED_HANDOFF
    }

    fun onLost(network: T): Transition {
        check(initialized) { "Network tracker is not initialized" }
        if (current != network) {
            return Transition.NONE
        }
        current = null
        return Transition.NETWORK_UNAVAILABLE
    }

    fun reset() {
        initialized = false
        current = null
    }
}
