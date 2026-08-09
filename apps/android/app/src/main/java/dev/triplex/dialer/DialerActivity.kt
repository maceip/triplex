package dev.triplex.dialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telecom.PhoneAccountHandle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.triplex.BuildConfig
import dev.triplex.MainActivity
import dev.triplex.data.remote.ScreeningSession
import dev.triplex.ui.components.TriplexBackground
import dev.triplex.ui.theme.TriplexTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

@AndroidEntryPoint
class DialerActivity : ComponentActivity() {
    @Inject
    lateinit var screeningCoordinator: ScreeningCoordinator

    private val initialNumber = MutableStateFlow("")
    private val isDefaultDialer = MutableStateFlow(false)
    private val isCallScreeningApp = MutableStateFlow(false)
    private val notificationsEnabled = MutableStateFlow(false)
    private val directoryAccessEnabled = MutableStateFlow(false)
    private val directoryRepository by lazy { DialerDirectoryRepository(this) }
    private val phoneAccounts = MutableStateFlow<List<DialerPhoneAccount>>(emptyList())
    private val selectedPhoneAccountId = MutableStateFlow<String?>(null)
    private val phoneAccountHandles = mutableMapOf<String, PhoneAccountHandle>()
    private var pendingNumber: String? = null

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshCapabilities()
        if (isDefaultDialer.value) {
            pendingNumber?.also { pendingNumber = null }?.let(::placeCall)
        }
    }

    private val screeningRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshCapabilities()
    }

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val number = pendingNumber.also { pendingNumber = null }
        if (granted && number != null) {
            placeCall(number)
        } else if (!granted) {
            Toast.makeText(this, "Phone permission is required to place calls", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshCapabilities()
    }

    private val directoryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshCapabilities()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialNumber.value = numberFromIntent(intent)
        refreshCapabilities()
        handleDebugIntent(intent)
        screeningCoordinator.start()

        setContent {
            val number by initialNumber.collectAsState()
            val defaultDialer by isDefaultDialer.collectAsState()
            val callScreeningApp by isCallScreeningApp.collectAsState()
            val notifications by notificationsEnabled.collectAsState()
            val directoryAccess by directoryAccessEnabled.collectAsState()
            val recents by directoryRepository.recents.collectAsState()
            val contacts by directoryRepository.contacts.collectAsState()
            val accounts by phoneAccounts.collectAsState()
            val selectedAccount by selectedPhoneAccountId.collectAsState()
            val screeningSession by screeningCoordinator.session.collectAsState()
            val screeningMessage by screeningCoordinator.message.collectAsState()

            TriplexTheme {
                TriplexDialerScreen(
                    initialNumber = number,
                    isDefaultDialer = defaultDialer,
                    isCallScreeningApp = callScreeningApp,
                    notificationsEnabled = notifications,
                    directoryAccessEnabled = directoryAccess,
                    recents = recents,
                    contacts = contacts,
                    phoneAccounts = accounts,
                    selectedPhoneAccountId = selectedAccount,
                    screeningSession = screeningSession,
                    screeningMessage = screeningMessage,
                    onRequestDefaultDialer = ::requestDefaultDialer,
                    onRequestCallScreening = ::requestCallScreeningRole,
                    onEnableNotifications = ::requestNotificationPermission,
                    onRequestDirectoryAccess = ::requestDirectoryAccess,
                    onSelectPhoneAccount = { selectedPhoneAccountId.value = it },
                    onCallVoicemail = ::callVoicemail,
                    onPlaceCall = ::placeCall,
                    onScreeningDecision = screeningCoordinator::decide,
                    onOpenTriplex = {
                        startActivity(Intent(this, MainActivity::class.java))
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initialNumber.value = numberFromIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        DialerCallNotificationManager.setCallUiVisible(this, true)
        refreshCapabilities()
    }

    override fun onPause() {
        DialerCallNotificationManager.setCallUiVisible(this, false)
        super.onPause()
    }

    private fun refreshCapabilities() {
        val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        isDefaultDialer.value = telecom.defaultDialerPackage == packageName
        val roleManager = getSystemService(RoleManager::class.java)
        isCallScreeningApp.value = roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        notificationsEnabled.value = NotificationManagerCompat.from(this).areNotificationsEnabled()
        directoryAccessEnabled.value =
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (directoryAccessEnabled.value) directoryRepository.refresh()
        refreshPhoneAccounts(telecom)
    }

    private fun requestDefaultDialer() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }

    private fun requestCallScreeningRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            screeningRoleLauncher.launch(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            refreshCapabilities()
        }
    }

    private fun requestDirectoryAccess() {
        directoryPermissionLauncher.launch(
            arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
        )
    }

    private fun placeCall(rawNumber: String) {
        val number = normalizeDialString(rawNumber)
        if (number.isBlank()) return

        if (!isDefaultDialer.value) {
            pendingNumber = number
            requestDefaultDialer()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNumber = number
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            return
        }

        val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val extras = Bundle().apply {
            selectedPhoneAccountId.value
                ?.let(phoneAccountHandles::get)
                ?.let { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        }
        try {
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
        } catch (error: SecurityException) {
            Toast.makeText(this, "Android blocked this call: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun numberFromIntent(intent: Intent): String {
        return normalizeDialString(intent.data?.schemeSpecificPart.orEmpty())
    }

    private fun refreshPhoneAccounts(telecom: TelecomManager) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val defaultHandle = telecom.getDefaultOutgoingPhoneAccount("tel")
        val handles = telecom.callCapablePhoneAccounts.orEmpty()
        phoneAccountHandles.clear()
        phoneAccounts.value = handles.map { handle ->
            val id = "${handle.componentName.flattenToShortString()}:${handle.id}"
            phoneAccountHandles[id] = handle
            DialerPhoneAccount(
                id = id,
                label = telecom.getPhoneAccount(handle)?.label?.toString().orEmpty().ifBlank { "Phone account" },
                isDefault = handle == defaultHandle
            )
        }
        if (selectedPhoneAccountId.value !in phoneAccountHandles) {
            selectedPhoneAccountId.value = defaultHandle
                ?.let { "${it.componentName.flattenToShortString()}:${it.id}" }
                ?.takeIf(phoneAccountHandles::containsKey)
                ?: phoneAccounts.value.singleOrNull()?.id
        }
    }

    private fun callVoicemail() {
        val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val handle = selectedPhoneAccountId.value?.let(phoneAccountHandles::get)
        val voicemailNumber = try {
            telecom.getVoiceMailNumber(handle)
        } catch (_: SecurityException) {
            null
        }
        if (voicemailNumber.isNullOrBlank()) {
            Toast.makeText(this, "No voicemail number is configured for this phone account", Toast.LENGTH_LONG).show()
        } else {
            placeCall(voicemailNumber)
        }
    }

    private fun handleDebugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != DEBUG_INCOMING_CALL) return
        val state = DialerCallUiState(
            hasCall = true,
            displayName = "Triplex test caller",
            number = "+1 318 555 0100",
            status = "Ringing",
            isIncoming = true,
            isRinging = true
        )
        DialerCallStore.publish(state)
        DialerCallNotificationManager.showIncoming(this, state)
    }

    companion object {
        const val DEBUG_INCOMING_CALL = "dev.triplex.dialer.DEBUG_INCOMING_CALL"
    }
}

@Composable
private fun TriplexDialerScreen(
    initialNumber: String,
    isDefaultDialer: Boolean,
    isCallScreeningApp: Boolean,
    notificationsEnabled: Boolean,
    directoryAccessEnabled: Boolean,
    recents: List<RecentCall>,
    contacts: List<DialerContact>,
    phoneAccounts: List<DialerPhoneAccount>,
    selectedPhoneAccountId: String?,
    screeningSession: ScreeningSession?,
    screeningMessage: String,
    onRequestDefaultDialer: () -> Unit,
    onRequestCallScreening: () -> Unit,
    onEnableNotifications: () -> Unit,
    onRequestDirectoryAccess: () -> Unit,
    onSelectPhoneAccount: (String) -> Unit,
    onCallVoicemail: () -> Unit,
    onPlaceCall: (String) -> Unit,
    onScreeningDecision: (ScreeningDecision) -> Unit,
    onOpenTriplex: () -> Unit
) {
    val callState by DialerCallStore.state.collectAsState()
    var number by rememberSaveable { mutableStateOf(initialNumber) }
    var addingCall by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialNumber) {
        if (initialNumber.isNotBlank()) number = initialNumber
    }

    TriplexBackground {
        if (screeningSession != null) {
            ScreeningContent(
                session = screeningSession,
                message = screeningMessage,
                onDecision = onScreeningDecision
            )
        } else if (callState.hasCall && !addingCall) {
            InCallContent(
                state = callState,
                onAddCall = {
                    TriplexInCallService.holdForAddCall()
                    addingCall = true
                }
            )
        } else {
            DialPadContent(
                number = number,
                isDefaultDialer = isDefaultDialer,
                isCallScreeningApp = isCallScreeningApp,
                notificationsEnabled = notificationsEnabled,
                directoryAccessEnabled = directoryAccessEnabled,
                recents = recents,
                contacts = contacts,
                phoneAccounts = phoneAccounts,
                selectedPhoneAccountId = selectedPhoneAccountId,
                onNumberChange = { number = normalizeDialString(it) },
                onRequestDefaultDialer = onRequestDefaultDialer,
                onRequestCallScreening = onRequestCallScreening,
                onEnableNotifications = onEnableNotifications,
                onRequestDirectoryAccess = onRequestDirectoryAccess,
                onSelectPhoneAccount = onSelectPhoneAccount,
                onCallVoicemail = onCallVoicemail,
                activeCallInBackground = callState.hasCall,
                onReturnToCall = { addingCall = false },
                onPlaceCall = {
                    onPlaceCall(it)
                    addingCall = false
                },
                onOpenTriplex = onOpenTriplex
            )
        }
    }
}

