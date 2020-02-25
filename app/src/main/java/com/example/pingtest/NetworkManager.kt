package com.example.pingtest

import android.net.*
import android.os.AsyncTask
import android.os.Build
import android.util.Log


/**
 * Class to easily manage network methods (like ping)
 * Need to call registerNetworkCallback() (typically in onCreate())
 * before call an action
 * and unregisterNetworkCallback() (typically in onDestroy())
 * before destroy elements
 */
class NetworkManager(private val connectivityManager: ConnectivityManager)
    : ConnectivityManager.NetworkCallback() {

    private val mNetworkRequest: NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    addTransportType(NetworkCapabilities.TRANSPORT_LOWPAN)
                }
            }
            .build()

    private var mNetworkConnectionListeners = ArrayList<NetworkConnectionListener>()
    private var mIPAccessibilityList = ArrayList<PingIPObject>()


    var networkAvailable = false
        private set

    fun isIPConnected(IPAddress: String): Boolean {
        return try {
            retrieveIPObjectFromAddress(IPAddress).isIPConnected
        } catch (e: Exception) {
            false
        }
    }

    private fun retrieveIPObjectFromAddress(IPAddress: String): PingIPObject {
        return mIPAccessibilityList.last { it.IPAddress == IPAddress }
    }

    private fun retrieveIPObjectFromListener(listener: IPAccessibilityListener): PingIPObject {
        return mIPAccessibilityList.last { it.listeners.contains(listener) }
    }

    fun addNetworkConnectionListener(networkListener: NetworkConnectionListener) {
        mNetworkConnectionListeners.add(networkListener)
    }

    fun removeNetworkConnectionListener(networkListener: NetworkConnectionListener) {
        mNetworkConnectionListeners.remove(networkListener)
    }

    fun addIPAccessibilityListener(IPAddress: String,
                                   listener: IPAccessibilityListener,
                                   pingPeriod: Long = 5000L,
                                   maxAttempts: Int? = null) {
        val existingIpObject =
                try {
                    retrieveIPObjectFromAddress(IPAddress)
                } catch (e: Exception) {
                    val newPingIP = PingIPObject(IPAddress, pingPeriod, maxAttempts)
                    mIPAccessibilityList.add(newPingIP)
                    newPingIP
                }
        existingIpObject.listeners.add(listener)
        if (networkAvailable) {
            startCheckIPAccessibility(existingIpObject)
        }
    }

    fun removeIPAccessibilityListener(listener: IPAccessibilityListener) {
        val objectToRemove = retrieveIPObjectFromListener(listener)
        objectToRemove.listeners.remove(listener)
        if (objectToRemove.listeners.isEmpty()) {
            stopCheckIPAccessibility(objectToRemove)
            mIPAccessibilityList.remove(objectToRemove)
        }
    }

    fun removeAllListeners() {
        mIPAccessibilityList.forEach {
            stopCheckIPAccessibility(it)
        }
        mIPAccessibilityList.clear()
        mNetworkConnectionListeners.clear()
    }

    fun registerNetworkCallback() {
        connectivityManager.apply {
            registerNetworkCallback(mNetworkRequest, this@NetworkManager)
        }
    }

    fun unregisterNetworkCallback() {
        connectivityManager.apply {
            unregisterNetworkCallback(this@NetworkManager)
        }
    }

    override fun onAvailable(network: Network) {
        networkAvailable = true
        Log.d(TAG, "Connected to network, start checking Internet connection and VLAN connection")
        mIPAccessibilityList.forEach {
            startCheckIPAccessibility(it)
        }
        // Must be in UI thread
        mNetworkConnectionListeners.forEach {
            it.onConnectionAvailable()
        }
    }

    override fun onLosing(network: Network, maxMsToLive: Int) {}

    override fun onLost(network: Network) {
        onUnavailable()
    }

    override fun onUnavailable() {
        networkAvailable = false
        Log.d(TAG, "Disconnected from network")
        // Must be in UI thread
        mNetworkConnectionListeners.forEach {
            it.onConnectionUnavailable()
        }
        mIPAccessibilityList.forEach {
            stopCheckIPAccessibility(it)
            it.numberOfAttempts = 0
            it.listeners.forEach { listener ->
                listener.onIPInaccessible(it.IPAddress,
                        it.numberOfAttempts,
                        it.maxAttempts)
            }
        }
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {}

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {}

    /**
     * Thread to check internet connections periodically.
     */
    private fun startCheckIPAccessibility(pingIPObject: PingIPObject) {
        stopCheckIPAccessibility(pingIPObject)
        pingIPObject.timerThread = doActionPeriodically({
            // Ping IP
            pingAddress(pingIPObject.IPAddress) { connection ->
                pingIPObject.isIPConnected = connection
                // Increment number of attempts
                pingIPObject.numberOfAttempts++
                // Check connection
                if (connection) {
                    stopCheckIPAccessibility(pingIPObject)
                    pingIPObject.listeners.forEach {
                        it.onIPAccessible(pingIPObject.IPAddress,
                                pingIPObject.numberOfAttempts,
                                pingIPObject.maxAttempts)
                    }
                } else {
                    // Stop checking if max attempts
                    val numberOfAttempts = pingIPObject.numberOfAttempts
                    if (pingIPObject.maxAttempts != null
                            && pingIPObject.numberOfAttempts >= pingIPObject.maxAttempts!!) {
                        stopCheckIPAccessibility(pingIPObject)
                    }
                    pingIPObject.listeners.forEach { listener ->
                        listener.onIPInaccessible(pingIPObject.IPAddress,
                                numberOfAttempts,
                                pingIPObject.maxAttempts)
                    }
                }
            }
        }, pingIPObject.period)
    }

    private fun stopCheckIPAccessibility(pingIPObject: PingIPObject) {
        stopTask(pingIPObject.timerThread)
        pingIPObject.numberOfAttempts = 0
    }

    /**
     * Ping [address] in background thread and do the [actionAfterPing] in UI thread at ping return
     */
    fun pingAddress(address: String, actionAfterPing: (Boolean) -> Unit) {
        if (networkAvailable) {
            NetworkPing(address) { connection ->
                // Must be in UI thread
                actionAfterPing(connection)
            }.execute()
        } else {
            actionAfterPing(false)
        }
    }

    /**
     * Private class to do a ping in a background thread
     */
    private class NetworkPing(private val address: String,
                              val result: (Boolean) -> Unit)
        : AsyncTask<Void, Void, Boolean>() {

        override fun doInBackground(vararg params: Void?): Boolean {
            return try {
                Log.d(TAG, "Try to ping : $address")
                val pingProcess = Runtime.getRuntime().exec("ping -c 1 -w 1 $address")
                pingProcess.waitFor()
                val pingReturnValue = pingProcess.exitValue() == 0
                pingProcess.destroy()
                pingReturnValue
            } catch (ex: Exception) {
                Log.e(TAG, "Unable to ping : $address + ${ex.message}")
                false
            }
        }

        override fun onPostExecute(connected: Boolean) {
            super.onPostExecute(connected)
            result(connected)
        }
    }

    interface NetworkConnectionListener {
        fun onConnectionAvailable()
        fun onConnectionUnavailable()
    }

    interface IPAccessibilityListener {
        fun onIPAccessible(IPAddress: String, numberOfAttempts: Int, maxAttempts: Int?)
        fun onIPInaccessible(IPAddress: String, numberOfAttempts: Int, maxAttempts: Int?)
    }

    private data class PingIPObject(val IPAddress: String,
                                    var period: Long,
                                    var maxAttempts: Int?,
                                    var numberOfAttempts: Int = 0,
                                    var timerThread: Thread? = null,
                                    var listeners: ArrayList<IPAccessibilityListener> = ArrayList(),
                                    var isIPConnected: Boolean = false)

    companion object {
        private val TAG: String = NetworkManager::class.java.name
    }

    /**
     * Do action periodically every [every] milliseconds,
     * Stop the thread with stopTask()
     */
    fun doActionPeriodically(action: () -> Unit, every: Long = 5000L): Thread {
        val checkConnectionTimer = Thread {
            val posDurationMills: Long = every
            val maxDurationMills: Long = Long.MAX_VALUE
            for (pos in maxDurationMills downTo 0) {
                action()
                try {
                    Thread.sleep(posDurationMills)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        checkConnectionTimer.start()
        return checkConnectionTimer
    }

    /**
     * Utility function to stop a thread
     */
    fun stopTask(task: Thread?) {
        if (task != null && task.isAlive)
            task.interrupt()
    }
}