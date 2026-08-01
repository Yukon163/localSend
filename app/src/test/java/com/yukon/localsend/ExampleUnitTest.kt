package com.yukon.localsend

import org.junit.Test

import org.junit.Assert.*
import java.io.File
import java.util.UUID

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun completedStagingFile_isAlwaysResolvedInsideStagingRoot() {
        val staging = File(System.getProperty("java.io.tmpdir"), "localsend-${UUID.randomUUID()}")
        assertTrue(staging.mkdirs())
        try {
            val resolved = resolveCompletedStagingFile(staging, "../../received.txt")
            assertEquals(staging.canonicalFile, resolved?.parentFile)
            assertEquals("received.txt", resolved?.name)
        } finally {
            staging.delete()
        }
    }

    @Test
    fun completedStagingFile_rejectsDirectoryMarkers() {
        val staging = File(System.getProperty("java.io.tmpdir"), "localsend-${UUID.randomUUID()}")
        assertNull(resolveCompletedStagingFile(staging, ".."))
        assertNull(resolveCompletedStagingFile(staging, "."))
        assertNull(resolveCompletedStagingFile(staging, ""))
    }

    @Test
    fun transferSegments_useRealByteOffsetsAndClampDownloadedEnd() {
        val decoded = listOf(
            TransferSegmentUi(500, 1000, 1200, running = true),
            TransferSegmentUi(0, 500, 250, running = false),
            TransferSegmentUi(900, 900, 900, running = true),
        )

        assertEquals(
            listOf(
                TransferSegmentUi(0, 500, 250, running = false),
                TransferSegmentUi(500, 1000, 1000, running = true),
            ),
            normalizeTransferSegments(decoded, totalBytes = 1000),
        )
    }

    @Test
    fun transferSegments_ignoreMissingOrInvalidRanges() {
        assertTrue(normalizeTransferSegments(emptyList(), totalBytes = 1000).isEmpty())
        assertTrue(
            normalizeTransferSegments(
                listOf(TransferSegmentUi(-1, 10, -1, running = true)),
                totalBytes = 1000,
            ).isEmpty()
        )
    }

    @Test
    fun recentTransfers_areNewestFirstAndLimited() {
        val states = listOf(
            transferState("middle", updatedAtMs = 20),
            transferState("old", updatedAtMs = 10),
            transferState("new", updatedAtMs = 30),
        )

        assertEquals(
            listOf("new", "middle"),
            recentTransferStates(states, limit = 2).map(TransferUiState::transferId),
        )
    }

    @Test
    fun outgoingCleanup_requiresAStrictCacheDescendant() {
        val root = File(System.getProperty("java.io.tmpdir"), "localsend-outgoing")
        assertTrue(isStrictFileDescendant(root, File(root, "transfer/file.bin")))
        assertFalse(
            isStrictFileDescendant(
                root,
                File(root.parentFile, "${root.name}-lookalike/file.bin"),
            )
        )
        assertFalse(isStrictFileDescendant(root, root))
    }

    private fun transferState(transferId: String, updatedAtMs: Long) = TransferUiState(
        transferId = transferId,
        direction = "send",
        state = "completed",
        fileName = "$transferId.bin",
        durableBytes = 1,
        inFlightBytes = 0,
        totalBytes = 1,
        activeWorkers = 0,
        targetWorkers = 0,
        speedBytesPerSecond = 0.0,
        elapsedMs = 1,
        updatedAtMs = updatedAtMs,
    )
}
