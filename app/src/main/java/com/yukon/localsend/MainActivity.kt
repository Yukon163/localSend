package com.yukon.localsend

import android.content.Context
import android.content.ContentValues
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
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
import androidx.compose.foundation.Canvas
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import android.provider.OpenableColumns
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import android.content.ActivityNotFoundException
import android.content.Context.MODE_PRIVATE
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.core.content.FileProvider
import java.net.URLConnection
import org.json.JSONObject
import java.util.UUID

data class TransferSegmentUi(
    val start: Long,
    val end: Long,
    val downloadedEnd: Long,
    val running: Boolean,
)

data class TransferDiagnosticsUi(
    val hashingMs: Long = 0,
    val negotiatingMs: Long = 0,
    val transferringMs: Long = 0,
    val verifyingMs: Long = 0,
    val wireBytes: Long = 0,
    val retransmittedBytes: Long = 0,
    val splitTrials: Int = 0,
    val rollbacks: Int = 0,
    val retries: Int = 0,
    val timeoutsOrResets: Int = 0,
)

data class TransferUiState(
    val transferId: String,
    val direction: String,
    val state: String,
    val fileName: String,
    val durableBytes: Long,
    val inFlightBytes: Long,
    val totalBytes: Long,
    val activeWorkers: Int,
    val allocatedWorkers: Int = 0,
    val targetWorkers: Int,
    val speedBytesPerSecond: Double,
    val elapsedMs: Long,
    val stageBytes: Long = 0,
    val stageTotalBytes: Long = 0,
    val errorCode: String? = null,
    val message: String? = null,
    val segments: List<TransferSegmentUi> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val diagnostics: TransferDiagnosticsUi = TransferDiagnosticsUi(),
)

internal fun recentTransferStates(
    transfers: Collection<TransferUiState>,
    limit: Int = 4,
): List<TransferUiState> = transfers
    .sortedByDescending(TransferUiState::updatedAtMs)
    .take(limit.coerceAtLeast(0))

internal fun isStrictFileDescendant(root: File, candidate: File): Boolean = runCatching {
    val canonicalRoot = root.canonicalFile
    val canonicalCandidate = candidate.canonicalFile
    canonicalCandidate.path.startsWith(canonicalRoot.path + File.separator)
}.getOrDefault(false)

internal fun saturatingAddBytes(left: Long, right: Long): Long {
    val safeLeft = left.coerceAtLeast(0L)
    val safeRight = right.coerceAtLeast(0L)
    return if (Long.MAX_VALUE - safeLeft < safeRight) Long.MAX_VALUE else safeLeft + safeRight
}

internal fun parseTransferSegments(event: JSONObject, totalBytes: Long): List<TransferSegmentUi> {
    if (totalBytes <= 0L) return emptyList()
    val encoded = event.optJSONArray("segments") ?: return emptyList()
    val decoded = buildList {
        for (index in 0 until encoded.length()) {
            val segment = encoded.optJSONObject(index) ?: continue
            add(
                TransferSegmentUi(
                    start = segment.optLong("start", -1L),
                    end = segment.optLong("end", -1L),
                    downloadedEnd = segment.optLong("downloadedEnd", -1L),
                    running = segment.optBoolean("running", false),
                )
            )
        }
    }
    return normalizeTransferSegments(decoded, totalBytes)
}

internal fun normalizeTransferSegments(
    segments: List<TransferSegmentUi>,
    totalBytes: Long,
): List<TransferSegmentUi> {
    if (totalBytes <= 0L) return emptyList()
    return segments.mapNotNull { segment ->
        if (segment.start < 0L || segment.start >= totalBytes || segment.end <= segment.start) {
            return@mapNotNull null
        }
        val boundedEnd = segment.end.coerceAtMost(totalBytes)
        segment.copy(
            end = boundedEnd,
            downloadedEnd = segment.downloadedEnd.coerceIn(segment.start, boundedEnd),
        )
    }.sortedWith(compareBy(TransferSegmentUi::start, TransferSegmentUi::end))
}

private const val PUBLIC_DOWNLOAD_SUBDIRECTORY = "localsend_yukon"
private const val PUBLICATION_PREFS = "localsend_publications"
private const val PUBLICATION_STATE_PREPARING = "preparing"
private const val PUBLICATION_STATE_COPYING = "copying"
private const val PUBLICATION_STATE_PUBLISHED = "published"

private sealed interface PublishResult {
    data class Published(
        val uri: Uri,
        val displayName: String,
    ) : PublishResult

    data class Retained(val reason: String) : PublishResult
}

private data class PublicationReceipt(
    val state: String,
    val uri: Uri?,
    val sourceSize: Long,
    val sourceLastModified: Long,
)

private fun publicationKey(stagingFile: File): String =
    runCatching { stagingFile.canonicalPath }.getOrElse { stagingFile.absolutePath }

private fun readPublicationReceipt(context: Context, stagingFile: File): PublicationReceipt? {
    val encoded = context.getSharedPreferences(PUBLICATION_PREFS, MODE_PRIVATE)
        .getString(publicationKey(stagingFile), null)
        ?: return null
    return runCatching {
        val json = JSONObject(encoded)
        PublicationReceipt(
            state = json.getString("state"),
            uri = json.optString("uri").takeIf(String::isNotBlank)?.let(Uri::parse),
            sourceSize = json.getLong("sourceSize"),
            sourceLastModified = json.getLong("sourceLastModified"),
        )
    }.getOrNull()
}

