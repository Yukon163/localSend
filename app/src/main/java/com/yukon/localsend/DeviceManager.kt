package com.yukon.localsend

import androidx.compose.runtime.mutableStateListOf

object DeviceManager {
    val devices = mutableStateListOf<String>()
    var myName: String = "Unknown"

    fun addDevice(info: String) {
        if (!devices.contains(info)) {
            devices.add(info)
        }
    }

    fun clear() {
        devices.clear()
    }
}