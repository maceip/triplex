package dev.triplex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.triplex.data.local.SecureStorage
import dev.triplex.data.repository.UserRepository
import dev.triplex.nativebridge.agent.AgentBridge
import dev.triplex.nativebridge.runtime.NativeRuntimeManager
import dev.triplex.telephony.sip.TelephonyController
import dev.triplex.ui.navigation.TriplexNavigation
import dev.triplex.ui.theme.TriplexTheme
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var secureStorage: SecureStorage

    @Inject
    lateinit var userRepository: UserRepository
    
    @Inject
    lateinit var telephonyController: TelephonyController
    

    @Inject
    lateinit var agentBridge: AgentBridge

    private val runtimeManager by lazy {
        NativeRuntimeManager.getInstance(applicationContext)
    }

    // Debug-only loopback driver:
    //   adb shell am broadcast -a dev.triplex.DEMO_TURN [--ez barge_in true]
    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            agentBridge.runDemoTurn(bargeIn = intent?.getBooleanExtra("barge_in", false) == true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        initializeNativeRuntime()
        
        val isEnrolled = secureStorage.getDeviceToken() != null
        
        setContent {
            TriplexTheme {
                TriplexNavigation(isEnrolled = isEnrolled)
            }
        }
    }
    
    private fun initializeNativeRuntime() {
        lifecycleScope.launch {
            try {
                if (!runtimeManager.initialize()) {
                    Timber.e("Failed to initialize native runtime")
                    return@launch
                }

                // Baseline agent session (loopback mode until direct call
                // media is proven; must not run concurrently with a call).
                if (agentBridge.start() && BuildConfig.DEBUG) {
                    registerReceiver(
                        demoReceiver,
                        IntentFilter("dev.triplex.DEMO_TURN"),
                        RECEIVER_EXPORTED
                    )
                }
                
                if (runtimeManager.isReady()) {
                    secureStorage.getDeviceId()?.let { deviceId ->
                        Timber.i("Native runtime ready, device: $deviceId")
                        
                        telephonyController.initialize()
                        userRepository.syncSipCredentials()
                        if (!telephonyController.registerSip()) {
                            Timber.w(
                                "SIP not registered (state: %s)",
                                telephonyController.sipState.value
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Native runtime initialization failed")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (BuildConfig.DEBUG) {
            runCatching { unregisterReceiver(demoReceiver) }
        }
        agentBridge.stop()
        telephonyController.shutdown()
        runtimeManager.shutdown()
        
        Timber.i("MainActivity destroyed, native resources released")
    }
}
