package com.yukon.localsend

import android.util.Log

object RustSDK {
    init {
        System.loadLibrary("locsd_lib")
    }

    @JvmStatic
    fun onDeviceFound(deviceInfo: String) {
        // 切换到主线程刷新一下ui
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            DeviceManager.addDevice(deviceInfo)
        }
    }

    external fun startDiscovery(userAlias: String)
    external fun discoverOnce()
}