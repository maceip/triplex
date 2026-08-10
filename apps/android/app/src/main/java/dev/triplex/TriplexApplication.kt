package dev.triplex

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.HiltAndroidApp
import dev.triplex.data.repository.AgentRunRecorder
import dev.triplex.telephony.sip.TriplexSipService
import dev.triplex.telemetry.TelemetryClient
import dev.triplex.telemetry.TelemetryConfig
import dev.triplex.telemetry.TelemetryTree
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class TriplexApplication : Application() {

    @Inject
    lateinit var runRecorder: AgentRunRecorder

    override fun onCreate() {
        super.onCreate()
        
        val telemetryConfig = TelemetryConfig(
            ingestUrl = "https://func-triplex-ingest-production.azurewebsites.net",
            apiKey = "Gnlw4HbXdmEHbj12db1BDEOUgBh4s9T3+J3Y+L9Z1Tw=",
            batchSize = 50,
            flushIntervalMs = 30_000L
        )
        
        try {
            val telemetryClient = TelemetryClient.initialize(this, telemetryConfig)
            Timber.plant(TelemetryTree(telemetryClient))
            Timber.i("Telemetry client initialized")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize telemetry client")
        }
        
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        runRecorder.start()
        // Background starts (BOOT_COMPLETED, adb broadcasts) cannot always
        // launch an FGS; DialerActivity / TriplexSipService retries when allowed.
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TriplexSipService::class.java),
            )
        }.onFailure { error ->
            Timber.w(error, "Deferred SIP service start until a foreground entry point")
        }
    }
}

