package com.yukon.localsend

import androidx.compose.runtime.mutableStateListOf

object DeviceManager {
    val devices = mutableStateListOf<String>()

    fun addDevice(info: String) {
        if (!devices.contains(info)) {
            devices.add(info)
        }
    }
}