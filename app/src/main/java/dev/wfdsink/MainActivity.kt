package dev.wfdsink

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/**
 * Advertise-only test: makes the device show up as a Miracast sink and logs every step.
 * No video yet. Run, then watch:  adb logcat -s WFDTEST
 *
 * Launch without a remote:
 *   adb shell am start -n dev.wfdsink/.MainActivity
 * Try granting the privileged permission (may be refused, and that is useful to know):
 *   adb shell pm grant dev.wfdsink android.permission.CONFIGURE_WIFI_DISPLAY
 *   adb shell pm grant dev.wfdsink android.permission.ACCESS_FINE_LOCATION
 */
class MainActivity : Activity() {
    private val tag = "WFDTEST"
    private lateinit var p2p: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var status: TextView

    private fun listener(what: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { log("$what: success") }
        override fun onFailure(reason: Int) { log("$what: FAILED reason=$reason (0=error 1=unsupported 2=busy)") }
    }

    private fun log(s: String) {
        Log.i(tag, s)
        runOnUiThread { status.append(s + "\n") }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        status = TextView(this).apply { textSize = 18f; setPadding(48, 48, 48, 48) }
        setContentView(status)
        log("SDK_INT=${Build.VERSION.SDK_INT} model=${Build.MODEL} device=${Build.DEVICE}")

        p2p = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = p2p.initialize(this, mainLooper, null)

        registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        })

        setDeviceName("[FireTV] Sink")
        setWfdInfo()
        p2p.discoverPeers(channel, listener("discoverPeers"))
        // Autonomous group: the stick becomes group owner, like AirScreen's own network.
        // Delayed so it doesn't collide with discovery (reason=2 busy).
        status.postDelayed({
            p2p.createGroup(channel, listener("createGroup"))
            status.postDelayed({ logGroup() }, 2000)
        }, 1500)
    }

    private fun logGroup() {
        p2p.requestGroupInfo(channel) { g ->
            if (g == null) { log("no group"); return@requestGroupInfo }
            log("group ssid=${g.networkName} owner=${g.isGroupOwner} clients=${g.clientList.map { it.deviceAddress }}")
        }
    }

    /** Hidden: WifiP2pManager.setDeviceName(Channel, String, ActionListener) */
    private fun setDeviceName(name: String) {
        try {
            val m = WifiP2pManager::class.java.getMethod(
                "setDeviceName", WifiP2pManager.Channel::class.java, String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            m.invoke(p2p, channel, name, listener("setDeviceName($name)"))
        } catch (e: Throwable) { log("setDeviceName reflection failed: $e") }
    }

    /** Hidden: WifiP2pWfdInfo + WifiP2pManager.setWFDInfo(...) */
    private fun setWfdInfo() {
        try {
            val cls = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val info = cls.getConstructor().newInstance()
            cls.getMethod("setWfdEnabled", Boolean::class.javaPrimitiveType).invoke(info, true)
            cls.getMethod("setDeviceType", Int::class.javaPrimitiveType).invoke(info, 1) // 1 = primary sink
            cls.getMethod("setSessionAvailable", Boolean::class.javaPrimitiveType).invoke(info, true)
            cls.getMethod("setControlPort", Int::class.javaPrimitiveType).invoke(info, 7236)
            cls.getMethod("setMaxThroughput", Int::class.javaPrimitiveType).invoke(info, 50)
            val m = WifiP2pManager::class.java.getMethod(
                "setWFDInfo", WifiP2pManager.Channel::class.java, cls,
                WifiP2pManager.ActionListener::class.java
            )
            m.invoke(p2p, channel, info, listener("setWFDInfo"))
        } catch (e: Throwable) {
            // InvocationTargetException wrapping SecurityException = missing CONFIGURE_WIFI_DISPLAY
            log("setWFDInfo reflection failed: $e cause=${e.cause}")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val ni = i.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    log("P2P connection changed: connected=${ni?.isConnected}")
                    if (ni?.isConnected == true) {
                        p2p.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                            log("group formed, owner=${info.isGroupOwner} ownerAddr=${info.groupOwnerAddress?.hostAddress}")
                            logGroup()
                            // If WE are group owner, the source is a client: its IP is in the ARP table.
                            // If the source is group owner, ownerAddr above is the RTSP target.
                            try {
                                log("arp:\n" + java.io.File("/proc/net/arp").readText())
                            } catch (e: Exception) { log("arp read failed: $e") }
                            // Next step: WfdRtspClient(<source ip>, ...).start()
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION ->
                    log("P2P state: ${i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)} (2=enabled)")
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> log("this device changed")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
