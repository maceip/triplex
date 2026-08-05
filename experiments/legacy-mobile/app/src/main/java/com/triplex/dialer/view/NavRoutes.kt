package com.triplex.dialer.view

/**
 * Simple route definitions for Compose navigation.
 */
sealed class NavRoutes {
    data object Dialer : NavRoutes()
    data class InCall(val callId: String) : NavRoutes()
    data class IncomingCall(val callId: String) : NavRoutes()
    data object CallLog : NavRoutes()
    data object Settings : NavRoutes()
    data object VoiceCloneSetup : NavRoutes()
}
