package dev.kauzes.mizan.merchant.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether the phone believes it has a network. */
interface Connectivity {
    val online: Flow<Boolean>
}

/**
 * The system's own answer, as a flow.
 *
 * Believed, not trusted: a phone can hold a network that reaches nothing, so coming online only starts a
 * sync, and whether the platform answered is what decides anything. The reverse matters more — going
 * offline is never how a payment is judged, only how it is explained.
 */
class AndroidConnectivity(context: Context) : Connectivity {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    override val online: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(hasInternet()) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        trySend(hasInternet())
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun hasInternet(): Boolean {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
