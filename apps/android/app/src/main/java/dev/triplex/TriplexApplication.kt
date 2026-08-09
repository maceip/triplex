package dev.triplex

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.HiltAndroidApp
import dev.triplex.telephony.sip.TriplexSipService
import timber.log.Timber

@HiltAndroidApp
class TriplexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, TriplexSipService::class.java),
        )
    }
}
