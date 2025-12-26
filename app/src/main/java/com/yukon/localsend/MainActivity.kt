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

object TransferManager {
    // "正在发送...", "正在接收...", "完成"
    val status = mutableStateOf("空闲")
    val currentFileName = mutableStateOf("")
    // 0.0 - 1.0f
    val progress = mutableFloatStateOf(0f)
    val isTransferring = mutableStateOf(false)

    fun updateProgress(transferred: Long, total: Long) {
        if (total > 0) {
            progress.floatValue = transferred.toFloat() / total.toFloat()
        }
    }

    fun startTransfer(filename: String, isSending: Boolean) {
        isTransferring.value = true
        currentFileName.value = filename
        progress.floatValue = 0f
        status.value = if (isSending) "正在发送..." else "正在接收..."
    }

    fun finishTransfer(success: Boolean, msg: String) {
        isTransferring.value = false
        progress.floatValue = if (success) 1.0f else 0f
        status.value = if (success) "传输成功" else "失败: $msg"
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

        val publicDownloadDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )

        val myDir = File(publicDownloadDir, "localsend_yukon")
        if (!myDir.exists()) {
            val created = myDir.mkdirs()
            if (!created) {
                Log.e("YukonTest", "无法创建文件夹: ${myDir.absolutePath}")
            }
        }

        val saveDir = myDir.absolutePath
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
                    onSwitchToStatus = { selectedTab = 1 }
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
    onSwitchToStatus: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LocalSend - ${DeviceManager.myName}") },
                actions = {
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
