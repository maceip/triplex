package dev.triplex.telephony.plivo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSecurityPolicyTest {
    @Test
    fun acceptsVerifiedTls12WithMandatorySrtp() {
        val verdict = DirectTransportSecurityPolicy.evaluate(
            secureSnapshot(NegotiatedTlsVersion.TLS_1_2, srtpActive = true),
            requireActiveMedia = true,
        )

        assertTrue(verdict.accepted)
        assertEquals(NegotiatedTlsVersion.TLS_1_2, verdict.tlsVersion)
    }

    @Test
    fun acceptsVerifiedTls13WithMandatorySrtp() {
        val verdict = DirectTransportSecurityPolicy.evaluate(
            secureSnapshot(NegotiatedTlsVersion.TLS_1_3, srtpActive = true),
            requireActiveMedia = true,
        )

        assertTrue(verdict.accepted)
        assertEquals(NegotiatedTlsVersion.TLS_1_3, verdict.tlsVersion)
    }

    @Test
    fun rejectsDeprecatedOrConfiguredProtocolMasksAsNegotiatedVersions() {
        val tls10 = secureSnapshot(NegotiatedTlsVersion.TLS_1_2, true).copy(tlsProtocol = 1L shl 2)
        val configuredMask = secureSnapshot(NegotiatedTlsVersion.TLS_1_2, true).copy(
            tlsProtocol = NegotiatedTlsVersion.TLS_1_2.pjSslProtocol or
                NegotiatedTlsVersion.TLS_1_3.pjSslProtocol,
        )

        for (snapshot in listOf(tls10, configuredMask)) {
            val verdict = DirectTransportSecurityPolicy.evaluate(snapshot, true)
            assertFalse(verdict.accepted)
            assertTrue(
                TransportSecurityFailure.TLS_VERSION_NOT_ALLOWED in verdict.failures,
            )
        }
    }

    @Test
    fun rejectsMediaWithoutSrtpEvenWhenTlsIsValid() {
        val verdict = DirectTransportSecurityPolicy.evaluate(
            secureSnapshot(NegotiatedTlsVersion.TLS_1_3, srtpActive = false),
            requireActiveMedia = true,
        )

        assertFalse(verdict.accepted)
        assertTrue(TransportSecurityFailure.SRTP_NOT_ACTIVE in verdict.failures)
    }

    @Test
    fun rejectsCertificateCipherAndTransportFailures() {
        val snapshot = secureSnapshot(NegotiatedTlsVersion.TLS_1_2, true).copy(
            tlsCipher = 0,
            tlsVerifyStatus = 7,
            tlsLastStatus = 12,
        )
        val verdict = DirectTransportSecurityPolicy.evaluate(snapshot, true)

        assertFalse(verdict.accepted)
        assertTrue(TransportSecurityFailure.TLS_CIPHER_MISSING in verdict.failures)
        assertTrue(TransportSecurityFailure.CERTIFICATE_NOT_VERIFIED in verdict.failures)
        assertTrue(TransportSecurityFailure.TLS_TRANSPORT_ERROR in verdict.failures)
    }

    @Test
    fun signalingReadinessDoesNotPretendMediaIsAlreadyNegotiated() {
        val snapshot = secureSnapshot(NegotiatedTlsVersion.TLS_1_3, true).copy(
            mediaActive = false,
            srtpActive = false,
        )

        assertTrue(DirectTransportSecurityPolicy.evaluate(snapshot, false).accepted)
        assertFalse(DirectTransportSecurityPolicy.evaluate(snapshot, true).accepted)
    }

    private fun secureSnapshot(
        version: NegotiatedTlsVersion,
        srtpActive: Boolean,
    ) = TransportSecuritySnapshot(
        registered = true,
        registrationStatus = 200,
        tlsConnected = true,
        tlsProtocol = version.pjSslProtocol,
        tlsCipher = if (version == NegotiatedTlsVersion.TLS_1_2) 0xC02F else 0x1301,
        tlsVerifyStatus = 0,
        tlsLastStatus = 0,
        mediaActive = true,
        srtpActive = srtpActive,
    )
}
