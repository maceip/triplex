package com.triplex.dialer.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.triplex.dialer.TriplexDialerApp
import com.triplex.dialer.viewModel.DialerViewModel

/**
 * Main activity hosting the Compose navigation graph.
 *
 * Mirrors AndroidX reference app's DialerActivity with Triplex wiring.
 */
class DialerActivity : ComponentActivity() {

    private val dialerViewModel by lazy {
        DialerViewModel(TriplexDialerApp.instance.triplexClient)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TriplexDialerTheme {
                TriplexDialerNav(dialerViewModel)
            }
        }
    }
}

@Composable
fun TriplexDialerNav(viewModel: DialerViewModel) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = NavRoutes.DIALER) {
        composable(NavRoutes.DIALER) {
            DialerScreen(
                viewModel = viewModel,
                onCall = { callId ->
                    navController.navigate("in_call/$callId")
                },
                onCallLog = { navController.navigate(NavRoutes.CALL_LOG) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS) },
            )
        }

        composable("in_call/{callId}") { backStackEntry ->
            val callId = backStackEntry.arguments?.getString("callId") ?: return@composable
            InCallScreen(
                callId = callId,
                viewModel = viewModel,
                onEndCall = {
                    viewModel.endCall(callId)
                    navController.navigate(NavRoutes.DIALER) {
                        popUpTo(NavRoutes.DIALER)
                    }
                },
            )
        }

        composable(NavRoutes.CALL_LOG) {
            CallLogScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