@Composable
private fun DialPadContent(
    number: String,
    isDefaultDialer: Boolean,
    isCallScreeningApp: Boolean,
    notificationsEnabled: Boolean,
    directoryAccessEnabled: Boolean,
    recents: List<RecentCall>,
    contacts: List<DialerContact>,
    phoneAccounts: List<DialerPhoneAccount>,
    selectedPhoneAccountId: String?,
    onNumberChange: (String) -> Unit,
    onRequestDefaultDialer: () -> Unit,
    onRequestCallScreening: () -> Unit,
    onEnableNotifications: () -> Unit,
    onRequestDirectoryAccess: () -> Unit,
    onSelectPhoneAccount: (String) -> Unit,
    onCallVoicemail: () -> Unit,
    activeCallInBackground: Boolean,
    onReturnToCall: () -> Unit,
    onPlaceCall: (String) -> Unit,
    onOpenTriplex: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var section by rememberSaveable { mutableStateOf(DialerSection.KEYPAD) }
    val rows = remember {
        listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to "")
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("PHONE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Triplex Dialer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onOpenTriplex) { Text("Triplex app") }
        }

        if (activeCallInBackground) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Current call is on hold", modifier = Modifier.weight(1f))
                    TextButton(onClick = onReturnToCall) { Text("Return to call") }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DialerSection.entries.forEach { item ->
                TextButton(onClick = { section = item }) {
                    Text(
                        item.label,
                        color = if (section == item) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (section == item) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        if (!isDefaultDialer || !isCallScreeningApp || !notificationsEnabled) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Finish phone setup", style = MaterialTheme.typography.titleMedium)
                    if (!isDefaultDialer) {
                        OutlinedButton(onClick = onRequestDefaultDialer, modifier = Modifier.fillMaxWidth()) {
                            Text("Set as default phone app")
                        }
                    }
                    if (!isCallScreeningApp) {
                        OutlinedButton(onClick = onRequestCallScreening, modifier = Modifier.fillMaxWidth()) {
                            Text("Enable call screening")
                        }
                    }
                    if (!notificationsEnabled) {
                        OutlinedButton(onClick = onEnableNotifications, modifier = Modifier.fillMaxWidth()) {
                            Text("Enable incoming-call notifications")
                        }
                    }
                }
            }
        }

        when (section) {
            DialerSection.KEYPAD -> {
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = number,
            onValueChange = onNumberChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center),
            placeholder = { Text("Enter a number", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
            trailingIcon = {
                TextButton(
                    onClick = {
                        clipboard.getText()?.text?.let(onNumberChange)
                    }
                ) { Text("Paste") }
            }
        )

        if (phoneAccounts.size > 1) {
            Text("Call using", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                phoneAccounts.forEach { account ->
                    OutlinedButton(onClick = { onSelectPhoneAccount(account.id) }) {
                        Text(if (account.id == selectedPhoneAccountId) "${account.label} ✓" else account.label)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { (digit, letters) ->
                    DialKey(
                        digit = digit,
                        letters = letters,
                        onClick = { onNumberChange(number + digit) },
                        onLongClick = if (digit == "0") {
                            { onNumberChange(number + "+") }
                        } else null
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { if (number.isNotEmpty()) onNumberChange(number.dropLast(1)) },
                enabled = number.isNotEmpty()
            ) { Text("Delete") }
            Button(
                onClick = { onPlaceCall(number) },
                enabled = number.isNotBlank(),
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) { Text("Call") }
            Spacer(Modifier.width(64.dp))
        }

        Text(
            "Hold 0 for +",
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onCallVoicemail) { Text("Call voicemail") }
            }
            DialerSection.RECENTS -> RecentsContent(
                enabled = directoryAccessEnabled,
                recents = recents,
                onRequestAccess = onRequestDirectoryAccess,
                onCall = onPlaceCall
            )
            DialerSection.CONTACTS -> ContactsContent(
                enabled = directoryAccessEnabled,
                contacts = contacts,
                onRequestAccess = onRequestDirectoryAccess,
                onCall = onPlaceCall
            )
            DialerSection.FAVORITES -> FavoritesContent(
                enabled = directoryAccessEnabled,
                contacts = contacts,
                onRequestAccess = onRequestDirectoryAccess,
                onCall = onPlaceCall
            )
        }
    }
}

@Composable
private fun ScreeningContent(
    session: ScreeningSession,
    message: String,
    onDecision: (ScreeningDecision) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "TRIPLEX IS SCREENING",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            session.caller_number.ifBlank { "Unknown caller" },
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Text(
            if (session.transcript.isBlank()) "Listening for why they are calling..."
            else "\u201c${session.transcript}\u201d",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            when (session.status) {
                "asking" -> "The caller is answering now"
                "ready", "holding" -> "The caller is on hold"
                "connecting" -> "Connecting you securely"
                else -> session.status.replaceFirstChar { it.uppercase() }
            },
            modifier = Modifier.padding(top = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (message.isNotBlank()) {
            Text(
                message,
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(32.dp))
        if (session.decision == null) {
            IncomingCallDecisionSlider(
                onDecline = { onDecision(ScreeningDecision.DECLINE) },
                onAnswer = { onDecision(ScreeningDecision.ACCEPT) },
                onTransfer = { onDecision(ScreeningDecision.AGENT) }
            )
        }
    }
}

private data class DialerPhoneAccount(
    val id: String,
    val label: String,
    val isDefault: Boolean
)

private enum class DialerSection(val label: String) {
    KEYPAD("Keypad"),
    RECENTS("Recents"),
    CONTACTS("Contacts"),
    FAVORITES("Favorites")
}

@Composable
private fun RecentsContent(
    enabled: Boolean,
    recents: List<RecentCall>,
    onRequestAccess: () -> Unit,
    onCall: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    DirectoryHeader("Recent calls", enabled, onRequestAccess)
    if (enabled) DirectorySearchField(query, { query = it }, "Search recents")
    val visibleRecents = if (query.isBlank()) recents else recents.filter {
        it.name.contains(query, ignoreCase = true) || it.number.contains(query)
    }
    if (enabled && recents.isEmpty()) {
        Text("No recent calls", modifier = Modifier.padding(24.dp))
    }
    if (enabled) {
        visibleRecents.forEach { recent ->
            DirectoryRow(
                title = recent.name.ifBlank { recent.number.ifBlank { "Unknown caller" } },
                subtitle = "${recent.typeLabel} · ${recent.whenLabel}",
                number = recent.number,
                onCall = onCall
            )
        }
    }
}

@Composable
private fun ContactsContent(
    enabled: Boolean,
    contacts: List<DialerContact>,
    onRequestAccess: () -> Unit,
    onCall: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    DirectoryHeader("Contacts", enabled, onRequestAccess)
    if (enabled) DirectorySearchField(query, { query = it }, "Search contacts")
    val visibleContacts = if (query.isBlank()) contacts else contacts.filter {
        it.name.contains(query, ignoreCase = true) || it.number.contains(query)
    }
    if (enabled && contacts.isEmpty()) {
        Text("No contacts with phone numbers", modifier = Modifier.padding(24.dp))
    }
    if (enabled) {
        visibleContacts.forEach { contact ->
            DirectoryRow(
                title = if (contact.starred) "★ ${contact.name}" else contact.name,
                subtitle = contact.number,
                number = contact.number,
                onCall = onCall
            )
        }
    }
}

@Composable
private fun FavoritesContent(
    enabled: Boolean,
    contacts: List<DialerContact>,
    onRequestAccess: () -> Unit,
    onCall: (String) -> Unit
) {
    DirectoryHeader("Favorites", enabled, onRequestAccess)
    val favorites = contacts.filter(DialerContact::starred)
    if (enabled && favorites.isEmpty()) {
        Text("Star contacts in Android Contacts to see them here", modifier = Modifier.padding(24.dp))
    }
    if (enabled) {
        favorites.forEach { contact ->
            DirectoryRow(contact.name, contact.number, contact.number, onCall)
        }
    }
}

@Composable
private fun DirectorySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        singleLine = true,
        placeholder = { Text(placeholder) }
    )
}

@Composable
private fun DirectoryHeader(title: String, enabled: Boolean, onRequestAccess: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (!enabled) {
            Text(
                "Allow Phone access to show device data.",
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRequestAccess) { Text("Allow access") }
        }
    }
}

@Composable
private fun DirectoryRow(
    title: String,
    subtitle: String,
    number: String,
    onCall: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onCall(number) }, enabled = number.isNotBlank()) { Text("Call") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    digit: String,
    letters: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    Surface(
        modifier = Modifier
            .size(76.dp)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(digit, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                if (letters.isNotEmpty()) {
                    Text(letters, fontSize = 10.sp, letterSpacing = 1.4.sp)
                }
            }
        }
    }
}

