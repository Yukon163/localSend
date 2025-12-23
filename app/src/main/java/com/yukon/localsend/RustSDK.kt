package com.yukon.localsend

import android.util.Log

object RustSDK {
    init {
        System.loadLibrary("locsd_lib")
    }

    @JvmStatic
    fun onDeviceFound(deviceInfo: String) {
        Log.d("YukonTest", "收到 SDK 回传设备: $deviceInfo")
        DeviceManager.addDevice(deviceInfo)
    }

    external fun helloRromRust(path: String): String
    external fun startDiscovery()
    external fun testCallback()
}