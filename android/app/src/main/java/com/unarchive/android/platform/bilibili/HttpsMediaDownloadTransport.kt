package com.unarchive.android.platform.bilibili

import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class HttpsMediaDownloadTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : MediaDownloadTransport {
    override suspend fun download(request: MediaDownloadRequest): Long = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("Bilibili audio must use HTTPS")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            request.headers.forEach(connection::setRequestProperty)
            val status = connection.responseCode
            require(status == HttpsURLConnection.HTTP_OK) {
                "Bilibili audio server returned HTTP $status"
            }
            val contentLength = connection.contentLengthLong.takeIf { it >= 0 }
            require(contentLength == null || contentLength <= request.maximumBytes) {
                "Bilibili audio exceeds the download size limit"
            }

            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(request.destination, false).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= request.maximumBytes) {
                            "Bilibili audio exceeds the download size limit"
                        }
                        output.write(buffer, 0, count)
                        request.progressListener.onProgress(downloaded, contentLength)
                    }
                    output.fd.sync()
                }
            }
            downloaded
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
