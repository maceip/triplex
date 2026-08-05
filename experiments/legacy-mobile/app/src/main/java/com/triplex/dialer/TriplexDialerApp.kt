package com.triplex.dialer

import android.app.Application
import android.content.Context
import com.triplex.dialer.triplex.TriplexClient

/** Application class that initializes Triplex voice engine and telecom glue. */
class TriplexDialerApp : Application() {

    /** Global Triplex client instance — shared across service and UI. */
    lateinit var triplexClient: TriplexClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        triplexClient = TriplexClient().also { it.initialize(this) }
    }

    companion object {
        lateinit var instance: TriplexDialerApp
            private set

        fun appContext(): Context = instance.applicationContext
    }
}
