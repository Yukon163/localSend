package com.yukon.localsend

import android.util.Log

object RustSDK {
    init {
        System.loadLibrary("localsend_core")
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

        val saveDir = TransferManager.saveDir.value
        var notice = ""
        if (saveDir.isNotBlank()) {
            val dir = java.io.File(saveDir)
            val target = java.io.File(dir, fileName)
            if (target.exists()) {
                notice = "检测到同名文件，接收完成后将自动添加序号"
            }
        }

        runOnMain {
            TransferManager.startTransfer(fileName, isSending = false, total = fileSize, notice = notice)
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
    fun onTransferComplete(success: Boolean, msg: String, isSending: Boolean) {
        Log.d("RustSDK", "传输结束: $success")
        runOnMain {
            TransferManager.finishTransfer(success, msg, isSending)
        }
    }

    @JvmStatic
    fun onTransferEvent(eventJson: String) {
        runOnMain {
            TransferManager.applyEvent(eventJson)
        }
    }

    var currentAlias: String? = null
        private set

    private var isDiscoveryStarted = false
    private var isFileServerStarted = false

    fun startDiscoverySafe(userAlias: String) {
        if (isDiscoveryStarted) {
            Log.d("RustSDK", "Discovery service already started, skipping.")
            return
        }
        startDiscovery(userAlias)
        isDiscoveryStarted = true
        currentAlias = userAlias
    }

    fun startFileServerSafe(saveDir: String) {
        if (isFileServerStarted) {
            Log.d("RustSDK", "File server already started, skipping.")
            return
        }
        startFileServer(saveDir)
        isFileServerStarted = true
    }

    private external fun startDiscovery(userAlias: String)
    external fun discoverOnce(userAlias: String)
    private external fun startFileServer(saveDir: String)
    external fun sendFile(targetIp: String, filePath: String): String
    external fun sendFileDescriptor(
        targetIp: String,
        fd: Int,
        fileName: String,
        fileSize: Long,
        sourceKey: String,
    ): String
    external fun cancelTransfer(transferId: String): Boolean
    external fun retryTransfer(transferId: String): Boolean
    external fun configureDeviceIdentity(deviceId: String, deviceName: String): Boolean
    external fun stopFileServer()
}
