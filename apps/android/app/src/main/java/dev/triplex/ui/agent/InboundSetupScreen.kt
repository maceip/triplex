package dev.triplex.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import dev.triplex.domain.model.AutomationVoicePolicy
import dev.triplex.ui.components.OutlinedTextInput
import dev.triplex.ui.components.TriplexButton
import dev.triplex.ui.components.TriplexButtonStyle
import dev.triplex.ui.components.TriplexCard
import dev.triplex.ui.components.TriplexCardTone
import dev.triplex.ui.components.TriplexChoiceChip
import dev.triplex.ui.components.TriplexScreenHeader
import dev.triplex.ui.components.TriplexToggleRow
import dev.triplex.ui.components.TriplexTopBar
import dev.triplex.ui.theme.TriplexDesign
import zed.rainxch.rikkaicons.tokens.ArrowLeft
import zed.rainxch.rikkaicons.tokens.RikkaIcons
import zed.rainxch.rikkaui.components.ui.scaffold.Scaffold

/**
 * `agent/inbound` — how the agent handles calls that come to you (reskin.md §3.2).
 */
@Composable
fun InboundSetupScreen(
    onBack: () -> Unit,
    onOpenVoice: () -> Unit,
    viewModel: InboundSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val spacing = TriplexDesign.spacing

    // Re-check on entry: the clone may have become ready since the last visit.
    LaunchedEffect(Unit) { viewModel.refreshVoiceReadiness() }

    Scaffold(
        containerColor = Color.Transparent,
        // System insets are consumed once, by the shell scaffold. RikkaUI's
        // scaffold applies no window insets of its own, so the screen is not
        // inset a second time.
        topBar = {
            TriplexTopBar(
                title = "Incoming calls",
                navigationIcon = RikkaIcons.ArrowLeft,
                onNavigationClick = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal,
                top = spacing.large,
                bottom = spacing.xxLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.large),
        ) {
            item { AnsweringCard(state = state, viewModel = viewModel) }
            item { GreetingCard(state = state, viewModel = viewModel) }
            item { VoiceCard(state = state, viewModel = viewModel, onOpenVoice = onOpenVoice) }
            item { AutomationsCard(state = state, viewModel = viewModel) }
            item { QuickRepliesCard(state = state, viewModel = viewModel) }
            item { RoutingExplainerCard() }
        }
    }
}

@Composable
private fun AnsweringCard(state: InboundSetupState, viewModel: InboundSetupViewModel) {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.large)) {
            TriplexToggleRow(
                title = "Agent answers all calls",
                checked = state.config.autoAnswerAll,
                onCheckedChange = viewModel::setAutoAnswerAll,
                supportingText = "Off: calls on the Triplex line keep ringing until you " +
                    "answer or hand them to the agent yourself.",
            )
        }
    }
}

@Composable
private fun GreetingCard(state: InboundSetupState, viewModel: InboundSetupViewModel) {
    val spacing = TriplexDesign.spacing
    // The field is edited locally and committed on Save so a half-typed greeting
    // never becomes what the agent says on the next call.
    var draft by remember(state.config.greetingText) {
        mutableStateOf(state.config.greetingText)
    }
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            TriplexScreenHeader(
                title = "Greeting",
                description = "The first thing the agent says when it picks up.",
            )
            OutlinedTextInput(
                label = "Greeting",
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
                TriplexButton(
                    text = "Save",
                    onClick = { viewModel.setGreetingText(draft) },
                    enabled = draft != state.config.greetingText,
                )
                TriplexButton(
                    text = "Reset",
                    onClick = { viewModel.setGreetingText("") },
                    style = TriplexButtonStyle.SECONDARY,
                )
            }
        }
    }
}

@Composable
private fun VoiceCard(
    state: InboundSetupState,
    viewModel: InboundSetupViewModel,
    onOpenVoice: () -> Unit,
) {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            TriplexScreenHeader(
                title = "Voice",
                description = "Which voice the agent speaks with on incoming calls.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                TriplexChoiceChip(
                    label = "Triplex voice",
                    selected = state.config.voicePolicy == AutomationVoicePolicy.PRESET,
                    onClick = { viewModel.setVoicePolicy(AutomationVoicePolicy.PRESET) },
                )
                TriplexChoiceChip(
                    label = "My cloned voice",
                    selected = state.config.voicePolicy == AutomationVoicePolicy.CLONED,
                    onClick = { viewModel.setVoicePolicy(AutomationVoicePolicy.CLONED) },
                    // Unusable is shown as unusable rather than offered and then
                    // silently swapped for the branded voice.
                    enabled = state.clonedVoiceReady,
                )
            }
            if (!state.clonedVoiceReady) {
                Text(
                    text = "Your cloned voice is not ready yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TriplexButton(
                    text = "Set up my voice",
                    onClick = onOpenVoice,
                    style = TriplexButtonStyle.SECONDARY,
                )
            }
            state.voiceError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AutomationsCard(state: InboundSetupState, viewModel: InboundSetupViewModel) {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.large),
        ) {
            TriplexScreenHeader(
                title = "Automations",
                description = "What you can hand a live call to. Switched off here, " +
                    "the agent will not run it even if something asks it to.",
            )
            state.automations.forEach { template ->
                TriplexToggleRow(
                    title = template.title,
                    checked = template.id in state.config.enabledAutomationIds,
                    onCheckedChange = { viewModel.setAutomationEnabled(template.id, it) },
                    supportingText = template.description,
                )
            }
        }
    }
}

@Composable
private fun QuickRepliesCard(state: InboundSetupState, viewModel: InboundSetupViewModel) {
    val spacing = TriplexDesign.spacing
    var draft by remember { mutableStateOf("") }
    TriplexCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            TriplexScreenHeader(
                title = "Quick replies",
                description = "Tap one while a call is being screened and the agent " +
                    "says it out loud.",
            )
            state.config.quickReplies.forEach { reply ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.medium),
                ) {
                    Text(
                        text = reply,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TriplexButton(
                        text = "Remove",
                        onClick = {
                            viewModel.setQuickReplies(state.config.quickReplies - reply)
                        },
                        style = TriplexButtonStyle.SECONDARY,
                    )
                }
            }
            OutlinedTextInput(
                label = "Add a reply",
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
            )
            TriplexButton(
                text = "Add",
                onClick = {
                    viewModel.setQuickReplies(state.config.quickReplies + draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            )
        }
    }
}

/**
 * Why an agent call has to arrive on the SIP line (reskin.md §5).
 *
 * This is the single most common way to misunderstand the product, and it is a
 * platform limit rather than a missing feature, so the app states it plainly
 * instead of letting the user discover it as a silent failure.
 */
@Composable
private fun RoutingExplainerCard() {
    val spacing = TriplexDesign.spacing
    TriplexCard(modifier = Modifier.fillMaxWidth(), tone = TriplexCardTone.WARNING) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Text("Which calls the agent can answer", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Being your default phone app lets Triplex see calls on your SIM — " +
                    "who is calling, and when — but Android gives a phone app no access to " +
                    "the call's audio. On a SIM call the agent can neither listen nor speak.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "The agent works on the Triplex line, which carries its own audio. " +
                    "Two ways to get calls there:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "1. Give out your Triplex number. Calls to it reach the agent directly.\n" +
                    "2. Forward your SIM number to it. In your phone's call-settings menu, " +
                    "set conditional forwarding — when busy, unanswered, or unreachable — to " +
                    "your Triplex number. Your SIM rings first; anything you do not pick up " +
                    "lands on the agent.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Forwarding is a carrier feature and may be billed as a call.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
