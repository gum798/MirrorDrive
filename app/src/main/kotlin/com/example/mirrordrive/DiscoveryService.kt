package com.example.mirrordrive

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

fun airplayTxt(deviceId: String, pkHex: String): Map<String, String> = linkedMapOf(
    "deviceid" to deviceId,                 // lowercase colon MAC
    "features" to "0x5A7FFEE6,0x0",
    "pw" to "false",
    "flags" to "0x4",
    "model" to "AppleTV3,2",
    "pk" to pkHex,
    "pi" to "2e388006-13ba-4041-9a67-25dd4a43d536",
    "srcvers" to "220.68",
    "vv" to "2",
)

fun raopTxt(pkHex: String): Map<String, String> = linkedMapOf(
    "txtvers" to "1", "ch" to "2", "cn" to "0,1,2,3", "da" to "true",
    "et" to "0,3,5", "vv" to "2", "ft" to "0x5A7FFEE6,0x0", "am" to "AppleTV3,2",
    "md" to "0,1,2", "rhd" to "5.6.0.0", "pw" to "false", "sf" to "0x4",
    "sr" to "44100", "ss" to "16", "sv" to "false", "tp" to "UDP",
    "vs" to "220.68", "vn" to "65537", "pk" to pkHex,
)

fun raopInstanceName(deviceId: String, friendly: String): String =
    deviceId.replace(":", "").uppercase() + "@" + friendly

class DiscoveryService(private val context: Context) {
    private var jmdns: JmDNS? = null
    private var lock: WifiManager.MulticastLock? = null
    private val io = Executors.newSingleThreadExecutor()

    fun start(name: String, port: Int, pkHex: String, deviceId: String) = io.execute {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("mirrordrive-mdns").apply {
            setReferenceCounted(true); acquire()
        }
        val addr = wifiIpv4(wifi) ?: error("no wifi ipv4")
        val jm = JmDNS.create(addr, name)
        jm.registerService(ServiceInfo.create(
            "_airplay._tcp.local.", name, port, 0, 0, airplayTxt(deviceId, pkHex)))
        jm.registerService(ServiceInfo.create(
            "_raop._tcp.local.", raopInstanceName(deviceId, name), port, 0, 0, raopTxt(pkHex)))
        jmdns = jm
    }

    fun stop() = io.execute {
        jmdns?.unregisterAllServices(); jmdns?.close(); jmdns = null
        lock?.let { if (it.isHeld) it.release() }; lock = null
    }

    private fun wifiIpv4(wifi: WifiManager): InetAddress? {
        @Suppress("DEPRECATION")
        val ip = wifi.connectionInfo.ipAddress
        if (ip == 0) return null
        val bytes = byteArrayOf(
            (ip and 0xff).toByte(), (ip shr 8 and 0xff).toByte(),
            (ip shr 16 and 0xff).toByte(), (ip shr 24 and 0xff).toByte())
        return InetAddress.getByAddress(bytes)
    }
}
