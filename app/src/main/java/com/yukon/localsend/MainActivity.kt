package com.yukon.localsend

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.yukon.localsend.ui.theme.LocalSendTheme
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.provider.OpenableColumns
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import java.io.FileOutputStream
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context.MODE_PRIVATE
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.core.content.FileProvider
import java.net.URLConnection

object TransferManager {
    // "正在发送...", "正在接收...", "完成"
    val status = mutableStateOf("空闲")
    val currentFileName = mutableStateOf("")
    // 0.0 - 1.0f
    val progress = mutableFloatStateOf(0f)
    val isTransferring = mutableStateOf(false)
    val speedText = mutableStateOf("")
    val saveDir = mutableStateOf("")
    val lastReceivedFilePath = mutableStateOf<String?>(null)
    val lastReceivedDirPath = mutableStateOf<String?>(null)
    val requestTab = mutableIntStateOf(-1)
    val noticeText = mutableStateOf("")

    private val isSending = mutableStateOf(false)
    private val totalBytes = mutableStateOf<Long?>(null)
    private val transferredBytes = mutableStateOf<Long?>(null)
    private val lastSpeedTimeMs = mutableStateOf<Long?>(null)
    private val lastSpeedBytes = mutableStateOf<Long?>(null)
    private val collisionOriginalName = mutableStateOf<String?>(null)
    private val collisionTempPath = mutableStateOf<String?>(null)

    private fun formatBytesPerSec(bytesPerSec: Double): String {
        if (bytesPerSec <= 0) return "0 B/s"
        val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
        var v = bytesPerSec
        var idx = 0
        while (v >= 1024 && idx < units.lastIndex) {
            v /= 1024.0
            idx++
        }
        return if (v >= 100) String.format("%.0f %s", v, units[idx])
        else if (v >= 10) String.format("%.1f %s", v, units[idx])
        else String.format("%.2f %s", v, units[idx])
    }

    fun updateProgress(transferred: Long, total: Long) {
        transferredBytes.value = transferred
        totalBytes.value = if (total > 0) total else null

        if (total > 0) {
            progress.floatValue = transferred.toFloat() / total.toFloat()
        }

        val now = System.currentTimeMillis()
        val lastT = lastSpeedTimeMs.value
        val lastB = lastSpeedBytes.value
        if (lastT != null && lastB != null) {
            val dt = now - lastT
            if (dt >= 350) {
                val db = transferred - lastB
                val bps = (db.toDouble() * 1000.0) / dt.toDouble()
                speedText.value = formatBytesPerSec(bps)
                lastSpeedTimeMs.value = now
                lastSpeedBytes.value = transferred
            }
        } else {
            lastSpeedTimeMs.value = now
            lastSpeedBytes.value = transferred
        }
    }

    fun startTransfer(filename: String, isSending: Boolean, total: Long? = null, notice: String = "") {
        isTransferring.value = true
        currentFileName.value = filename
        progress.floatValue = 0f
        status.value = if (isSending) "正在发送..." else "正在接收..."
        this.isSending.value = isSending
        speedText.value = ""
        noticeText.value = notice
        totalBytes.value = total
        transferredBytes.value = null
        lastSpeedTimeMs.value = null
        lastSpeedBytes.value = null
        lastReceivedFilePath.value = null
        lastReceivedDirPath.value = null
        collisionOriginalName.value = null
        collisionTempPath.value = null
        if (!isSending) {
            requestTab.intValue = 1
        }
    }

    fun markIncomingNameCollision(originalName: String, tempPath: String) {
        collisionOriginalName.value = originalName
        collisionTempPath.value = tempPath
    }

    fun finishTransfer(success: Boolean, msg: String) {
        isTransferring.value = false
        progress.floatValue = if (success) 1.0f else 0f
        status.value = if (success) "传输成功" else "失败: $msg"
        if (!isSending.value) {
            val dir = saveDir.value
            val originalName = collisionOriginalName.value
            val tempPath = collisionTempPath.value
            if (originalName != null && tempPath != null && dir.isNotBlank()) {
                val tempFile = File(tempPath)
                val receivedFile = File(dir, originalName)

                if (success && receivedFile.exists()) {
                    val finalName = nextAvailableName(dir, originalName)
                    val finalFile = File(dir, finalName)
                    val movedReceived = receivedFile.renameTo(finalFile)
                    if (movedReceived) {
                        val restored = tempFile.renameTo(File(dir, originalName))
                        if (restored) {
                            currentFileName.value = finalName
                            lastReceivedDirPath.value = dir
                            lastReceivedFilePath.value = finalFile.absolutePath
                            noticeText.value = "检测到同名文件，已自动保存为：$finalName"
                            return
                        } else {
                            noticeText.value = "同名文件处理部分失败：原文件未能恢复为原名"
                            lastReceivedDirPath.value = dir
                            lastReceivedFilePath.value = finalFile.absolutePath
                            currentFileName.value = finalName
                            return
                        }
                    } else {
                        noticeText.value = "检测到同名文件，但无法自动重命名，已保留为：$originalName"
                    }
                }

                if (!success) {
                    val originalPath = File(dir, originalName)
                    if (!originalPath.exists() && tempFile.exists()) {
                        tempFile.renameTo(originalPath)
                    }
                }
            }
        }

        if (success && !isSending.value) {
            val dir = saveDir.value
            if (dir.isNotBlank() && currentFileName.value.isNotBlank()) {
                lastReceivedDirPath.value = dir
                lastReceivedFilePath.value = File(dir, currentFileName.value).absolutePath
            }
        }
    }
}

