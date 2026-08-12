package dev.triplex.dialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.triplex.BuildConfig
import dev.triplex.data.local.SecureStorage
import dev.triplex.data.telecom.DialerSetupRepository
import dev.triplex.data.telecom.SetupRequest
import dev.triplex.data.telecom.TelecomAccountsRepository
import dev.triplex.domain.call.CallPhase
import dev.triplex.domain.call.CallSession
import dev.triplex.domain.call.CallSessionRepository
import dev.triplex.telephony.sip.TriplexSipService
import dev.triplex.ui.call.incall.InCallScreenHost
import dev.triplex.ui.keypad.KeypadScreen
import dev.triplex.ui.keypad.normalizeDialString
import dev.triplex.ui.shell.TriplexShell
import dev.triplex.ui.theme.LiquidGlassTheme
import kotlinx.coroutines.flow.MutableStateFlow
import zed.rainxch.rikkaicons.core.ProvideIconPack
import zed.rainxch.rikkaicons.pack.phosphor.PhosphorPack
import javax.inject.Inject

/**
 * The single application shell.
 *
 * This activity owns every piece of platform identity the app needs — launcher
 * entry, `DIAL`/`tel:` filters, `singleTask`, `showWhenLocked`, `turnScreenOn`,
 * and the full-screen-intent target — so consolidating the UI here (reskin.md
 * §2.1) costs nothing on the manifest side.
 *
 * After Phase 2 it owns exactly two things: the platform consent flows, which
 * need an `Activity` result launcher and therefore cannot live in a repository,
 * and the call surface. The keypad, the directory and outgoing-call plumbing
 * moved to [KeypadScreen], `DialerDirectoryRepository` and
 * [TelecomAccountsRepository].
 */
@AndroidEntryPoint
class DialerActivity : ComponentActivity() {
    @Inject
    lateinit var screeningCoordinator: ScreeningCoordinator

    @Inject
    lateinit var secureStorage: SecureStorage

    @Inject
    lateinit var callSessionRepository: CallSessionRepository

    @Inject
    lateinit var callNotificationController: CallNotificationController

    @Inject
    lateinit var directoryRepository: DialerDirectoryRepository

    @Inject
    lateinit var setupRepository: DialerSetupRepository

    @Inject
    lateinit var telecomAccounts: TelecomAccountsRepository

    /** Debug builds only: fabricates the SIM and SIP calls the debug intents ask for. */
    @Inject
    lateinit var debugCallDriver: DebugCallDriver

    private val initialNumber = MutableStateFlow("")

