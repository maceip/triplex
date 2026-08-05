package com.triplex.dialer.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import com.triplex.dialer.TriplexDialerApp
import com.triplex.dialer.model.*
import com.triplex.dialer.viewModel.DialerViewModel

/**
 * Main activity hosting Compose navigation.
 *
 * Routes:
 * - Dialer — number pad + call log shortcut
 * - InCall — outgoing call screen with TriplexCallChat
 * - IncomingCall — incoming call handled by Triplex AI
 * - CallLog — call history
 * - Settings — Triplex engine configuration
 * - VoiceCloneSetup — voice cloning onboarding
 */
class DialerActivity : ComponentActivity() {

    private val dialerViewModel by lazy {
        DialerViewModel(TriplexDialerApp.instance.triplexClient)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TriplexDialerTheme {
                DialerNavContent(dialerViewModel)
            }
        }
    }
}

@Composable
fun DialerNavContent(viewModel: DialerViewModel) {
    var currentRoute by remember { mutableStateOf<NavRoutes>(NavRoutes.Dialer) }
    val incomingCallId by viewModel.incomingCallId.collectAsState()

    // Auto-navigate to incoming call screen when a call arrives
    LaunchedEffect(incomingCallId) {
        incomingCallId?.let { callId ->
            viewModel.onIncomingCallSeen(callId)
            currentRoute = NavRoutes.IncomingCall(callId)
        }
    }

    // Render screen based on current route
    when (currentRoute) {
        NavRoutes.Dialer -> DialerScreen(
            viewModel = viewModel,
            onCall = { callId ->
                currentRoute = NavRoutes.InCall(callId)
            },
            onCallLog = {
                currentRoute = NavRoutes.CallLog
            },
            onSettings = {
                currentRoute = NavRoutes.Settings
            },
        )

        is NavRoutes.InCall -> {
            val callId = (currentRoute as NavRoutes.InCall).callId
            InCallScreen(
                callId = callId,
                viewModel = viewModel,
                onEndCall = {
                    viewModel.endCall(callId)
                    currentRoute = NavRoutes.Dialer
                },
            )
        }

        is NavRoutes.IncomingCall -> {
            val callId = (currentRoute as NavRoutes.IncomingCall).callId
            val session = TriplexDialerApp.instance.triplexClient.sessionState.value

            if (session != null) {
                IncomingCallScreen(
                    session = session,
                    onTransferToUser = {
                        viewModel.transferIncomingToUser(callId)
                        currentRoute = NavRoutes.Dialer
                    },
                    onEndCall = {
                        viewModel.endIncomingCall(callId)
                        currentRoute = NavRoutes.Dialer
                    },
                    onYankResponse = { answer ->
                        TriplexDialerApp.instance.triplexClient.provideUserInput(answer)
                    },
                    onYankCancel = {
                        TriplexDialerApp.instance.triplexClient.cancelUserInput()
                    },
                )
            } else {
                // Session not ready yet — show loading
                IncomingCallScreen(
                    session = TriplexCallSession(
                        direction = CallDirection.INCOMING,
                        phase = TriplexCallPhase.SCREENING,
                        counterpartyName = "Incoming Call",
                    ),
                    onTransferToUser = {
                        viewModel.transferIncomingToUser(callId)
                        currentRoute = NavRoutes.Dialer
                    },
                    onEndCall = {
                        viewModel.endIncomingCall(callId)
                        currentRoute = NavRoutes.Dialer
                    },
                    onYankResponse = { answer ->
                        TriplexDialerApp.instance.triplexClient.provideUserInput(answer)
                    },
                    onYankCancel = {
                        TriplexDialerApp.instance.triplexClient.cancelUserInput()
                    },
                )
            }
        }

        NavRoutes.CallLog -> CallLogScreen(
            viewModel = viewModel,
            onBack = {
                currentRoute = NavRoutes.Dialer
            },
        )

        NavRoutes.Settings -> SettingsScreen(
            viewModel = viewModel,
            onBack = {
                currentRoute = NavRoutes.Dialer
            },
            onVoiceCloneSetup = {
                currentRoute = NavRoutes.VoiceCloneSetup
            },
        )

        NavRoutes.VoiceCloneSetup -> VoiceCloneSetup(
            onComplete = {
                viewModel.toggleVoiceClone(true)
                currentRoute = NavRoutes.Dialer
            },
            onSkip = {
                viewModel.toggleVoiceClone(false)
                currentRoute = NavRoutes.Dialer
            },
        )
    }
}