@Composable
private fun InCallContent(state: DialerCallUiState, onAddCall: () -> Unit) {
    var showKeypad by remember { mutableStateOf(false) }
    var elapsedSeconds by remember(state.connectTimeMillis) { mutableStateOf(0L) }
    LaunchedEffect(state.connectTimeMillis) {
        while (state.connectTimeMillis > 0L) {
            elapsedSeconds = ((System.currentTimeMillis() - state.connectTimeMillis) / 1_000L).coerceAtLeast(0L)
            delay(1_000L)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (state.isIncoming && state.isRinging) "INCOMING CALL" else state.status.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            state.displayName.ifBlank { state.number.ifBlank { "Unknown caller" } },
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
        if (state.displayName.isNotBlank() && state.number.isNotBlank()) {
            Text(state.number, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.connectTimeMillis > 0L) {
            Text(
                "%02d:%02d".format(elapsedSeconds / 60L, elapsedSeconds % 60L),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        Spacer(Modifier.height(44.dp))

        if (state.isRinging) {
            IncomingCallDecisionSlider(
                onDecline = TriplexInCallService::declineCurrent,
                onAnswer = TriplexInCallService::answerCurrent,
                onTransfer = TriplexInCallService::transferCurrentToAgent
            )
            if (state.transferMessage.isNotBlank()) {
                Text(
                    state.transferMessage,
                    modifier = Modifier.padding(top = 12.dp),
                    color = if (state.canTransferToAgent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            if (state.canRespondViaText) {
                TextButton(onClick = TriplexInCallService::declineWithMessage) {
                    Text("Reply: Can't talk right now")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(onClick = TriplexInCallService::toggleMute) {
                    Text(if (state.isMuted) "Unmute" else "Mute")
                }
                OutlinedButton(onClick = TriplexInCallService::toggleSpeaker) {
                    Text(if (state.isSpeakerOn) "Speaker on" else "Speaker")
                }
                if (state.canHold) {
                    OutlinedButton(onClick = TriplexInCallService::toggleHold) {
                        Text(if (state.isOnHold) "Resume" else "Hold")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(onClick = onAddCall) { Text("Add call") }
                if (state.otherCallCount > 0) {
                    OutlinedButton(onClick = TriplexInCallService::switchCall) { Text("Switch") }
                }
                if (state.canMergeCalls) {
                    OutlinedButton(onClick = TriplexInCallService::mergeCalls) { Text("Merge") }
                }
                if (state.canSwapConference) {
                    OutlinedButton(onClick = TriplexInCallService::swapConference) { Text("Swap") }
                }
            }
            if (state.audioEndpoints.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Audio", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                state.audioEndpoints.forEach { endpoint ->
                    OutlinedButton(
                        onClick = { TriplexInCallService.selectAudioEndpoint(endpoint.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (endpoint.selected) "${endpoint.name} (selected)" else endpoint.name)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { showKeypad = !showKeypad }) {
                Text(if (showKeypad) "Hide keypad" else "Keypad")
            }
            if (showKeypad) {
                listOf("123", "456", "789", "*0#").forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { digit ->
                            DialKey(
                                digit = digit.toString(),
                                letters = "",
                                onClick = { TriplexInCallService.sendDtmf(digit) },
                                onLongClick = null
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = TriplexInCallService::disconnectCurrent,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("End call") }
        }
    }
}

@Composable
private fun IncomingCallDecisionSlider(
    onDecline: () -> Unit,
    onAnswer: () -> Unit,
    onTransfer: () -> Unit
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val horizontalLimit = with(density) { 118.dp.toPx() }
    val downwardLimit = with(density) { 92.dp.toPx() }
    val commitDistance = with(density) { 76.dp.toPx() }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    fun commit(action: () -> Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        action()
        dragOffset = Offset.Zero
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .semantics {
                contentDescription = "Incoming call control. Slide left to decline, right to answer, or down for the Triplex agent."
                customActions = listOf(
                    CustomAccessibilityAction("Decline call") { onDecline(); true },
                    CustomAccessibilityAction("Answer call") { onAnswer(); true },
                    CustomAccessibilityAction("Transfer to Triplex agent") { onTransfer(); true }
                )
            }
            .pointerInput(onDecline, onAnswer, onTransfer) {
                detectDragGestures(
                    onDragCancel = { dragOffset = Offset.Zero },
                    onDragEnd = {
                        when {
                            dragOffset.x <= -commitDistance -> commit(onDecline)
                            dragOffset.x >= commitDistance -> commit(onAnswer)
                            dragOffset.y >= commitDistance -> commit(onTransfer)
                            else -> dragOffset = Offset.Zero
                        }
                    }
                ) { change, amount ->
                    change.consume()
                    val candidate = dragOffset + amount
                    dragOffset = if (abs(candidate.x) > abs(candidate.y)) {
                        Offset(candidate.x.coerceIn(-horizontalLimit, horizontalLimit), 0f)
                    } else {
                        Offset(0f, candidate.y.coerceIn(0f, downwardLimit))
                    }
                }
            }
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 38.dp)
                .fillMaxWidth(0.78f)
                .height(68.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.78f)
        ) {}
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 70.dp)
                .width(68.dp)
                .height(122.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.78f)
        ) {}

        Text(
            "Decline",
            modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 62.dp),
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Accept",
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 62.dp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Agent",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.SemiBold
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 42.dp)
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .size(60.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("Slide", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun normalizeDialString(value: String): String {
    return value.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
}
