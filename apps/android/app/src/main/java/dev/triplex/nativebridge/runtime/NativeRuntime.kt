package dev.triplex.nativebridge.runtime

import timber.log.Timber

object NativeRuntime {
    
    @Volatile
    private var initialized = false
    
    private var epoch: Long = 0L
    
    init {
        try {
            System.loadLibrary("triplex-native")
            Timber.i("Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Timber.e(e, "Failed to load native library")
            throw e
        }
    }
    
    @Synchronized
    fun initialize(): Boolean {
        if (initialized) {
            Timber.w("Native runtime already initialized")
            return true
        }
        
        val result = nativeInitialize()
        if (result) {
            initialized = true
            epoch = System.nanoTime()
            Timber.i("Native runtime initialized at epoch: $epoch")
        }
        return result
    }
    
    @Synchronized
    fun shutdown() {
        if (!initialized) {
            return
        }
        
        nativeShutdown()
        initialized = false
        epoch = 0L
        Timber.i("Native runtime shutdown complete")
    }
    
    fun getEpoch(): Long = epoch
    
    fun isInitialized(): Boolean = initialized
    
    fun getCurrentTimestamp(): Long {
        check(initialized) { "Native runtime not initialized" }
        return nativeGetTimestamp()
    }
    
    external fun nativeInitialize(): Boolean
    external fun nativeShutdown()
    external fun nativeGetTimestamp(): Long
    external fun nativeGetVersion(): String
}
