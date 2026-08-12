package dev.triplex.ui.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import dev.triplex.data.local.SecureStorage
import dev.triplex.telephony.sip.TelephonyController
import dev.triplex.telephony.sip.TelephonyController.SipState
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.journey.FloatingShapeBackdrop
import dev.triplex.ui.journey.JourneyHero
import dev.triplex.ui.journey.JourneyScreenColumn
import dev.triplex.ui.journey.JourneyStageRail
import dev.triplex.ui.journey.JourneyStagger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import zed.rainxch.rikkaicons.tokens.ArrowLeft
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold
import zed.rainxch.rikkaui.components.ui.text.Text
import zed.rainxch.rikkaui.components.ui.text.TextVariant
import zed.rainxch.rikkaui.foundation.RikkaTheme
import javax.inject.Inject

data class CallForwardState(
    val stageIndex: Int = 0,
    val triplexTarget: String? = null,
    val hasCredentials: Boolean = false,
    val routingAttested: Boolean = false,
    val sipState: SipState = SipState.UNCONFIGURED,
    val message: String? = null,
)

@HiltViewModel
class CallForwardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage,
    telephonyController: TelephonyController,
) : ViewModel() {
    private val _state = MutableStateFlow(CallForwardState())
    val state = _state.asStateFlow()

    val sipState = telephonyController.sipState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        telephonyController.sipState.value,
    )

    init {
        refresh()
        viewModelScope.launch {
            sipState.collect { sip ->
                _state.value = _state.value.copy(sipState = sip)
            }
        }
    }

    fun refresh() {
        val username = secureStorage.getPlivoUsername()?.trim().orEmpty()
        val target = username.takeIf { it.isNotBlank() }?.let { formatForwardTarget(it) }
        _state.value = _state.value.copy(
            triplexTarget = target,
            hasCredentials = username.isNotBlank() &&
                !secureStorage.getPlivoPassword().isNullOrBlank(),
            routingAttested = secureStorage.isRoutingAttested(),
            stageIndex = when {
                secureStorage.isRoutingAttested() -> 3
                target != null -> 1
                else -> 0
            },
        )
    }

    fun goToStage(index: Int) {
        _state.value = _state.value.copy(stageIndex = index.coerceIn(0, 3))
    }

    fun attestForwarding() {
        secureStorage.setRoutingAttested(true)
        _state.value = _state.value.copy(
            routingAttested = true,
            stageIndex = 3,
            message = "Marked ready. Triplex will treat your SIM as routed when busy or unanswered.",
        )
    }

    fun clearAttestation() {
        secureStorage.setRoutingAttested(false)
        _state.value = _state.value.copy(
            routingAttested = false,
            stageIndex = 1,
            message = null,
        )
    }

    fun copyTarget(): Boolean {
        val target = _state.value.triplexTarget ?: return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Triplex number", target))
        _state.value = _state.value.copy(message = "Copied $target")
        return true
    }

    fun mmiBusy(): String? = mmi("**67*", _state.value.triplexTarget)
    fun mmiUnanswered(): String? = mmi("**61*", _state.value.triplexTarget)
    fun mmiUnreachable(): String? = mmi("**62*", _state.value.triplexTarget)

    private fun mmi(prefix: String, target: String?): String? {
        val digits = target?.filter { it.isDigit() || it == '+' }.orEmpty()
        if (digits.isBlank()) return null
        // GSM MMI: **61*<number># — carriers vary; user confirms in dialer.
        return "$prefix$digits#"
    }

    private fun formatForwardTarget(username: String): String {
        val digits = username.filter { it.isDigit() }
        return when {
            username.startsWith("+") -> username
            digits.length in 10..15 -> "+$digits"
            else -> username
        }
    }
}

