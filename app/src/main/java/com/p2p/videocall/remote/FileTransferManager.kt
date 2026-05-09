package com.p2p.videocall.remote

import com.p2p.videocall.p2p.P2PConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.webrtc.DataChannel
import java.io.File
import java.nio.ByteBuffer

class FileTransferManager(private val p2pConnectionManager: P2PConnectionManager) {

    suspend fun sendFile(file: File, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(65536)
        val fileLength = file.length()
        var bytesSent = 0L

        file.inputStream().use { stream ->
            var n: Int
            while (stream.read(buffer).also { n = it } != -1) {
                p2pConnectionManager.dataChannels[P2PConnectionManager.CHANNEL_FILE]?.send(
                    DataChannel.Buffer(ByteBuffer.wrap(buffer, 0, n), true)
                )
                bytesSent += n
                val progress = ((bytesSent.toDouble() / fileLength) * 100).toInt()
                withContext(Dispatchers.Main) {
                    onProgress(progress)
                }
            }
        }
    }
}
