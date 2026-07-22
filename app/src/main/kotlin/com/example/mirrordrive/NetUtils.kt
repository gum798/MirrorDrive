package com.example.mirrordrive

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The device's own site-local IPv4, found by enumerating network interfaces rather than the
 * deprecated WifiInfo.ipAddress (which only reports the STA/client interface and returns 0 when
 * the device is acting as a SoftAP hotspot). This works in every network mode:
 *  - normal shared Wi-Fi,
 *  - joined to an iPhone Personal Hotspot (tablet gets e.g. 172.20.10.2),
 *  - acting as the SoftAP hotspot itself (e.g. 192.168.x on ap0/wlan1).
 * jmDNS must bind and advertise this exact address, so picking the wrong NIC would hand the
 * iPhone an unreachable IP.
 */
fun localIpv4(): InetAddress? = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
}.getOrNull()

/** The same address as a dotted string for on-screen display, or null if none is up. */
fun localIpv4String(): String? = localIpv4()?.hostAddress