    /**
     * A call interrupted by a consent prompt, republished once consent lands.
     *
     * The keypad owns dialling, so the activity cannot simply re-dial: it hands
     * the number back and the keypad re-runs its own place-call path, SIM choice
     * included.
     */
    private val retryCall = MutableStateFlow<String?>(null)
    private var pendingRetryNumber: String? = null

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshPlatformState()
        resumePendingCall(setupRepository.status.value.isDefaultDialer)
    }

    private val screeningRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshPlatformState()
    }

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshPlatformState()
        if (!granted) {
            Toast.makeText(this, "Phone permission is required to place calls", Toast.LENGTH_LONG)
                .show()
        }
        resumePendingCall(granted)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPlatformState()
    }

    private val directoryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPlatformState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The shell owns every system-bar inset (see TriplexShell), so the
        // window is handed to Compose edge to edge and the bars stay
        // transparent. On API 35 the platform forces this anyway; calling it
        // explicitly keeps the behaviour identical down to minSdk 29.
        enableEdgeToEdge()
        initialNumber.value = numberFromIntent(intent)
        refreshPlatformState()
        handleDebugIntent(intent)
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TriplexSipService::class.java),
            )
        }
        screeningCoordinator.start()
        // Idempotent. Both call services also start it, but neither is
        // guaranteed to be running when the user opens the app.
        callNotificationController.start()

        val enrolledAtLaunch = secureStorage.getDeviceToken() != null

        setContent {
            val number by initialNumber.collectAsState()
            val retry by retryCall.collectAsState()
            val session by callSessionRepository.session.collectAsState()

            // Outside the theme, so every surface below — including anything the
            // theme itself draws — resolves its icon tokens. A token the ambient
            // pack cannot answer renders as an empty box rather than crashing, so
            // a missing provider would show up as silently blank chrome.
            //
            // The pack variant *is* the stroke choice: there is no weight
            // parameter anywhere in the icon API, so picking Regular here is how
            // the whole app gets one consistent line weight.
            ProvideIconPack(PhosphorPack.Fill) {
                LiquidGlassTheme {
                    TriplexShell(
                        initiallyEnrolled = enrolledAtLaunch,
                        keypadContent = { onOpenAgent ->
                            TriplexDialerScreen(
                                initialNumber = number,
                                session = session,
                                retryNumber = retry,
                                onRetryConsumed = { retryCall.value = null },
                                onRequestSetup = ::requestSetup,
                                onMessage = { message ->
                                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                                },
                                onOpenTriplex = onOpenAgent
                            )
                        }
                    )
                }
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
        callNotificationController.setCallUiVisible(true)
        refreshPlatformState()
    }

    override fun onPause() {
        callNotificationController.setCallUiVisible(false)
        super.onPause()
    }

    /**
     * Re-read roles, permissions and phone accounts.
     *
     * The directory itself no longer needs polling — its content observers push
     * — but a permission grant is exactly the event the observers cannot see, so
     * the repository is still told to re-evaluate access here.
     */
    private fun refreshPlatformState() {
        setupRepository.refresh()
        telecomAccounts.refresh()
        directoryRepository.refresh()
    }

    /** Launches the consent flow for [request]; [retryNumber] resumes a call afterwards. */
    private fun requestSetup(request: SetupRequest, retryNumber: String?) {
        pendingRetryNumber = retryNumber
        when (request) {
            SetupRequest.DEFAULT_DIALER -> requestRole(RoleManager.ROLE_DIALER, roleLauncher)
            SetupRequest.CALL_SCREENING ->
                requestRole(RoleManager.ROLE_CALL_SCREENING, screeningRoleLauncher)
            SetupRequest.NOTIFICATIONS -> requestNotificationPermission()
            SetupRequest.DIRECTORY_ACCESS -> directoryPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
            )
            SetupRequest.CALL_PERMISSION ->
                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun requestRole(
        role: String,
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
    ) {
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (roleManager.isRoleAvailable(role)) {
            launcher.launch(roleManager.createRequestRoleIntent(role))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            refreshPlatformState()
        }
    }

    private fun resumePendingCall(granted: Boolean) {
        val number = pendingRetryNumber
        pendingRetryNumber = null
        if (granted && number != null) retryCall.value = number
    }

    private fun numberFromIntent(intent: Intent): String =
        normalizeDialString(intent.data?.schemeSpecificPart.orEmpty())

    private fun handleDebugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        when (intent.action) {
            DEBUG_INCOMING_CALL -> debugCallDriver.startTelecomRinging()
            DEBUG_SIP_SCREENING -> debugCallDriver.startSipScreening()
        }
    }

    companion object {
        /** A ringing SIM call: agent has no media, controls are carrier-gated. */
        const val DEBUG_INCOMING_CALL = "dev.triplex.dialer.DEBUG_INCOMING_CALL"

        /** A screened SIP call: transcript, reply chips, honest post-answer state. */
        const val DEBUG_SIP_SCREENING = "dev.triplex.dialer.DEBUG_SIP_SCREENING"
    }
}

@Composable
private fun TriplexDialerScreen(
    initialNumber: String,
    session: CallSession?,
    retryNumber: String?,
    onRetryConsumed: () -> Unit,
    onRequestSetup: (SetupRequest, String?) -> Unit,
    onMessage: (String) -> Unit,
    onOpenTriplex: () -> Unit
) {
    var addingCall by rememberSaveable { mutableStateOf(false) }

    // "Add call" holds the current call and shows the keypad; any change of
    // primary session — the new call connecting, or every call ending — means
    // that detour is over.
    LaunchedEffect(session?.id) { addingCall = false }

    // A ringing or screened call belongs to the incoming sheet, which the shell
    // hosts as an overlay above this content. Rendering it here too would put
    // two surfaces on one call.
    val decided = session != null &&
        session.phase != CallPhase.RINGING &&
        session.phase != CallPhase.SCREENING

    // No TriplexBackground here: the shell wraps its whole scaffold in one, so
    // the nav bar and this content refract the same backdrop layer.
    if (session != null && decided && !addingCall) {
        InCallScreenHost(onAddCallDetour = { addingCall = true })
    } else {
        KeypadScreen(
            initialNumber = initialNumber,
            callInBackground = session != null,
            callOnHold = session?.phase == CallPhase.HOLDING,
            retryNumber = retryNumber,
            onRetryConsumed = onRetryConsumed,
            onReturnToCall = { addingCall = false },
            onRequestSetup = onRequestSetup,
            onMessage = onMessage,
            onOpenTriplex = onOpenTriplex
        )
    }
}