private fun writePublicationReceipt(
    context: Context,
    stagingFile: File,
    state: String,
    uri: Uri?,
): Boolean {
    val encoded = JSONObject()
        .put("state", state)
        .put("uri", uri?.toString() ?: "")
        .put("sourceSize", stagingFile.length())
        .put("sourceLastModified", stagingFile.lastModified())
        .toString()
    return context.getSharedPreferences(PUBLICATION_PREFS, MODE_PRIVATE)
        .edit()
        .putString(publicationKey(stagingFile), encoded)
        .commit()
}

private fun clearPublicationReceipt(context: Context, stagingFile: File) {
    context.getSharedPreferences(PUBLICATION_PREFS, MODE_PRIVATE)
        .edit()
        .remove(publicationKey(stagingFile))
        .commit()
}

private fun publishedUri(context: Context, stagingFile: File): Uri? {
    val receipt = readPublicationReceipt(context, stagingFile) ?: return null
    if (receipt.state != PUBLICATION_STATE_PUBLISHED
        || receipt.sourceSize != stagingFile.length()
        || receipt.sourceLastModified != stagingFile.lastModified()
    ) {
        clearPublicationReceipt(context, stagingFile)
        return null
    }
    val uri = receipt.uri ?: return null
    val readable = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
    if (!readable) {
        clearPublicationReceipt(context, stagingFile)
        return null
    }
    return uri
}

internal fun resolveCompletedStagingFile(stagingDir: File, completedName: String): File? {
    val safeName = File(completedName).name.takeIf { it.isNotBlank() && it != "." && it != ".." }
        ?: return null
    val root = runCatching { stagingDir.canonicalFile }.getOrNull() ?: return null
    val candidate = runCatching { File(root, safeName).canonicalFile }.getOrNull() ?: return null
    return candidate.takeIf { it.parentFile == root }
}

private fun mimeTypeForName(fileName: String): String {
    val extension = File(fileName).extension.lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: URLConnection.guessContentTypeFromName(fileName)
        ?: "application/octet-stream"
}

private fun publishStagingFile(context: Context, stagingFile: File): PublishResult {
    if (!stagingFile.isFile) {
        return PublishResult.Retained("暂存文件不存在，无法导出")
    }
    publishedUri(context, stagingFile)?.let { uri ->
        return PublishResult.Published(uri = uri, displayName = stagingFile.name)
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return PublishResult.Retained("Android 10 以下暂不自动导出，文件仍安全保存在应用暂存目录")
    }

    if (!writePublicationReceipt(
            context,
            stagingFile,
            PUBLICATION_STATE_PREPARING,
            null,
        )
    ) {
        return PublishResult.Retained("无法持久化导出任务，已保留暂存文件")
    }

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, stagingFile.name)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeForName(stagingFile.name))
        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DOWNLOAD_SUBDIRECTORY",
        )
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = try {
        resolver.insert(collection, values)
            ?: run {
                clearPublicationReceipt(context, stagingFile)
                return PublishResult.Retained("系统下载目录拒绝创建文件，已保留暂存文件")
            }
    } catch (error: Exception) {
        Log.e("TransferManager", "Unable to create MediaStore download", error)
        clearPublicationReceipt(context, stagingFile)
        return PublishResult.Retained("无法访问系统下载目录，已保留暂存文件")
    }

    return try {
        if (!writePublicationReceipt(
                context,
                stagingFile,
                PUBLICATION_STATE_COPYING,
                uri,
            )
        ) {
            error("Unable to persist pending MediaStore URI")
        }
        val descriptor = resolver.openFileDescriptor(uri, "w")
            ?: error("MediaStore returned no file descriptor")
        stagingFile.inputStream().use { input ->
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }

        val ready = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        if (resolver.update(uri, ready, null, null) <= 0) {
            error("MediaStore failed to finalize pending download")
        }
        if (!writePublicationReceipt(
                context,
                stagingFile,
                PUBLICATION_STATE_PUBLISHED,
                uri,
            )
        ) {
            error("Unable to persist completed MediaStore URI")
        }

        PublishResult.Published(
            uri = uri,
            displayName = stagingFile.name,
        )
    } catch (error: Exception) {
        Log.e("TransferManager", "Unable to publish received file", error)
        runCatching { resolver.delete(uri, null, null) }
        clearPublicationReceipt(context, stagingFile)
        PublishResult.Retained("导出到系统下载目录失败，已保留暂存文件")
    }
}

private fun reconcileInterruptedPublicationRecords(context: Context) {
    val preferences = context.getSharedPreferences(PUBLICATION_PREFS, MODE_PRIVATE)
    val editor = preferences.edit()
    preferences.all.forEach { (sourcePath, encoded) ->
        val json = runCatching { JSONObject(encoded as String) }.getOrNull()
        val state = json?.optString("state")
        if (state == PUBLICATION_STATE_PREPARING || state == PUBLICATION_STATE_COPYING) {
            json.optString("uri").takeIf(String::isNotBlank)?.let(Uri::parse)?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            editor.remove(sourcePath)
        }
    }
    editor.commit()
}

