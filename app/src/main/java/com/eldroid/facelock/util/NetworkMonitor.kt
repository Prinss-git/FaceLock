package com.eldroid.facelock.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Whether the phone has a usable connection right now, and a stream of
 * changes. Firestore queues plain writes while offline, but transactions
 * (assign, remove, bulk add, sort) need the server, so screens check first.
 */
class NetworkMonitor(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isOnline(): Boolean = cm.activeNetwork?.let(cm::getNetworkCapabilities)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    fun observe(): Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isOnline()) }
            override fun onLost(network: Network) { trySend(isOnline()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(isOnline())
            }
        }
        trySend(isOnline())
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

/** Shows [banner] while the phone is offline, for as long as the screen is visible. */
fun AppCompatActivity.bindOfflineBanner(banner: View) {
    val monitor = NetworkMonitor(this)
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            monitor.observe().collect { online -> banner.visible(!online) }
        }
    }
}
