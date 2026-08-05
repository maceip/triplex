package dev.triplex.nativebridge.runtime

import android.content.Context
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NativeRuntimeManager private constructor(
    private val context: Context
) {
    private val runtime = NativeRuntime
    private val isReady = AtomicBoolean(false)
    private val sipEndpoint = AtomicReference<String?>(null)
    
    private var acousticEchoCanceler: DynamicAEC? = null
    private var noiseSuppressor: DynamicNoiseSuppressor? = null
    
    fun initialize(): Boolean {
        Timber.i("Initializing native runtime manager")
        
        if (!runtime.initialize()) {
            Timber.e("Failed to initialize native runtime")
            return false
        }
        
        acousticEchoCanceler = DynamicAEC(runtime.getEpoch())
        noiseSuppressor = DynamicNoiseSuppressor(runtime.getEpoch())
        
        isReady.set(true)
        Timber.i("Native runtime manager ready")
        return true
    }
    
    fun registerSipEndpoint(
        sipUri: String,
        authUser: String,
        authPass: String,
        realm: String
    ): Boolean {
        if (!isReady.get()) {
            Timber.e("Runtime not ready for SIP registration")
            return false
        }
        
        val currentEpoch = runtime.getEpoch()
        return try {
            val result = ackedSipRegister(
                currentEpoch,
                sipUri,
                authUser,
                authPass,
                realm
            )
            if (result) {
                sipEndpoint.set(sipUri)
                Timber.i("SIP endpoint registered: $sipUri (epoch: $currentEpoch)")
            } else {
                Timber.e("SIP registration rejected")
            }
            result
        } catch (e: Exception) {
            Timber.e(e, "SIP registration failed")
            false
        }
    }
    
    fun unregisterSipEndpoint() {
        val epoch = runtime.getEpoch()
        ackedSipUnregister(epoch)
        sipEndpoint.set(null)
        Timber.i("SIP endpoint unregistered (epoch: $epoch)")
    }
    
    fun shutdown() {
        isReady.set(false)
        unregisterSipEndpoint()
        runtime.shutdown()
        Timber.i("Native runtime manager shutdown complete")
    }
    
    fun isReady(): Boolean = isReady.get()
    
    fun getEndpoint(): String? = sipEndpoint.get()
    
    private data class DynamicAEC(val epoch: Long)
    private data class DynamicNoiseSuppressor(val epoch: Long)
    
    private external fun ackedSipRegister(
        epoch: Long,
        sipUri: String,
        authUser: String,
        authPass: String,
        realm: String
    ): Boolean
    
    private external fun ackedSipUnregister(epoch: Long)
    
    companion object {
        @Volatile
        private var instance: NativeRuntimeManager? = null
        
        fun getInstance(context: Context): NativeRuntimeManager {
            return instance ?: synchronized(this) {
                instance ?: NativeRuntimeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
