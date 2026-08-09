package dev.triplex.dialer

import android.telecom.Call
import android.telecom.CallScreeningService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The user-selected Android screening service for real SIM calls.
 *
 * It responds immediately so Telecom is never delayed or silently blocked.
 * Conversational screening is performed before routing on Plivo, where both
 * sides of the media path are legitimately available to the screening agent.
 */
@AndroidEntryPoint
class TriplexCallScreeningService : CallScreeningService() {
    @Inject
    lateinit var screeningCoordinator: ScreeningCoordinator

    override fun onScreenCall(callDetails: Call.Details) {
        screeningCoordinator.start()
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setSilenceCall(false)
                .build()
        )
    }
}