@Composable
fun CallForwardScreen(
    onBack: () -> Unit,
    onOpenEnrollmentHint: () -> Unit,
    viewModel: CallForwardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val spacing = RikkaTheme.spacing
    val context = LocalContext.current
    val stages = listOf("Line", "Number", "Forward", "Confirm")

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TriplexTopBar(
                title = "Call forwarding",
                navigationIcon = RikkaIcons.ArrowLeft,
                onNavigationClick = onBack,
            )
        },
    ) { padding ->
        FloatingShapeBackdrop {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = spacing.xxl),
            ) {
                JourneyScreenColumn {
                    JourneyStagger(0) {
                        JourneyHero(
                            brand = "TRIPLEX",
                            title = "Route missed SIM calls to the agent.",
                            supporting = "Android never gives dialers live call audio. " +
                                "Conditional forward puts unanswered rings on your Triplex line.",
                            eyebrow = "SIM → TRIPLEX LINE",
                        )
                    }
                    JourneyStagger(1) {
                        JourneyStageRail(stages = stages, currentIndex = state.stageIndex)
                    }
                    JourneyStagger(2) {
                        when (state.stageIndex) {
                            0 -> LineGateCard(
                                hasCredentials = state.hasCredentials,
                                sipState = state.sipState,
                                onContinue = {
                                    if (state.hasCredentials) viewModel.goToStage(1)
                                    else onOpenEnrollmentHint()
                                },
                            )
                            1 -> NumberCard(
                                target = state.triplexTarget,
                                onCopy = { viewModel.copyTarget() },
                                onContinue = { viewModel.goToStage(2) },
                            )
                            2 -> ForwardCard(
                                busy = viewModel.mmiBusy(),
                                unanswered = viewModel.mmiUnanswered(),
                                unreachable = viewModel.mmiUnreachable(),
                                onDial = { code ->
                                    context.startActivity(
                                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(code)}")),
                                    )
                                },
                                onContinue = { viewModel.goToStage(3) },
                            )
                            else -> ConfirmCard(
                                attested = state.routingAttested,
                                message = state.message,
                                onAttest = viewModel::attestForwarding,
                                onClear = viewModel::clearAttestation,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineGateCard(
    hasCredentials: Boolean,
    sipState: SipState,
    onContinue: () -> Unit,
) {
    val spacing = RikkaTheme.spacing
    TriplexCard(
        modifier = Modifier.fillMaxWidth(),
        tone = if (hasCredentials) TriplexCardTone.SUCCESS else TriplexCardTone.WARNING,
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = if (hasCredentials) "Triplex line credentials are on this phone."
                else "Finish line setup before forwarding.",
                variant = TextVariant.H3,
            )
            Text(
                text = if (hasCredentials) {
                    "SIP status: ${sipState.name.replace('_', ' ')}. Next, copy your Triplex number."
                } else {
                    "Without SIP credentials there is nowhere for your carrier to send forwarded calls."
                },
                color = RikkaTheme.colors.onMuted,
            )
            TriplexButton(
                text = if (hasCredentials) "Continue" else "Open account setup",
                onClick = onContinue,
            )
        }
    }
}

@Composable
private fun NumberCard(
    target: String?,
    onCopy: () -> Unit,
    onContinue: () -> Unit,
) {
    val spacing = RikkaTheme.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(text = "Your Triplex forward target", variant = TextVariant.H3)
            Text(
                text = target ?: "No number on this device yet.",
                variant = TextVariant.H2,
                color = RikkaTheme.colors.primary,
            )
            Text(
                text = "Carriers differ. Prefer the number shown in your Triplex account if this " +
                    "looks like a SIP username rather than a phone number.",
                variant = TextVariant.Small,
                color = RikkaTheme.colors.onMuted,
            )
            TriplexButton(
                text = "Copy number",
                onClick = onCopy,
                enabled = !target.isNullOrBlank(),
            )
            TriplexButton(
                text = "Next: set forwarding",
                onClick = onContinue,
                style = TriplexButtonStyle.SECONDARY,
                enabled = !target.isNullOrBlank(),
            )
        }
    }
}

@Composable
private fun ForwardCard(
    busy: String?,
    unanswered: String?,
    unreachable: String?,
    onDial: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val spacing = RikkaTheme.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.ACCENT) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(text = "Conditional forward codes", variant = TextVariant.H3)
            Text(
                text = "These open the dialer with a common GSM MMI string. Your carrier may " +
                    "require its own settings UI instead — use that if the code fails.",
                color = RikkaTheme.colors.onMuted,
            )
            listOf(
                "When busy" to busy,
                "When unanswered" to unanswered,
                "When unreachable" to unreachable,
            ).forEach { (label, code) ->
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(text = label, variant = TextVariant.H4)
                    Text(
                        text = code ?: "Unavailable",
                        variant = TextVariant.Small,
                        color = RikkaTheme.colors.onMuted,
                    )
                    TriplexButton(
                        text = "Open in dialer",
                        onClick = { code?.let(onDial) },
                        style = TriplexButtonStyle.OUTLINE,
                        enabled = code != null,
                    )
                }
            }
            TriplexButton(text = "I set forwarding", onClick = onContinue)
        }
    }
}

@Composable
private fun ConfirmCard(
    attested: Boolean,
    message: String?,
    onAttest: () -> Unit,
    onClear: () -> Unit,
) {
    val spacing = RikkaTheme.spacing
    TriplexCard(
        modifier = Modifier.fillMaxWidth(),
        tone = if (attested) TriplexCardTone.SUCCESS else TriplexCardTone.DEFAULT,
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = if (attested) "Routing marked ready." else "Confirm on this phone.",
                variant = TextVariant.H3,
            )
            Text(
                text = "Triplex cannot read carrier forward state. Confirm only after your " +
                    "operator accepted the codes or you enabled conditional CF in its app.",
                color = RikkaTheme.colors.onMuted,
            )
            message?.let { Text(text = it, variant = TextVariant.Small) }
            if (attested) {
                TriplexButton(
                    text = "Clear confirmation",
                    onClick = onClear,
                    style = TriplexButtonStyle.GHOST,
                )
            } else {
                TriplexButton(text = "Yes — my SIM forwards to Triplex", onClick = onAttest)
            }
        }
    }
}
