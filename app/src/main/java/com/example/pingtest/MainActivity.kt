package com.example.pingtest

import android.content.Context
import android.net.ConnectivityManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import kotlinx.android.synthetic.main.activity_main.*
import java.util.HashMap

class MainActivity : AppCompatActivity() {


    private var mNetworkManager: NetworkManager? = null
    private val listCameras = HashMap<Int, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // RC
        listCameras[buttonTail.id] = "192.168.50.203"
        listCameras[buttonForward.id] = "192.168.50.204"
        listCameras[buttonRightHand.id] = "192.168.50.205"
        listCameras[buttonAFT.id] = "192.168.50.206"
        listCameras[buttonLeftHand.id] = "192.168.50.207"

        // OA Dev
        /*
        listCameras[buttonTail.id] = "10.240.7.122"
        listCameras[buttonForward.id] = "10.240.7.122"
        listCameras[buttonRightHand.id] = "10.240.7.122"
        listCameras[buttonAFT.id] = "10.240.7.122"
        listCameras[buttonLeftHand.id] = "10.240.7.122"
        */

        // Disable buttons
        listCameras.forEach(object: ((Map.Entry<Int, String>) -> Unit) {
            override fun invoke(entry: Map.Entry<Int, String>) {
                findViewById<Button>(entry.key).isEnabled = false
            }
        })

        // Init network manager for ping
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?)?.let { connectivityManager ->
            mNetworkManager = NetworkManager(connectivityManager).apply {
                registerNetworkCallback()
            }
        }

        // Launch ping for each camera
        listCameras.forEach(object: ((Map.Entry<Int, String>) -> Unit) {
            override fun invoke(entry: Map.Entry<Int, String>) {
                //pingCameraA(entry.key, entry.value)
                pingCameraB(entry.key, entry.value)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()

        mNetworkManager?.removeAllListeners()
        mNetworkManager?.unregisterNetworkCallback()
    }

    /**
     * METHOD A -> FalconSkyView 0.7
     */
    private fun pingCameraA(cameraId: Int, ipToPing: String) {
        mNetworkManager?.pingAddress(ipToPing) { connection ->
            runOnUiThread {
                Log.d("Test", "Ping $ipToPing connection $connection")
                findViewById<Button>(cameraId)?.apply {
                    isEnabled = connection
                }
            }
        }
    }

    /**
     * METHOD B -> FalconSkyView 0.8 Test
     */
    @Synchronized private fun pingCameraB(cameraId: Int, ipToPing: String) {

        mNetworkManager?.addIPAccessibilityListener(ipToPing, object: NetworkManager.IPAccessibilityListener {
            override fun onIPAccessible(IPAddress: String, numberOfAttempts: Int, maxAttempts: Int?) {
                runOnUiThread {
                    Log.d("Test", "Camera ping $IPAddress connected")
                    findViewById<Button>(cameraId)?.apply {
                        isEnabled = true
                    }
                }
            }

            override fun onIPInaccessible(IPAddress: String, numberOfAttempts: Int, maxAttempts: Int?) {
                runOnUiThread {
                    Log.d("Test", "Camera ping $IPAddress disconnected, attempts: $numberOfAttempts")
                    findViewById<Button>(cameraId)?.apply {
                        isEnabled = false
                    }
                }
            }
        }, 10000L, 5)
    }
}