private fun removeOrphanedPendingDownloads(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DOWNLOAD_SUBDIRECTORY/"
    runCatching {
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
            "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(relativePath),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val ownerColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(ownerColumn) == context.packageName) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }
    }.onFailure { error ->
        Log.w("TransferManager", "Unable to reconcile pending MediaStore rows", error)
    }
}

object TransferManager {
    val transfers = mutableStateMapOf<String, TransferUiState>()
    val activeTransferId = mutableStateOf<String?>(null)

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
    private val outgoingCachePaths = mutableMapOf<String, String>()
    private val outgoingTargets = mutableMapOf<String, String>()
    private val outgoingUris = mutableMapOf<String, String>()
    private val outgoingFileNames = mutableMapOf<String, String>()
    private val outgoingFileSizes = mutableMapOf<String, Long>()
    private val publicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val publicationMutex = Mutex()
    private val publicationLock = Any()
    private val publishingPaths = mutableSetOf<String>()
    private var appContext: Context? = null
    private var initializedStagingRoot: String? = null

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

    fun updatePreparation(filename: String, stage: String, processed: Long, total: Long) {
        isTransferring.value = true
        isSending.value = true
        currentFileName.value = filename
        status.value = stage
        totalBytes.value = total.takeIf { it > 0L }
        transferredBytes.value = processed
        progress.floatValue = if (total > 0L) {
            (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    fun registerOutgoingTransfer(
        context: Context,
        transferId: String,
        filePath: String,
        targetIp: String,
        replacesTransferId: String? = null,
    ) {
        appContext = context.applicationContext
        activeTransferId.value = transferId
        outgoingCachePaths[transferId] = filePath
        outgoingTargets[transferId] = targetIp
        val outgoingMeta = JSONObject()
            .put("filePath", filePath)
            .put("targetIp", targetIp)
            .toString()
        context.getSharedPreferences("localsend_outgoing", MODE_PRIVATE)
            .edit()
            .putString(transferId, outgoingMeta)
            .also { editor ->
                if (replacesTransferId != null && replacesTransferId != transferId) {
                    editor.remove(replacesTransferId)
                }
            }
            .apply()
        val existing = transfers[transferId]
        if (existing == null) {
            transfers[transferId] = TransferUiState(
                transferId = transferId,
                direction = "send",
                state = "hashing",
                fileName = File(filePath).name,
                durableBytes = 0,
                inFlightBytes = 0,
                totalBytes = File(filePath).length(),
                activeWorkers = 0,
                targetWorkers = 8,
                speedBytesPerSecond = 0.0,
                elapsedMs = 0,
            )
        }
        if (replacesTransferId != null && replacesTransferId != transferId) {
            transfers.remove(replacesTransferId)
            outgoingCachePaths.remove(replacesTransferId)
            outgoingTargets.remove(replacesTransferId)
        }
    }

    fun registerOutgoingUriTransfer(
        context: Context,
        transferId: String,
        uri: Uri,
        fileName: String,
        fileSize: Long,
        targetIp: String,
        replacesTransferId: String? = null,
    ) {
        appContext = context.applicationContext
        activeTransferId.value = transferId
        outgoingUris[transferId] = uri.toString()
        outgoingFileNames[transferId] = fileName
        outgoingFileSizes[transferId] = fileSize
        outgoingTargets[transferId] = targetIp
        val outgoingMeta = JSONObject()
            .put("uri", uri.toString())
            .put("fileName", fileName)
            .put("fileSize", fileSize)
            .put("targetIp", targetIp)
            .toString()
        context.getSharedPreferences("localsend_outgoing", MODE_PRIVATE)
            .edit()
            .putString(transferId, outgoingMeta)
            .also { editor ->
                if (replacesTransferId != null && replacesTransferId != transferId) {
                    editor.remove(replacesTransferId)
                }
            }
            .apply()
        transfers.putIfAbsent(
            transferId,
            TransferUiState(
                transferId = transferId,
                direction = "send",
                state = "hashing",
                fileName = fileName,
                durableBytes = 0,
                inFlightBytes = 0,
                totalBytes = fileSize,
                activeWorkers = 0,
                targetWorkers = 8,
                speedBytesPerSecond = 0.0,
                elapsedMs = 0,
            ),
        )
        if (replacesTransferId != null && replacesTransferId != transferId) {
            transfers.remove(replacesTransferId)
            outgoingCachePaths.remove(replacesTransferId)
            outgoingTargets.remove(replacesTransferId)
            outgoingUris.remove(replacesTransferId)
            outgoingFileNames.remove(replacesTransferId)
            outgoingFileSizes.remove(replacesTransferId)
        }
    }

    fun restoreOutgoingTransfers(context: Context) {
        appContext = context.applicationContext
        val records = context.getSharedPreferences("localsend_outgoing", MODE_PRIVATE).all
        records.forEach { (transferId, encoded) ->
            val meta = runCatching { JSONObject(encoded as String) }.getOrNull() ?: return@forEach
            val filePath = meta.optString("filePath")
            val uri = meta.optString("uri")
            val fileName = meta.optString("fileName")
            val fileSize = meta.optLong("fileSize", -1L)
            val targetIp = meta.optString("targetIp")
            val file = File(filePath)
            if (targetIp.isBlank() || (uri.isBlank() && !file.exists())) return@forEach
            if (uri.isNotBlank()) {
                outgoingUris[transferId] = uri
                outgoingFileNames[transferId] = fileName
                outgoingFileSizes[transferId] = fileSize
            } else {
                outgoingCachePaths[transferId] = filePath
            }
            outgoingTargets[transferId] = targetIp
            transfers[transferId] = TransferUiState(
                transferId = transferId,
                direction = "send",
                state = "failed",
                fileName = if (uri.isNotBlank()) fileName else file.name,
                durableBytes = 0,
                inFlightBytes = 0,
                totalBytes = if (uri.isNotBlank()) fileSize.coerceAtLeast(0L) else file.length(),
                activeWorkers = 0,
                targetWorkers = 0,
                speedBytesPerSecond = 0.0,
                elapsedMs = 0,
                errorCode = "process_restarted",
                message = "发送进程已重启，可从接收端 durable 进度继续",
            )
        }
    }

    fun initializeIncomingStorage(context: Context, stagingDir: File) {
        val applicationContext = context.applicationContext
        val canonicalRoot = runCatching { stagingDir.canonicalPath }
            .getOrElse { stagingDir.absolutePath }
        appContext = applicationContext
        saveDir.value = canonicalRoot
        val shouldRecover = synchronized(publicationLock) {
            if (initializedStagingRoot == canonicalRoot) {
                false
            } else {
                initializedStagingRoot = canonicalRoot
                true
            }
        }
        if (!shouldRecover) return

        publicationScope.launch {
            publicationMutex.withLock {
                reconcileInterruptedPublicationRecords(applicationContext)
                removeOrphanedPendingDownloads(applicationContext)
            }
            val completedFiles = File(canonicalRoot).listFiles()
                ?.filter(File::isFile)
                .orEmpty()
            withContext(Dispatchers.Main.immediate) {
                completedFiles.forEach { file ->
                    publishCompletedIncoming("recovery:${publicationKey(file)}", file.name)
                }
            }
        }
    }

    private fun shouldProjectPublication(completionKey: String): Boolean {
        return activeTransferId.value == completionKey
            || (completionKey.startsWith("legacy:") && !isTransferring.value)
    }

    private fun publishCompletedIncoming(completionKey: String, completedName: String) {
        val context = appContext
        val stagingRoot = saveDir.value.takeIf { it.isNotBlank() }?.let(::File)
        if (context == null || stagingRoot == null) {
            if (shouldProjectPublication(completionKey)) {
                noticeText.value = "接收已完成，但应用暂存目录尚未初始化"
            }
            return
        }
        val stagingFile = resolveCompletedStagingFile(stagingRoot, completedName)
        if (stagingFile == null || !stagingFile.isFile) {
            if (shouldProjectPublication(completionKey)) {
                noticeText.value = "接收已完成，但找不到可导出的暂存文件"
                lastReceivedFilePath.value = null
                lastReceivedDirPath.value = stagingRoot.absolutePath
            }
            return
        }

        val pathKey = publicationKey(stagingFile)
        val started = synchronized(publicationLock) { publishingPaths.add(pathKey) }
        if (!started) return

        if (shouldProjectPublication(completionKey)) {
            currentFileName.value = stagingFile.name
            lastReceivedFilePath.value = stagingFile.absolutePath
            lastReceivedDirPath.value = stagingRoot.absolutePath
            status.value = "正在保存到下载目录..."
            noticeText.value = "文件已接收，正在导出到 Downloads/$PUBLIC_DOWNLOAD_SUBDIRECTORY"
        }

        publicationScope.launch {
            try {
                val result = publicationMutex.withLock {
                    publishStagingFile(context, stagingFile)
                }
                withContext(Dispatchers.Main.immediate) {
                    if (shouldProjectPublication(completionKey)) {
                        status.value = "传输成功"
                        when (result) {
                            is PublishResult.Published -> {
                                currentFileName.value = result.displayName
                                lastReceivedFilePath.value = result.uri.toString()
                                lastReceivedDirPath.value = null
                                noticeText.value =
                                    "已保存到 Downloads/$PUBLIC_DOWNLOAD_SUBDIRECTORY；暂存副本为断点恢复保留"
                            }

                            is PublishResult.Retained -> {
                                lastReceivedFilePath.value = stagingFile.absolutePath
                                lastReceivedDirPath.value = stagingRoot.absolutePath
                                noticeText.value = result.reason
                            }
                        }
                    }
                }
            } finally {
                synchronized(publicationLock) { publishingPaths.remove(pathKey) }
            }
        }
    }

    fun applyEvent(eventJson: String) {
        val event = try {
            JSONObject(eventJson)
        } catch (error: Exception) {
            Log.e("TransferManager", "Invalid transfer event", error)
            return
        }
        val transferId = event.optString("transferId")
        if (transferId.isBlank()) return
        val direction = event.optString("direction", "send")
        val state = event.optString("state", "transferring")
        val fileName = event.optString("fileName")
        val durable = event.optLong("durableBytes")
        val inFlight = event.optLong("inFlightBytes")
        val total = event.optLong("totalBytes")
        val stageBytes = event.optLong("stageBytes")
        val stageTotalBytes = event.optLong("stageTotalBytes")
        val speed = event.optDouble("speedBytesPerSecond", 0.0)
        val message = event.optString("message").takeIf { it.isNotBlank() && it != "null" }
        val errorCode = event.optString("errorCode").takeIf { it.isNotBlank() && it != "null" }
        val diagnosticJson = event.optJSONObject("diagnostics")
        val diagnostics = TransferDiagnosticsUi(
            hashingMs = diagnosticJson?.optLong("hashingMs") ?: 0L,
            negotiatingMs = diagnosticJson?.optLong("negotiatingMs") ?: 0L,
            transferringMs = diagnosticJson?.optLong("transferringMs") ?: 0L,
            verifyingMs = diagnosticJson?.optLong("verifyingMs") ?: 0L,
            wireBytes = diagnosticJson?.optLong("wireBytes") ?: 0L,
            retransmittedBytes = diagnosticJson?.optLong("retransmittedBytes") ?: 0L,
            splitTrials = diagnosticJson?.optInt("splitTrials") ?: 0,
            rollbacks = diagnosticJson?.optInt("rollbacks") ?: 0,
            retries = diagnosticJson?.optInt("retries") ?: 0,
            timeoutsOrResets = diagnosticJson?.optInt("timeoutsOrResets") ?: 0,
        )
        val parsedSegments = parseTransferSegments(event, total)
        val segments = when {
            state == "completed" && total > 0L -> listOf(
                TransferSegmentUi(0L, total, total, running = false)
            )
            parsedSegments.isNotEmpty() -> parsedSegments
            else -> transfers[transferId]?.segments.orEmpty()
        }
        val updated = TransferUiState(
            transferId = transferId,
            direction = direction,
            state = state,
            fileName = fileName,
            durableBytes = durable,
            inFlightBytes = inFlight,
            totalBytes = total,
            activeWorkers = event.optInt("activeWorkers"),
            allocatedWorkers = event.optInt("allocatedWorkers"),
            targetWorkers = event.optInt("targetWorkers"),
            speedBytesPerSecond = speed,
            elapsedMs = event.optLong("elapsedMs"),
            stageBytes = stageBytes,
            stageTotalBytes = stageTotalBytes,
            errorCode = errorCode,
            message = message,
            segments = segments,
            updatedAtMs = System.currentTimeMillis(),
            diagnostics = diagnostics,
        )
        transfers[transferId] = updated
        activeTransferId.value = transferId

        currentFileName.value = fileName
        isTransferring.value = state !in setOf("completed", "failed", "cancelled")
        status.value = when (state) {
            "hashing" -> "正在校验文件..."
            "negotiating" -> "正在协商 V3..."
            "transferring" -> if (direction == "send") "正在发送..." else "正在接收..."
            "paused" -> "正在安全拆分..."
            "verifying" -> "正在校验完整文件..."
            "completed" -> "传输成功"
            "cancelled" -> "传输已取消"
            "failed" -> "失败: ${message ?: errorCode ?: "未知错误"}"
            else -> state
        }
        val projectedBytes = when {
            state == "hashing" && stageTotalBytes > 0L -> stageBytes
            state == "transferring" || state == "paused" -> saturatingAddBytes(durable, inFlight).coerceAtMost(total)
            else -> durable
        }
        val projectedTotal = if (state == "hashing" && stageTotalBytes > 0L) stageTotalBytes else total
        if (projectedTotal > 0L) {
            progress.floatValue = (projectedBytes.toFloat() / projectedTotal.toFloat()).coerceIn(0f, 1f)
            transferredBytes.value = projectedBytes
            totalBytes.value = projectedTotal
        }
        speedText.value = formatBytesPerSec(speed)
        if (state == "completed" || state == "cancelled") {
            cleanupOutgoingCache(transferId)
        }
        if (state in setOf("completed", "failed", "cancelled")) {
            Log.i(
                "TransferDiagnostics",
                "id=$transferId state=$state hashMs=${diagnostics.hashingMs} " +
                    "negotiateMs=${diagnostics.negotiatingMs} " +
                    "transferMs=${diagnostics.transferringMs} verifyMs=${diagnostics.verifyingMs} " +
                    "wire=${diagnostics.wireBytes} retransmit=${diagnostics.retransmittedBytes} " +
                    "split=${diagnostics.splitTrials} rollback=${diagnostics.rollbacks} " +
                    "retry=${diagnostics.retries} timeoutOrReset=${diagnostics.timeoutsOrResets}",
            )
        }
        if (state == "completed" && direction == "receive") {
            val completedName = message ?: fileName
            if (completedName.isNotBlank()) {
                publishCompletedIncoming(transferId, completedName)
            }
        }
    }

    fun cancel(transferId: String): Boolean = RustSDK.cancelTransfer(transferId)

    fun retry(transferId: String): Boolean {
        if (RustSDK.retryTransfer(transferId)) return true
        val context = appContext ?: return false
        val targetIp = outgoingTargets[transferId] ?: return false
        val uriString = outgoingUris[transferId]
        if (uriString != null) {
            val uri = Uri.parse(uriString)
            val fileName = outgoingFileNames[transferId].orEmpty()
            val fileSize = outgoingFileSizes[transferId] ?: -1L
            val newTransferId = sendSeekableUriDirect(context, targetIp, uri, fileName, fileSize)
                ?: return false
            registerOutgoingUriTransfer(
                context = context,
                transferId = newTransferId,
                uri = uri,
                fileName = fileName,
                fileSize = fileSize,
                targetIp = targetIp,
                replacesTransferId = transferId,
            )
            return true
        }
        val filePath = outgoingCachePaths[transferId] ?: return false
        if (!File(filePath).exists()) return false
        val newTransferId = RustSDK.sendFile(targetIp, filePath)
        registerOutgoingTransfer(
            context = context,
            transferId = newTransferId,
            filePath = filePath,
            targetIp = targetIp,
            replacesTransferId = transferId,
        )
        return true
    }

    private fun cleanupOutgoingCache(transferId: String) {
        val context = appContext ?: return
        val preferences = context.getSharedPreferences("localsend_outgoing", MODE_PRIVATE)
        val encoded = preferences.getString(transferId, null)
        val persistedMeta = runCatching { JSONObject(encoded ?: "") }.getOrNull()
        val persistedPath = persistedMeta?.optString("filePath")
        val persistedUri = persistedMeta?.optString("uri")?.takeIf(String::isNotBlank)
        val path = outgoingCachePaths.remove(transferId) ?: persistedPath
        outgoingTargets.remove(transferId)
        outgoingUris.remove(transferId)
        outgoingFileNames.remove(transferId)
        outgoingFileSizes.remove(transferId)
        preferences.edit().remove(transferId).apply()
        persistedUri?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { error ->
                Log.d("TransferManager", "持久 URI 权限无需释放或已失效: $uri", error)
            }
        }
        if (path == null) return

        cleanupScope.launch {
            runCatching {
                val outgoingRoot = File(context.cacheDir, "outgoing").canonicalFile
                val file = File(path).canonicalFile
                if (!isStrictFileDescendant(outgoingRoot, file)) {
                    Log.w("TransferManager", "Refusing to delete outgoing path outside cache: $file")
                    return@runCatching
                }
                if (!file.delete() && file.exists()) {
                    Log.w("TransferManager", "Unable to delete outgoing cache file: $file")
                    return@runCatching
                }
                val parent = file.parentFile
                if (parent != null && parent != outgoingRoot && !parent.delete() && parent.exists()) {
                    Log.w("TransferManager", "Unable to delete empty outgoing cache directory: $parent")
                }
            }.onFailure { error ->
                Log.w("TransferManager", "Unable to clean outgoing cache for $transferId", error)
            }
        }
    }

    fun markIncomingNameCollision(originalName: String, tempPath: String) {
        collisionOriginalName.value = originalName
        collisionTempPath.value = tempPath
    }

    fun finishTransfer(success: Boolean, msg: String, completedSend: Boolean? = null) {
        val wasSending = completedSend ?: isSending.value
        isTransferring.value = false
        progress.floatValue = if (success) 1.0f else 0f
        status.value = if (success) "传输成功" else "失败: $msg"
        if (success && !wasSending && msg.isNotBlank()) {
            currentFileName.value = msg
        }
        if (success && !wasSending && msg.isNotBlank()) {
            publishCompletedIncoming("legacy:$msg", msg)
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
    private const val KEY_LEGACY_SAVE_DIR = "legacy_save_dir"
    private const val KEY_STORAGE_VERSION = "storage_version"
    private const val STORAGE_VERSION = 2
    private const val KEY_DEVICE_ID = "device_id"

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    fun getDefaultSaveDir(context: Context): String {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { externalRoot ->
            val externalStaging = File(externalRoot, "incoming")
            if ((externalStaging.isDirectory || externalStaging.mkdirs()) && externalStaging.canWrite()) {
                return externalStaging.absolutePath
            }
        }
        val internalStaging = File(context.filesDir, "incoming")
        if (!internalStaging.isDirectory) {
            internalStaging.mkdirs()
        }
        return internalStaging.absolutePath
    }

    fun getSaveDir(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
        val safeStagingDir = getDefaultSaveDir(context)
        val saved = prefs.getString(KEY_SAVE_DIR, null)
        if (saved != safeStagingDir || prefs.getInt(KEY_STORAGE_VERSION, 0) < STORAGE_VERSION) {
            prefs.edit().apply {
                if (!saved.isNullOrBlank() && saved != safeStagingDir) {
                    putString(KEY_LEGACY_SAVE_DIR, saved)
                }
                putString(KEY_SAVE_DIR, safeStagingDir)
                putInt(KEY_STORAGE_VERSION, STORAGE_VERSION)
            }.apply()
        }
        return safeStagingDir
    }

    fun getLegacySaveDir(context: Context): String? {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_LEGACY_SAVE_DIR, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun clearSaveDir(context: Context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .remove(KEY_SAVE_DIR)
            .remove(KEY_LEGACY_SAVE_DIR)
            .remove(KEY_STORAGE_VERSION)
            .apply()
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
        TransferManager.restoreOutgoingTransfers(this)

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
        RustSDK.configureDeviceIdentity(AppSettings.getDeviceId(this), localName)

        setupMulticastLock()

        try {
            RustSDK.startDiscoverySafe(localName)
            Log.d("YukonTest", "Rust 发现服务启动 (Safe)")
        } catch (e: Exception) {
            Log.e("YukonTest", "Rust 发现服务启动失败 ${e.message}")
        }

        // Keep the Rust server and UI on one staging root for the lifetime of
        // this process. A transient external-volume change must not make the
        // UI look in a different directory while the existing server keeps
        // writing to its original root.
        val saveDirFromSettings = TransferManager.saveDir.value
            .takeIf(String::isNotBlank)
            ?: AppSettings.getSaveDir(this)
        val myDir = File(saveDirFromSettings)
        if (!myDir.exists()) {
            val created = myDir.mkdirs()
            if (!created) {
                Log.e("YukonTest", "无法创建文件夹: ${myDir.absolutePath}")
            }
        }

        val saveDir = myDir.absolutePath
        TransferManager.initializeIncomingStorage(this, myDir)
        try {
            RustSDK.startFileServerSafe(saveDir)
            Log.d("YukonTest", "Rust 文件接收服务启动 (Safe)，保存路径: $saveDir")
        } catch (e: Exception) {
            Log.e("YukonTest", "Rust 文件接收服务启动失败 ${e.message}")
        }

        RustSDK.discoverOnce(localName)
    }

    private fun setupMulticastLock() {
        if (multicastLock?.isHeld == true) return
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
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            pendingTargetIp?.let { targetIp ->
                appScope.launch {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            it,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }.onFailure { error ->
                        Log.w("YukonSend", "无法持久化文件读取权限，将仅支持本次发送", error)
                    }
                    val info = withContext(Dispatchers.IO) { inspectOpenableFile(context, it) }
                    if (info == null) {
                        TransferManager.startTransfer("无法读取的文件", true)
                        TransferManager.finishTransfer(false, "无法读取文件信息")
                        onSwitchToStatus()
                        return@launch
                    }
                    TransferManager.startTransfer(info.fileName, true, total = info.size.takeIf { size -> size >= 0L })
                    TransferManager.updatePreparation(info.fileName, "正在打开文件...", 0L, info.size)
                    onSwitchToStatus()

                    val directTransferId = withContext(Dispatchers.IO) {
                        sendSeekableUriDirect(context, targetIp, it, info.fileName, info.size)
                    }
                    if (directTransferId != null) {
                        Log.d("YukonSend", "使用可随机读取 FD 直接发送: $it -> $targetIp")
                        TransferManager.registerOutgoingUriTransfer(
                            context = context,
                            transferId = directTransferId,
                            uri = it,
                            fileName = info.fileName,
                            fileSize = info.size,
                            targetIp = targetIp,
                        )
                    } else {
                        TransferManager.updatePreparation(
                            info.fileName,
                            "源不支持随机读取，正在创建兼容缓存...",
                            0L,
                            info.size,
                        )
                        val realPath = withContext(Dispatchers.IO) {
                            copyUriToCache(context, it, info) { copied, total ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    TransferManager.updatePreparation(
                                        info.fileName,
                                        "源不支持随机读取，正在创建兼容缓存...",
                                        copied,
                                        total,
                                    )
                                }
                            }
                        }
                        if (realPath != null) {
                            Log.d("YukonSend", "兼容缓存完成，开始发送: $realPath -> $targetIp")
                            val transferId = withContext(Dispatchers.IO) {
                                RustSDK.sendFile(targetIp, realPath)
                            }
                            TransferManager.registerOutgoingTransfer(context, transferId, realPath, targetIp)
                        } else {
                            TransferManager.finishTransfer(false, "文件读取或缓存失败")
                        }
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
                                fileLauncher.launch(arrayOf("*/*"))
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
fun SegmentedTransferProgressIndicator(
    segments: List<TransferSegmentUi>,
    totalBytes: Long,
    fallbackProgress: Float,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val runningColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val downloadedColor = MaterialTheme.colorScheme.primary
    val hasSegments = totalBytes > 0L && segments.isNotEmpty()
    val total = totalBytes.coerceAtLeast(1L).toDouble()

    Canvas(modifier = modifier) {
        val radius = size.height / 2f
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = CornerRadius(radius, radius),
        )

        if (!hasSegments) {
            val fallbackWidth = size.width * fallbackProgress.coerceIn(0f, 1f)
            if (fallbackWidth > 0f) {
                drawRoundRect(
                    color = downloadedColor,
                    size = Size(fallbackWidth, size.height),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            return@Canvas
        }

        fun drawInterval(start: Long, end: Long, color: androidx.compose.ui.graphics.Color) {
            val left = ((start.toDouble() / total) * size.width).toFloat().coerceIn(0f, size.width)
            val right = ((end.toDouble() / total) * size.width).toFloat().coerceIn(left, size.width)
            if (right <= left) return
            drawRoundRect(
                color = color,
                topLeft = Offset(left, 0f),
                size = Size(right - left, size.height),
                cornerRadius = CornerRadius(radius, radius),
            )
        }

        segments.filter(TransferSegmentUi::running).forEach { segment ->
            drawInterval(segment.start, segment.end, runningColor)
        }
        segments.forEach { segment ->
            drawInterval(segment.start, segment.downloadedEnd, downloadedColor)
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
    val activeTransferId by TransferManager.activeTransferId
    val activeTransfer = activeTransferId?.let(TransferManager.transfers::get)

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

        SegmentedTransferProgressIndicator(
            segments = activeTransfer?.segments.orEmpty(),
            totalBytes = activeTransfer?.totalBytes ?: 0L,
            fallbackProgress = progress,
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

        if (isTransferring && activeTransfer != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "并发: ${activeTransfer.activeWorkers}/${activeTransfer.allocatedWorkers} · 目标 ${activeTransfer.targetWorkers}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
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
    val stagingDir = remember {
        TransferManager.saveDir.value.takeIf { it.isNotBlank() }
            ?: AppSettings.getSaveDir(context)
    }
    val legacyDir = remember { AppSettings.getLegacySaveDir(context) }

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
            Text("接收与导出", style = MaterialTheme.typography.titleMedium)
            Text(
                "接收暂存位置（应用专属）",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                stagingDir,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                "Rust 只写入上面的应用专属目录，不会直接写公共 Downloads。",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()
            Text("公开下载位置", style = MaterialTheme.typography.labelLarge)
            Text(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "接收完成后自动导出到 Downloads/$PUBLIC_DOWNLOAD_SUBDIRECTORY；应用通过 content URI 打开文件，并保留应用专属副本用于 Done 丢包与断点恢复。"
                } else {
                    "Android 10 以下不自动导出；完成后可从应用专属暂存位置打开文件。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (legacyDir != null) {
                HorizontalDivider()
                Text("旧保存路径已停用", style = MaterialTheme.typography.labelLarge)
                Text(
                    "$legacyDir\n为适配 scoped storage，该路径不再交给 Rust；其中已有文件不会被删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private fun openFile(context: Context, fileLocation: String?) {
    if (fileLocation.isNullOrBlank()) return
    val parsed = Uri.parse(fileLocation)
    val isContentUri = parsed.scheme.equals("content", ignoreCase = true)
    val uri: Uri
    val mime: String

    if (isContentUri) {
        uri = parsed
        mime = context.contentResolver.getType(uri)
            ?: mimeTypeForName(uri.lastPathSegment.orEmpty())
    } else {
        val file = if (parsed.scheme.equals("file", ignoreCase = true)) {
            parsed.path?.let(::File)
        } else {
            File(fileLocation)
        }
        if (file == null || !file.isFile) {
            Toast.makeText(context, "文件不存在: $fileLocation", Toast.LENGTH_LONG).show()
            return
        }
        uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (error: Exception) {
            Log.e("MainActivity", "Unable to share received file", error)
            Toast.makeText(context, "无法安全打开该文件", Toast.LENGTH_LONG).show()
            return
        }
        mime = mimeTypeForName(file.name)
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
    val parsed = Uri.parse(dirPath)
    if (parsed.scheme.equals("content", ignoreCase = true)) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = parsed
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "系统未找到可打开文件夹的应用", Toast.LENGTH_LONG).show()
        }
        return
    }
    val dir = File(dirPath)
    if (!dir.exists() || !dir.isDirectory) {
        Toast.makeText(context, "文件夹不存在: $dirPath", Toast.LENGTH_LONG).show()
        return
    }

    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dir)
    } catch (error: Exception) {
        Log.e("MainActivity", "Unable to share received directory", error)
        Toast.makeText(context, "无法安全打开该文件夹", Toast.LENGTH_LONG).show()
        return
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

data class OpenableFileInfo(
    val fileName: String,
    val size: Long,
)

fun inspectOpenableFile(context: Context, uri: Uri): OpenableFileInfo? = runCatching {
    var fileName = "temp_file"
    var size = -1L
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                fileName = File(cursor.getString(nameIndex)).name.ifBlank { "temp_file" }
            }
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    if (size < 0L) {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            size = descriptor.statSize
        }
    }
    OpenableFileInfo(fileName, size)
}.onFailure { error ->
    Log.e("YukonSend", "读取文档信息失败: $uri", error)
}.getOrNull()

fun sendSeekableUriDirect(
    context: Context,
    targetIp: String,
    uri: Uri,
    fileName: String,
    declaredSize: Long,
): String? {
    var rawFd: Int? = null
    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            val size = descriptor.statSize.takeIf { it >= 0L } ?: declaredSize
            if (size < 0L) return null
            rawFd = descriptor.detachFd()
            val sourceKey = "${uri}|$size"
            val transferId = RustSDK.sendFileDescriptor(targetIp, rawFd!!, fileName, size, sourceKey)
            rawFd = null // Rust owns the detached descriptor after JNI returns.
            transferId.takeIf(String::isNotBlank)
        }
    } catch (error: Exception) {
        rawFd?.let { detached ->
            runCatching { ParcelFileDescriptor.adoptFd(detached).close() }
        }
        Log.i("YukonSend", "文档不支持可随机读取 FD，切换到缓存兼容模式: $uri", error)
        null
    }
}

fun copyUriToCache(
    context: Context,
    uri: Uri,
    info: OpenableFileInfo = inspectOpenableFile(context, uri) ?: OpenableFileInfo("temp_file", -1L),
    onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
): String? {
    try {
        val contentResolver = context.contentResolver
        val outgoingDir = File(context.cacheDir, "outgoing/${UUID.randomUUID()}")
        if (!outgoingDir.mkdirs() && !outgoingDir.isDirectory) return null
        val cacheFile = File(outgoingDir, info.fileName)

        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var copied = 0L
                var lastUpdate = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    copied += count
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 200L || (info.size >= 0L && copied >= info.size)) {
                        onProgress(copied, info.size)
                        lastUpdate = now
                    }
                }
                onProgress(copied, info.size.takeIf { it >= 0L } ?: copied)
            }
        } ?: return null

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