internal fun nextAvailableName(dirPath: String, fileName: String): String {
    val dir = File(dirPath)
    val dot = fileName.lastIndexOf('.')
    val base = if (dot > 0) fileName.substring(0, dot) else fileName
    val ext = if (dot > 0 && dot < fileName.length - 1) fileName.substring(dot + 1) else ""
    for (i in 1..9999) {
        val candidate = if (ext.isNotBlank()) "${base}($i).$ext" else "${base}($i)"
        if (!File(dir, candidate).exists()) return candidate
    }
    val fallback = System.currentTimeMillis().toString()
    return if (ext.isNotBlank()) "${base}($fallback).$ext" else "${base}($fallback)"
}

internal fun tempNameForCollision(fileName: String): String {
    val ts = System.currentTimeMillis()
    return ".localsend_tmp_${ts}_$fileName"
}

object AppSettings {
    private const val PREFS = "localsend_prefs"
    private const val KEY_SAVE_DIR = "save_dir"

    fun getDefaultSaveDir(context: Context): String {
        val publicDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(publicDownloadDir, "localsend_yukon").absolutePath
    }

    fun getSaveDir(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getString(KEY_SAVE_DIR, null)
        return saved?.takeIf { it.isNotBlank() } ?: getDefaultSaveDir(context)
    }

    fun setSaveDir(context: Context, dir: String) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SAVE_DIR, dir).apply()
    }

    fun clearSaveDir(context: Context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_SAVE_DIR).apply()
    }
}

class MainActivity : ComponentActivity() {
    private var multicastLock: WifiManager.MulticastLock? = null

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (WifiManager.WIFI_STATE_CHANGED_ACTION == intent.action) {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    Log.d("MainActivity", "WiFi 已开启，尝试重启服务")
                    startLocalSendServices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        registerReceiver(wifiStateReceiver, filter)

        startLocalSendServices()

        enableEdgeToEdge()
        setContent {
            LocalSendTheme {
                MainScreen()
            }
        }
    }

    private fun startLocalSendServices() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            Log.w("MainActivity", "WiFi 未开启，可能无法发现设备")
            runOnUiThread {
                Toast.makeText(this, "请开启 WiFi 以使用设备发现功能", Toast.LENGTH_LONG).show()
            }
        }

        val localName = RustSDK.currentAlias ?: "Android-${(1000..9999).random()}"
        DeviceManager.myName = localName

        setupMulticastLock()

        try {
            RustSDK.startDiscoverySafe(localName)
            Log.d("YukonTest", "Rust 发现服务启动 (Safe)")
        } catch (e: Exception) {
            Log.e("YukonTest", "Rust 发现服务启动失败 ${e.message}")
        }

        val saveDirFromSettings = AppSettings.getSaveDir(this)
        val myDir = File(saveDirFromSettings)
        if (!myDir.exists()) {
            val created = myDir.mkdirs()
            if (!created) {
                Log.e("YukonTest", "无法创建文件夹: ${myDir.absolutePath}")
            }
        }

        val saveDir = myDir.absolutePath
        TransferManager.saveDir.value = saveDir
        try {
            RustSDK.startFileServerSafe(saveDir)
            Log.d("YukonTest", "Rust 文件接收服务启动 (Safe)，保存路径: $saveDir")
        } catch (e: Exception) {
            Log.e("YukonTest", "Rust 文件接收服务启动失败 ${e.message}")
        }

        RustSDK.discoverOnce(localName)
    }

    private fun setupMulticastLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("localsend_lock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(wifiStateReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "注销广播接收器失败", e)
        }
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
    }

}

