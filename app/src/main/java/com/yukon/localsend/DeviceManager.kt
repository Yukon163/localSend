package com.yukon.localsend

import androidx.compose.runtime.mutableStateListOf

object DeviceManager {
    val devices = mutableStateListOf<String>()
    var myName: String = "Unknown"

    fun addDevice(info: String) {
        val parts = info.split("|")
        if (parts.size >= 3) {
            val ip = parts[2]
            val index = devices.indexOfFirst {
                val existingParts = it.split("|")
                existingParts.size >= 3 && existingParts[2] == ip
            }

            if (index != -1) {
                if (devices[index] != info) {
                    devices[index] = info
                }
            } else {
                devices.add(info)
            }
        } else {
            if (!devices.contains(info)) {
                devices.add(info)
            }
        }
    }

    fun clear() {
        devices.clear()
    }
}