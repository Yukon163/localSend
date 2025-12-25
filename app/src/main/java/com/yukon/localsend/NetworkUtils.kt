package com.yukon.localsend

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class NetworkInfo(
    val name: String,
    val ip: String,
    val interfaceName: String
)

object NetworkUtils {
    fun getAllLocalIps(): List<NetworkInfo> {
        val result = mutableListOf<NetworkInfo>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val sAddr = addr.hostAddress ?: continue

                        val displayName = getFriendlyName(intf.name)

                        result.add(NetworkInfo(displayName, sAddr, intf.name))
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return result
    }

    private fun getFriendlyName(interfaceName: String): String {
        return when {
            interfaceName.startsWith("wlan") -> "WiFi"
            interfaceName.startsWith("ap") -> "热点"
            interfaceName.startsWith("eth") -> "以太网"
            interfaceName.startsWith("tun") -> "VPN"
            interfaceName.contains("p2p") -> "Wi-Fi Direct"
            else -> interfaceName
        }
    }
}