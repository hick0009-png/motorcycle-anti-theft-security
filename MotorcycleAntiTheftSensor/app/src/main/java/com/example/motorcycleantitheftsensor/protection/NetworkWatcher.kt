package com.example.motorcycleantitheftsensor.protection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Records what the phone was connected through, and when it was connected through nothing.
 *
 * This is the second half of every "why didn't it warn me". A failed send says the message did
 * not arrive; only this says whether there was anything to send it over. Without it a night of
 * `tg:failed:timeout` looks like a broken bot rather than a phone that spent the night out of
 * coverage, and those have opposite answers.
 *
 * Reports transitions rather than state. The callback fires on capability changes that have
 * nothing to do with reachability — a metering flag, a validation result — and a row for each
 * would spend the domain's hourly allowance saying the same thing.
 *
 * Registered by the service and unregistered with it, for the reason `ClockChangeWatcher`
 * gives: nothing here is worth waking the app for while there is no file to write to.
 */
class NetworkWatcher(
    private val onTransportChanged: (BreadcrumbEvent, BreadcrumbDetail) -> Unit,
) {

    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** What the last row said, so that only changes cost a row. */
    private var lastReported: BreadcrumbDetail? = null

    fun start(context: Context) {
        if (callback != null) return
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val created = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                report(transportOf(capabilities))
            }

            override fun onLost(network: Network) {
                report(BreadcrumbDetail.OFFLINE)
            }

            override fun onUnavailable() {
                report(BreadcrumbDetail.OFFLINE)
            }
        }
        // A phone with no connectivity service, or one that refuses the registration, is not a
        // reason to take the service down: this describes a problem rather than being one.
        runCatching { connectivity.registerDefaultNetworkCallback(created) }
            .onSuccess {
                manager = connectivity
                callback = created
            }
    }

    fun stop() {
        val current = callback ?: return
        callback = null
        runCatching { manager?.unregisterNetworkCallback(current) }
        manager = null
        lastReported = null
    }

    @Synchronized
    private fun report(transport: BreadcrumbDetail) {
        if (transport == lastReported) return
        lastReported = transport
        val event = if (transport == BreadcrumbDetail.OFFLINE) {
            BreadcrumbEvent.LOST
        } else {
            BreadcrumbEvent.GAINED
        }
        runCatching { onTransportChanged(event, transport) }
    }

    private fun transportOf(capabilities: NetworkCapabilities): BreadcrumbDetail = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> BreadcrumbDetail.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> BreadcrumbDetail.MOBILE
        // Ethernet over a dock, Bluetooth tethering, a VPN standing in front of either. The
        // file only has to say the phone had a way out, not which unusual one it was.
        else -> BreadcrumbDetail.UNKNOWN
    }
}
