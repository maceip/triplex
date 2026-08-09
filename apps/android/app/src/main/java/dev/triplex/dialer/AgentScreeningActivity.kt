package dev.triplex.dialer

import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dagger.hilt.android.AndroidEntryPoint
import dev.triplex.data.remote.ScreeningSession
import dev.triplex.domain.model.AutomationCatalog
import dev.triplex.domain.model.AutomationTemplate
import dev.triplex.telephony.sip.CallConversationTurn
import dev.triplex.telephony.sip.ConversationSpeaker
import javax.inject.Inject

private val Ink = Color(0xFF17211D)
private val Moss = Color(0xFF1C6B4A)
private val MossSoft = Color(0xFFDDEDE3)
private val Paper = Color(0xFFF7F4EC)
private val Sand = Color(0xFFE9E2D4)
private val Rust = Color(0xFFB43E32)
private val CallerBubble = Color(0xFFE3EBF4)

@AndroidEntryPoint
class AgentScreeningActivity : ComponentActivity() {
    @Inject lateinit var screeningCoordinator: ScreeningCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screeningCoordinator.start()

        setContent {
            val liveSession by screeningCoordinator.session.collectAsState()
            val coordinatorMessage by screeningCoordinator.message.collectAsState()
            val agentUtterance by screeningCoordinator.agentUtterance.collectAsState()
            val conversationTurns by screeningCoordinator.conversationTurns.collectAsState()
            var visibleSession by remember { mutableStateOf(liveSession) }
            LaunchedEffect(liveSession) {
                liveSession?.let { visibleSession = it }
            }

            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Moss,
                    onPrimary = Color.White,
                    background = Paper,
                    onBackground = Ink,
                    surface = Color.White,
                    onSurface = Ink,
                    error = Rust,
                ),
            ) {
                if (visibleSession == null) {
                    WaitingForLiveScreening(
                        message = coordinatorMessage,
                        onClose = ::finish,
                    )
                } else {
                    AgentScreeningScreen(
                        session = visibleSession,
                        agentUtterance = agentUtterance,
                        conversationTurns = conversationTurns,
                        coordinatorMessage = coordinatorMessage,
                        onRunAutomation = { automationId ->
                            screeningCoordinator.decide(ScreeningDecision.AGENT, automationId)
                        },
                        onAnswer = {
                            screeningCoordinator.decide(ScreeningDecision.ACCEPT)
                            finish()
                        },
                        onHangUp = {
                            screeningCoordinator.decide(ScreeningDecision.DECLINE)
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WaitingForLiveScreening(message: String, onClose: () -> Unit) {
    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(Moss, CircleShape),
            )
            Text(
                text = "Connecting to live screening",
                modifier = Modifier.padding(top = 18.dp),
                color = Ink,
                fontFamily = FontFamily.Serif,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message.ifBlank {
                    "Waiting for a forwarded Plivo SIP call on this phone."
                },
                modifier = Modifier.padding(top = 10.dp),
                color = Ink.copy(alpha = 0.62f),
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            TextButton(onClick = onClose, modifier = Modifier.padding(top = 18.dp)) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun AgentScreeningScreen(
    session: ScreeningSession?,
    agentUtterance: String,
    conversationTurns: List<CallConversationTurn>,
    coordinatorMessage: String,
    onRunAutomation: (String) -> Unit,
    onAnswer: () -> Unit,
    onHangUp: () -> Unit,
) {
    var choosingAutomation by remember { mutableStateOf(false) }
    val activeAutomation = AutomationCatalog.inbound.firstOrNull {
        it.id == session?.decision
    }
    val automationRunning = activeAutomation != null
    val caller = session?.caller_number
        ?.let { PhoneNumberUtils.formatNumber(it, "US") }
        ?.takeIf { it.isNotBlank() }
        ?: "Incoming caller"

    Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(if (session == null) Sand else Moss, CircleShape),
                )
                Text(
                    text = if (automationRunning) "AUTOMATION RUNNING" else "LIVE CALL SCREEN",
                    modifier = Modifier.padding(start = 10.dp),
                    color = if (automationRunning) Moss else Ink.copy(alpha = 0.68f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                )
            }

            Text(
                text = caller,
                modifier = Modifier.padding(top = 18.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Ink,
                    fontFamily = FontFamily.Serif,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = "Caller audio is being transcribed on this phone.",
                modifier = Modifier.padding(top = 4.dp),
                color = Ink.copy(alpha = 0.62f),
                fontSize = 15.sp,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (conversationTurns.isEmpty() && agentUtterance.isBlank() && session?.transcript.isNullOrBlank()) {
                    ListeningBubble()
                } else {
                    val turns = if (conversationTurns.isNotEmpty()) {
                        conversationTurns
                    } else {
                        buildList {
                            if (agentUtterance.isNotBlank()) {
                                add(CallConversationTurn(ConversationSpeaker.TRIPLEX, agentUtterance))
                            }
                            session?.transcript?.takeIf { it.isNotBlank() }?.let {
                                add(CallConversationTurn(ConversationSpeaker.CALLER, it))
                            }
                        }
                    }
                    turns.forEach { turn ->
                        ConversationBubble(
                            speaker = if (turn.speaker == ConversationSpeaker.CALLER) "CALLER" else "TRIPLEX",
                            text = turn.text,
                            isCaller = turn.speaker == ConversationSpeaker.CALLER,
                        )
                    }
                }
                if (coordinatorMessage.isNotBlank()) {
                    Text(
                        text = coordinatorMessage,
                        color = Ink.copy(alpha = 0.58f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            AnimatedVisibility(visible = choosingAutomation && !automationRunning) {
                AutomationPicker(
                    onChoose = { automationId ->
                        choosingAutomation = false
                        onRunAutomation(automationId)
                    },
                    onCancel = { choosingAutomation = false },
                )
            }

            if (!choosingAutomation && !automationRunning) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { choosingAutomation = true },
                        enabled = session != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Run automation", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onAnswer,
                            enabled = session != null,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Ink),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("Answer", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onHangUp,
                            enabled = session != null,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Rust),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("Hang up", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (automationRunning) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    color = MossSoft,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            "${activeAutomation?.title ?: "Automation"} is running",
                            color = Moss,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Triplex is handling this call with the voice policy attached to the automation.",
                            modifier = Modifier.padding(top = 4.dp),
                            color = Ink.copy(alpha = 0.68f),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationBubble(speaker: String, text: String, isCaller: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isCaller) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f),
            color = if (isCaller) CallerBubble else Color.White,
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = if (isCaller) 22.dp else 5.dp,
                bottomEnd = if (isCaller) 5.dp else 22.dp,
            ),
            shadowElevation = if (isCaller) 0.dp else 1.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 17.dp, vertical = 14.dp)) {
                Text(
                    text = speaker,
                    color = if (isCaller) Color(0xFF46627A) else Moss,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = text,
                    modifier = Modifier.padding(top = 5.dp),
                    color = Ink,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                )
            }
        }
    }
}

@Composable
private fun ListeningBubble() {
    Surface(
        color = CallerBubble.copy(alpha = 0.65f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.padding(start = 48.dp),
    ) {
        Text(
            text = "Listening for the caller...",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = Ink.copy(alpha = 0.55f),
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun AutomationPicker(onChoose: (String) -> Unit, onCancel: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        color = Color.White,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "Choose an inbound action",
                color = Ink,
                fontFamily = FontFamily.Serif,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "An automation only starts after you choose it.",
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                color = Ink.copy(alpha = 0.62f),
                fontSize = 13.sp,
            )
            AutomationCatalog.inbound.forEach { automation ->
                Button(
                    onClick = { onChoose(automation.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .height(72.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MossSoft,
                        contentColor = Ink,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(automation.title, fontWeight = FontWeight.Bold)
                        Text(automation.description, fontSize = 12.sp, color = Moss)
                    }
                }
            }
            Text(
                "Your configured inbound actions will appear here.",
                modifier = Modifier.padding(top = 12.dp),
                color = Ink.copy(alpha = 0.5f),
                fontSize = 12.sp,
            )
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                Text("Cancel")
            }
        }
    }
}

private fun splitCallerTranscript(transcript: String): Pair<String, String?> {
    val parts = transcript.split("\nAdditional message:", limit = 2)
    val first = parts.firstOrNull().orEmpty().trim()
    val additional = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    return first to additional
}
