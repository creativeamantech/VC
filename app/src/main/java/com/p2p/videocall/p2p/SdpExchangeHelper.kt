package com.p2p.videocall.p2p

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object SdpExchangeHelper {

    fun encodeSdpForSharing(sdp: String): String {
        val compressed = ByteArrayOutputStream().also { baos ->
            GZIPOutputStream(baos).use { it.write(sdp.toByteArray()) }
        }.toByteArray()
        return Base64.encodeToString(compressed, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    fun decodeSdpFromSharing(encoded: String): String {
        val compressed = Base64.decode(encoded, Base64.URL_SAFE)
        return GZIPInputStream(compressed.inputStream()).bufferedReader().readText()
    }
}
