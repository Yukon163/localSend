package com.yukon.localsend

import android.util.Log

object RustSDK {
    init {
        System.loadLibrary("locsd_lib")
    }

    private fun runOnMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    @JvmStatic
    fun onDeviceFound(deviceInfo: String) {
        // 切换到主线程刷新一下ui
        runOnMain {
            DeviceManager.addDevice(deviceInfo)
        }
    }

    @JvmStatic
    fun onReceiveRequest(fileName: String, fileSize: Long, senderIp: String): Boolean {
        Log.d("RustSDK", "收到请求: $fileName")

        runOnMain {
            TransferManager.startTransfer(fileName, isSending = false)
        }
        return true
    }

    @JvmStatic
    fun onTransferProgress(transferred: Long, total: Long) {
        runOnMain {
            TransferManager.updateProgress(transferred, total)
        }
    }

    @JvmStatic
    fun onTransferComplete(success: Boolean, msg: String) {
        Log.d("RustSDK", "传输结束: $success")
        runOnMain {
            TransferManager.finishTransfer(success, msg)
        }
    }

    external fun startDiscovery(userAlias: String)
    external fun discoverOnce(userAlias: String)
    external fun startFileServer(saveDir: String)
    external fun sendFile(targetIp: String, filePath: String)
}