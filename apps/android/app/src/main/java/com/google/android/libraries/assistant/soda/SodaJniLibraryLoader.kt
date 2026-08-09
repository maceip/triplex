package com.google.android.libraries.assistant.soda

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

internal object SodaJniLibraryLoader {
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("soda_dev_jni")
        runCatching {
            val field = SodaJniLoader::class.java.getDeclaredField("lastLoadedLibrary").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val loadedLibrary = field.get(null) as AtomicReference<String>
            loadedLibrary.compareAndSet("", "soda_dev_jni")
        }.onFailure {
            Log.w(TAG, "Unable to mark recovered SODA loader as preloaded: ${it.message}")
        }
        loaded = true
    }

    private const val TAG = "SodaJniLibraryLoader"
}
