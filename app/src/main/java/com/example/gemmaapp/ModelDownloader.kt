package com.example.gemmaapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object ModelDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.MINUTES) // Large models take time
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun downloadFile(
        url: String,
        destination: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to download file: ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body"))
            val contentLength = body.contentLength()
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(destination)

            val buffer = ByteArray(8 * 1024)
            var bytesCopied: Long = 0
            var bytesRead: Int
            var lastProgress = 0

            inputStream.use { input ->
                outputStream.use { output ->
                    while (input.read(buffer).also { bytesRead = it } >= 0) {
                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead

                        if (contentLength > 0) {
                            val progress = ((bytesCopied * 100) / contentLength).toInt()
                            if (progress > lastProgress) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) {
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
            }

            Result.success(destination)
        } catch (e: Exception) {
            if (destination.exists()) destination.delete() // Clean up partial file
            Result.failure(e)
        }
    }
}