@Composable
fun MainScreen() {
    // 页面状态：0 -> 发现页, 1 -> 接收/状态页
    var selectedTab by remember { mutableIntStateOf(0) }
    val appScope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(TransferManager.requestTab.intValue) {
        if (TransferManager.requestTab.intValue == 1) {
            selectedTab = 1
            TransferManager.requestTab.intValue = -1
        }
    }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "发现") },
                    label = { Text("发送") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Share, contentDescription = "接收") },
                    label = { Text("接收") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (selectedTab == 0) {
                DiscoveryScreenWithTopBar(
                    appScope = appScope,
                    onSwitchToStatus = { selectedTab = 1 },
                    onOpenSettings = { showSettings = true }
                )
            } else {
                ReceiveScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreenWithTopBar(
    appScope: kotlinx.coroutines.CoroutineScope,
    onSwitchToStatus: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LocalSend - ${DeviceManager.myName}") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { RustSDK.discoverOnce(DeviceManager.myName) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        DiscoveryScreen(
            appScope = appScope,
            modifier = Modifier.padding(padding),
            onDeviceSelected = { deviceIp, deviceName ->
                // 这里暂存一下要发送给谁，实际逻辑在 DiscoveryScreen 内部的文件选择器回调里
            },
            onSwitchToStatus = onSwitchToStatus
        )
    }
}

