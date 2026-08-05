package dev.triplex.telephony.plivo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.Executor

internal class NetworkHandoffMonitor(
    context: Context,
    private val controlExecutor: Executor,
    private val onNetworkUnavailable: () -> Unit,
    private val onValidatedHandoff: () -> Unit,
) : AutoCloseable {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val tracker = ValidatedNetworkTracker<Network>()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val transition = synchronized(lock) {
                tracker.onCapabilitiesChanged(network, usable)
            }
            dispatch(transition)
        }

        override fun onLost(network: Network) {
            val transition = synchronized(lock) { tracker.onLost(network) }
            dispatch(transition)
        }
    }

    fun start() {
        val initialNetwork = connectivityManager.activeNetwork
        val initialCapabilities = initialNetwork?.let(connectivityManager::getNetworkCapabilities)
        val initialValidatedNetwork = initialNetwork?.takeIf {
            initialCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                initialCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val transition = synchronized(lock) {
            check(!registered) { "Network monitor already started" }
            registered = true
            tracker.initialize(initialValidatedNetwork)
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        dispatch(transition)
    }

    private fun dispatch(transition: ValidatedNetworkTracker.Transition) {
        when (transition) {
            ValidatedNetworkTracker.Transition.NONE -> Unit
            ValidatedNetworkTracker.Transition.NETWORK_UNAVAILABLE -> {
                controlExecutor.execute(onNetworkUnavailable)
            }
            ValidatedNetworkTracker.Transition.VALIDATED_HANDOFF -> {
                controlExecutor.execute(onValidatedHandoff)
            }
        }
    }

    override fun close() {
        val shouldUnregister = synchronized(lock) {
            if (registered) {
                registered = false
                tracker.reset()
                true
            } else {
                false
            }
        }
        if (shouldUnregister) {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}
