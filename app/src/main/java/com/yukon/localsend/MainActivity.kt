package com.yukon.localsend

import android.content.Context
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp


class MainActivity : ComponentActivity() {
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val randomId = (1000..9999).random()
        val localName = "Android-$randomId"
        DeviceManager.myName = localName

        setupMulticastLock()

        try {
            RustSDK.startDiscovery(localName)
            Log.d("YukonTest", "Rust 发现服务启动成功")
        } catch (e: Exception) {
            Log.e("YukonTest", "Rust 发现服务启动失败 ${e.message}")
        }

        RustSDK.discoverOnce()

//        RustSDK.testCallback()
        enableEdgeToEdge()
        setContent {
            LocalSendTheme {
                Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            DiscoveryTopBar(
                                onRefresh = {
                                    RustSDK.discoverOnce()
                                }
                            )
                        }
                    ) { innerPadding ->
                    DiscoveryScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
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
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
    }
}

@Composable
fun DiscoveryScreen(modifier: Modifier = Modifier) {
    val discoveredDevices = DeviceManager.devices

    Column(modifier.fillMaxSize().padding(16.dp)){
        Text(
            text = "局域网设备发现中...",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

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

                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            Text(text = name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = ip,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryTopBar(onRefresh: () -> Unit) {
    androidx.compose.material3.TopAppBar(
        title = { Text("LocalSend") },
        actions = {
            androidx.compose.material3.IconButton(onClick = onRefresh) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
                    contentDescription = "Refresh"
                )
            }
        }
    )
}