@Composable
fun DiscoveryScreen(
    appScope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
    onDeviceSelected: (String, String) -> Unit,
    onSwitchToStatus: () -> Unit
) {
    val discoveredDevices = DeviceManager.devices
    val context = LocalContext.current

    // 获取本机IP用于显示
    var myIpList by remember { mutableStateOf<List<NetworkInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            myIpList = NetworkUtils.getAllLocalIps()
        }
    }

    var pendingTargetIp by remember { mutableStateOf<String?>(null) }


    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            pendingTargetIp?.let { targetIp ->
                appScope.launch {
                    TransferManager.startTransfer("处理文件中...", true)
                    onSwitchToStatus()

                    val realPath = withContext(Dispatchers.IO) {
                        copyUriToCache(context, it)
                    }

                    if (realPath != null) {
                        val fileObj = File(realPath)
                        TransferManager.currentFileName.value = File(realPath).name
                        Log.d("YukonSend", "开始发送文件: $realPath -> $targetIp")
                        // 3. 调用 Rust 发送
                        withContext(Dispatchers.IO) {
                            RustSDK.sendFile(targetIp, realPath)
                        }
                    } else {
                        TransferManager.finishTransfer(false, "文件解析失败")
                    }
                }
            }
        }
    }

    Column(modifier
        .fillMaxSize()
        .padding(16.dp)){
        Text(
            text = "点击设备发送文件",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (myIpList.isNotEmpty()) {
            Text(
                text = "本机 IP: ${myIpList.joinToString { it.ip }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        } else {
            Text(
                text = "未检测到有效网络 IP",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        HorizontalDivider()

        if (discoveredDevices.isEmpty()) {
            Text(
                text = "no device",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            LazyColumn {
                items(discoveredDevices) { info ->
                    val parts = info.split("|")
                    if (parts.size >= 3) {
                        val name = parts[1]
                        val ip = parts[2]

                        DeviceItem(
                            name = name,
                            ip = ip,
                            onClick = {
                                pendingTargetIp = ip
                                // 打开系统文件选择器，过滤所有文件
                                fileLauncher.launch("*/*")
                            }
                        )
                    }
                }
            }
        }

    }
}

@Composable
fun DeviceItem(name: String, ip: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() }, // 添加点击事件
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = ip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun ReceiveScreen() {
    val status by TransferManager.status
    val fileName by TransferManager.currentFileName
    val progress by TransferManager.progress
    val isTransferring by TransferManager.isTransferring
    val speed by TransferManager.speedText
    val notice by TransferManager.noticeText
    val context = LocalContext.current
    val receivedPath by TransferManager.lastReceivedFilePath
    val receivedDir by TransferManager.lastReceivedDirPath

    var ipList by remember { mutableStateOf<List<NetworkInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ipList = NetworkUtils.getAllLocalIps()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "本机名: ${DeviceManager.myName}", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "可用 IP 地址:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 3. 遍历显示所有 IP
                if (ipList.isEmpty()) {
                    Text("未检测到网络连接", color = MaterialTheme.colorScheme.error)
                } else {
                    ipList.forEach { info ->
                        IpAddressRow(info)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "状态: 准备接收 (端口 4060)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }//

        Text(text = status, style = MaterialTheme.typography.headlineSmall)
        if (notice.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (fileName.isNotEmpty()) {
            Text(text = "文件: $fileName", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "${(progress * 100).toInt()}%")

        if (isTransferring && speed.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "速度: $speed", style = MaterialTheme.typography.bodyMedium)
        }

        if (!isTransferring && status == "传输成功" && (receivedPath != null || receivedDir != null)) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = receivedPath != null,
                    onClick = { openFile(context, receivedPath) }
                ) {
                    Text("打开文件")
                }
                OutlinedButton(
                    enabled = receivedDir != null,
                    onClick = { openFolder(context, receivedDir) }
                ) {
                    Text("打开文件夹")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var currentDir by remember { mutableStateOf(AppSettings.getSaveDir(context)) }
    var inputDir by remember { mutableStateOf(currentDir) }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
            }

            val path = tryResolveTreeToFilePath(context, uri)
            if (path.isNullOrBlank()) {
                Toast.makeText(context, "该目录无法解析为文件路径，暂不支持", Toast.LENGTH_LONG).show()
            } else {
                inputDir = path
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("下载保存位置", style = MaterialTheme.typography.titleMedium)
            Text("当前: $currentDir", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

            OutlinedTextField(
                value = inputDir,
                onValueChange = { inputDir = it },
                label = { Text("自定义目录路径") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val dir = inputDir.trim()
                    if (dir.isBlank()) {
                        Toast.makeText(context, "目录不能为空", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val f = File(dir)
                    if (!f.exists()) f.mkdirs()
                    if (!f.exists() || !f.isDirectory) {
                        Toast.makeText(context, "目录不可用: $dir", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    AppSettings.setSaveDir(context, f.absolutePath)
                    currentDir = AppSettings.getSaveDir(context)
                    TransferManager.saveDir.value = currentDir
                    Toast.makeText(context, "已保存（重启后对接收服务生效）", Toast.LENGTH_LONG).show()
                }) {
                    Text("保存")
                }

                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }
                    treePicker.launch(intent)
                }) {
                    Text("选择文件夹")
                }
            }

            OutlinedButton(onClick = {
                AppSettings.clearSaveDir(context)
                currentDir = AppSettings.getSaveDir(context)
                inputDir = currentDir
                TransferManager.saveDir.value = currentDir
                Toast.makeText(context, "已恢复默认（重启后对接收服务生效）", Toast.LENGTH_LONG).show()
            }) {
                Text("恢复默认位置")
            }
        }
    }
}

private fun tryResolveTreeToFilePath(context: Context, treeUri: Uri): String? {
    if (!DocumentsContract.isTreeUri(treeUri)) return null
    val docId = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (_: Exception) {
        return null
    }
    val parts = docId.split(":")
    if (parts.size != 2) return null
    val volume = parts[0]
    val rel = parts[1].trimStart('/')
    val base = if (volume == "primary") {
        Environment.getExternalStorageDirectory().absolutePath
    } else {
        "/storage/$volume"
    }
    return if (rel.isBlank()) base else "$base/$rel"
}

private fun openFile(context: Context, filePath: String?) {
    if (filePath.isNullOrBlank()) return
    val file = File(filePath)
    if (!file.exists()) {
        Toast.makeText(context, "文件不存在: $filePath", Toast.LENGTH_LONG).show()
        return
    }

    val ext = file.extension.lowercase()
    val mimeFromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    val mime = mimeFromExt ?: URLConnection.guessContentTypeFromName(file.name) ?: "*/*"

    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: Exception) {
        Uri.fromFile(file)
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "打开文件"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "没有可打开该文件的应用", Toast.LENGTH_LONG).show()
    }
}

private fun openFolder(context: Context, dirPath: String?) {
    if (dirPath.isNullOrBlank()) return
    val dir = File(dirPath)
    if (!dir.exists() || !dir.isDirectory) {
        Toast.makeText(context, "文件夹不存在: $dirPath", Toast.LENGTH_LONG).show()
        return
    }

    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dir)
    } catch (_: Exception) {
        Uri.fromFile(dir)
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "resource/folder")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "系统未找到可打开文件夹的应用: $dirPath", Toast.LENGTH_LONG).show()
    }
}

fun copyUriToCache(context: Context, uri: Uri): String? {
    try {
        val contentResolver = context.contentResolver
        var fileName = "temp_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) fileName = cursor.getString(index)
            }
        }

        val cacheFile = File(context.cacheDir, fileName)

        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }

        return cacheFile.absolutePath
    } catch (e: Exception) {
        Log.e("Utils", "文件复制失败", e)
        return null
    }
}

@Composable
fun IpAddressRow(info: NetworkInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when (info.name) {
            "WiFi" -> Icons.Default.Wifi
            "热点" -> Icons.Default.WifiTethering
            "以太网" -> Icons.Default.SettingsEthernet
            else -> Icons.Default.Info
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = info.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(60.dp)
        )

        Text(
            text = info.ip,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}
