package dev.triplex.telephony.plivo

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthorizedSipRouteTest {
    @Test
    fun acceptsOnlyTheLockedGrantHeader() {
        val route = AuthorizedSipRoute(
            taskId = "task-1",
            sipUri = "sips:14155550123@phone.plivo.com:5061;transport=tls",
            headerName = "X-PH-TriplexGrant",
            headerValue = "header.payload.signature",
            expiresAtElapsedRealtimeNs = Long.MAX_VALUE,
        )

        assertEquals("X-PH-TriplexGrant", route.headerName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAHeaderNameChosenByTheCaller() {
        AuthorizedSipRoute(
            taskId = "task-1",
            sipUri = "sips:14155550123@phone.plivo.com:5061;transport=tls",
            headerName = "Authorization",
            headerValue = "header.payload.signature",
            expiresAtElapsedRealtimeNs = Long.MAX_VALUE,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHeaderValueInjection() {
        AuthorizedSipRoute(
            taskId = "task-1",
            sipUri = "sips:14155550123@phone.plivo.com:5061;transport=tls",
            headerName = "X-PH-TriplexGrant",
            headerValue = "header.payload.signature\r\nX-Evil: injected",
            expiresAtElapsedRealtimeNs = Long.MAX_VALUE,
        )
    }
}
