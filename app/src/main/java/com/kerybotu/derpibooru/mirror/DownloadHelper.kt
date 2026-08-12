package com.kerybotu.derpibooru.mirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

object DownloadHelper {

    private const val CHANNEL_ID = "download_channel"
    private const val NOTIFY_ID = 1001

    // 独立的协程作用域，专门给下载任务用，避免和 Activity 生命周期绑死
    private val downloadScope = CoroutineScope(Dispatchers.IO)

    fun download(
        context: Context,
        proxyPort: Int,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val appContext = context.applicationContext

        Toast.makeText(appContext, "开始下载：$fileName", Toast.LENGTH_SHORT).show()
        ensureChannel(appContext)

        downloadScope.launch {
            try {
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
                val conn = URL(url).openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", userAgent ?: "Mozilla/5.0")
                conn.connect()

                if (conn.responseCode !in 200..299) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "下载失败：HTTP ${conn.responseCode}", Toast.LENGTH_SHORT).show()
                    }
                    conn.disconnect()
                    return@launch
                }

                val resolvedMime = mimeType?.takeIf { it.isNotBlank() }
                    ?: MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
                    ?: "application/octet-stream"

                val saved = saveToDownloads(appContext, fileName, resolvedMime, conn.inputStream)
                conn.disconnect()

                withContext(Dispatchers.Main) {
                    if (saved) {
                        notifyDone(appContext, fileName)
                        Toast.makeText(appContext, "下载完成：$fileName", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(appContext, "保存文件失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "下载出错：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        input: java.io.InputStream
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = java.io.File(dir, fileName)
                file.outputStream().use { out -> input.copyTo(out) }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { input.close() } catch (_: Exception) {}
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "下载完成通知", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
    }

    private fun notifyDone(context: Context, fileName: String) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("下载完成")
                .setContentText(fileName)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
        } catch (e: SecurityException) {
            // 未授予通知权限，静默忽略，不影响下载本身
        }
    }
}