package dev.triplex.telephony.plivo

import dev.triplex.telephony.plivo.ValidatedNetworkTracker.Transition
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportValidationArtifactTest {
    private data class Result(val id: String, val passed: Boolean, val detail: String?)

    @Test
    fun emitsStructuredMockValidationWithoutProductionClaim() {
        val results = listOf(
            evaluate("wifi_to_cellular_handoff") {
                val tracker = ValidatedNetworkTracker<String>()
                val recoveredHandles = mutableListOf<Long>()
                val recovery = PjsipNetworkHandoffRecovery { handle ->
                    recoveredHandles += handle
                    0
                }
                check(tracker.initialize("wifi") == Transition.NONE)
                check(tracker.onLost("wifi") == Transition.NETWORK_UNAVAILABLE)
                val transition = tracker.onCapabilitiesChanged("lte", validatedInternet = true)
                check(transition == Transition.VALIDATED_HANDOFF)
                if (transition == Transition.VALIDATED_HANDOFF) {
                    check(recovery.recover(42L) == 0)
                }
                check(recoveredHandles == listOf(42L))
            },
            evaluate("tls_1_2_with_srtp") {
                check(secureVerdict(NegotiatedTlsVersion.TLS_1_2, true).accepted)
            },
            evaluate("tls_1_3_with_srtp") {
                check(secureVerdict(NegotiatedTlsVersion.TLS_1_3, true).accepted)
            },
            evaluate("pjsip_allowed_tls_mask_with_srtp") {
                val verdict = DirectTransportSecurityPolicy.evaluate(
                    TransportSecuritySnapshot(
                        registered = true,
                        registrationStatus = 200,
                        tlsConnected = true,
                        tlsProtocol = NegotiatedTlsVersion.TLS_1_2.pjSslProtocol or
                            NegotiatedTlsVersion.TLS_1_3.pjSslProtocol,
                        tlsCipher = 0x009D,
                        tlsVerifyStatus = 0,
                        tlsLastStatus = 0,
                        mediaActive = true,
                        srtpActive = true,
                    ),
                    requireActiveMedia = true,
                )
                check(verdict.accepted)
            },
            evaluate("missing_srtp_rejected") {
                val verdict = secureVerdict(NegotiatedTlsVersion.TLS_1_3, false)
                check(!verdict.accepted)
                check(TransportSecurityFailure.SRTP_NOT_ACTIVE in verdict.failures)
            },
        )
        val allPassed = results.all { it.passed }
        val configuredPath = System.getProperty("triplex.telephony.validationOutput")
            ?: "build/reports/transport/kotlin-transport-validation.json"
        val output = Path.of(configuredPath)
        output.toFile().parentFile?.mkdirs()
        output.toFile().writeText(render(results, allPassed))

        assertTrue(
            results.filterNot { it.passed }.joinToString { "${it.id}: ${it.detail}" },
            allPassed,
        )
    }

    private fun secureVerdict(
        version: NegotiatedTlsVersion,
        srtpActive: Boolean,
    ): TransportSecurityVerdict = DirectTransportSecurityPolicy.evaluate(
        TransportSecuritySnapshot(
            registered = true,
            registrationStatus = 200,
            tlsConnected = true,
            tlsProtocol = version.pjSslProtocol,
            tlsCipher = if (version == NegotiatedTlsVersion.TLS_1_2) 0xC02F else 0x1301,
            tlsVerifyStatus = 0,
            tlsLastStatus = 0,
            mediaActive = true,
            srtpActive = srtpActive,
        ),
        requireActiveMedia = true,
    )

    private fun evaluate(id: String, block: () -> Unit): Result = try {
        block()
        Result(id, true, null)
    } catch (error: Throwable) {
        Result(id, false, error.message ?: error::class.java.simpleName)
    }

    private fun render(results: List<Result>, allPassed: Boolean): String = buildString {
        append("{\n")
        append("  \"schema\": \"triplex.transport-validation-suite.v1\",\n")
        append("  \"suite\": \"kotlin_transport_policy\",\n")
        append("  \"evidence_kind\": \"CI_MOCK\",\n")
        append("  \"all_passed\": $allPassed,\n")
        append("  \"production_ready\": false,\n")
        append("  \"checks\": [\n")
        results.forEachIndexed { index, result ->
            append("    {\"id\": \"")
            append(escape(result.id))
            append("\", \"status\": \"")
            append(if (result.passed) "PASS" else "FAIL")
            append("\"")
            result.detail?.let {
                append(", \"detail\": \"")
                append(escape(it))
                append("\"")
            }
            append(if (index == results.lastIndex) "}\n" else "},\n")
        }
        append("  ]\n}\n")
    }

    private fun escape(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}
