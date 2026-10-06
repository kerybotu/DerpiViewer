package com.kerybotu.derpibooru.mirror.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class AppUpdateInfo(val version: String, val urls: List<String>, val message: List<String>)

sealed class UpdateCheckResult {
    data class Available(val info: AppUpdateInfo) : UpdateCheckResult()
    object NoUpdate : UpdateCheckResult()
    data class Failed(val reason: String) : UpdateCheckResult()
}

enum class UpdateFrequency(val label: String, val intervalMs: Long) {
    NEVER("永不", Long.MAX_VALUE), HOURLY("每小时", 3_600_000L), DAILY("每天（推荐）", 86_400_000L),
    WEEKLY("每周", 604_800_000L), MONTHLY("每月", 2_592_000_000L)
}

object AppUpdateManager {
    const val MANIFEST_URL = "https://kerybotu.github.io/appuploads/sources.json"
    private const val PREFS = "app_update"
    private const val LAST_CHECK = "last_check"
    private const val SKIPPED = "skipped_version"
    private const val FREQUENCY = "frequency"

    fun frequency(context: Context) = runCatching { UpdateFrequency.valueOf(context.getSharedPreferences(PREFS, 0).getString(FREQUENCY, UpdateFrequency.DAILY.name)!!) }.getOrDefault(UpdateFrequency.DAILY)
    fun setFrequency(context: Context, value: UpdateFrequency) = context.getSharedPreferences(PREFS, 0).edit().putString(FREQUENCY, value.name).apply()
    fun shouldCheck(context: Context) = frequency(context) != UpdateFrequency.NEVER && System.currentTimeMillis() - context.getSharedPreferences(PREFS, 0).getLong(LAST_CHECK, 0) >= frequency(context).intervalMs
    fun markChecked(context: Context) = context.getSharedPreferences(PREFS, 0).edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
    fun remindNextLaunch(context: Context) = context.getSharedPreferences(PREFS, 0).edit().putLong(LAST_CHECK, 0L).apply()
    fun skip(context: Context, version: String) = context.getSharedPreferences(PREFS, 0).edit().putString(SKIPPED, version).apply()
    fun isSkipped(context: Context, version: String) = context.getSharedPreferences(PREFS, 0).getString(SKIPPED, null) == version

    suspend fun check(context: Context, force: Boolean = false): AppUpdateInfo? =
        when (val result = checkDetailed(context, force)) {
            is UpdateCheckResult.Available -> result.info
            UpdateCheckResult.NoUpdate, is UpdateCheckResult.Failed -> null
        }

    suspend fun checkDetailed(context: Context, force: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!force && !shouldCheck(context)) return@withContext UpdateCheckResult.NoUpdate
        markChecked(context)

        val body = try {
            fetchText(MANIFEST_URL)
        } catch (e: Exception) {
            return@withContext UpdateCheckResult.Failed(formatCheckError(e))
        }
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return@withContext UpdateCheckResult.Failed("更新清单解析失败：服务器返回的内容不是有效 JSON")
        }

        val version = root.optString("dvversion").trim()
        if (version.isBlank()) {
            return@withContext UpdateCheckResult.Failed("更新清单缺少有效的 dvversion 字段")
        }
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty().trim()
        } catch (e: Exception) {
            return@withContext UpdateCheckResult.Failed("无法读取当前应用版本：${e.message ?: "PackageManager 异常"}")
        }
        val comparison = compareVersions(version, currentVersion)
            ?: return@withContext UpdateCheckResult.Failed("版本号格式无效：远程版本 $version，当前版本 ${currentVersion.ifBlank { "未知" }}（应为 a.b.c）")
        if (comparison <= 0 || isSkipped(context, version)) return@withContext UpdateCheckResult.NoUpdate

        val urls = root.optJSONArray("dvapkurl")?.strings().orEmpty()
        if (urls.isEmpty()) {
            return@withContext UpdateCheckResult.Failed("检测到新版本 $version，但更新清单没有有效的 APK 下载地址")
        }
        UpdateCheckResult.Available(AppUpdateInfo(version, urls, root.optJSONArray("dvmessage")?.strings().orEmpty()))
    }

    suspend fun download(context: Context, info: AppUpdateInfo, onProgress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "DerpiViewer_${info.version}.apk")
        var failure: Exception? = null
        info.urls.distinct().forEachIndexed { index, source ->
            onProgress(if (index == 0) "连接主下载源…" else "主下载源失败，切换备用下载源…")
            try { downloadFrom(source, target, onProgress); return@withContext target } catch (e: Exception) {
                failure = e; target.delete(); onProgress("下载源${index + 1}失败：${e.message ?: "网络错误"}")
            }
        }
        throw failure ?: IllegalStateException("没有可用的 APK 下载地址")
    }

    private fun downloadFrom(source: String, target: File, progress: (String) -> Unit) {
        val connection = open(source)
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val length = connection.contentLengthLong
        val supportsRanges = connection.getHeaderField("Accept-Ranges").equals("bytes", true)
        val startTime = System.currentTimeMillis(); var bytes = 0L; var switched = false
        connection.inputStream.use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                output.write(buffer, 0, n); bytes += n
                val elapsed = System.currentTimeMillis() - startTime
                progress(progressText(bytes, length, bytes * 1000.0 / elapsed.coerceAtLeast(1), 1))
                if (!switched && elapsed >= 2_000 && bytes * 1000.0 / elapsed < 1_048_576 && supportsRanges && length > 0) { switched = true; break }
            }
        } }
        connection.disconnect()
        if (switched) { progress("2 秒平均速度低于 1 MB/s，服务器支持分段下载，切换为 4 线程…"); downloadRanges(source, target, length, progress) }
        if (length > 0 && target.length() != length) error("APK 下载不完整")
        FileInputStream(target).use { input ->
            val signature = ByteArray(2)
            if (input.read(signature) != 2 || signature[0] != 'P'.code.toByte() || signature[1] != 'K'.code.toByte()) error("下载内容不是有效 APK")
        }
    }

    private fun downloadRanges(source: String, target: File, length: Long, progress: (String) -> Unit) {
        target.delete(); RandomAccessFile(target, "rw").use { it.setLength(length) }
        val done = AtomicLong(0); val began = System.currentTimeMillis(); val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 4).map { part -> executor.submit {
                val start = length * part / 4; val end = length * (part + 1) / 4 - 1; val c = open(source)
                c.setRequestProperty("Range", "bytes=$start-$end")
                try {
                    if (c.responseCode != HttpURLConnection.HTTP_PARTIAL) error("服务器拒绝分段下载")
                    RandomAccessFile(target, "rw").use { out -> out.seek(start); c.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); val total = done.addAndGet(n.toLong()); progress(progressText(total, length, total * 1000.0 / (System.currentTimeMillis() - began).coerceAtLeast(1), 4)) }
                    } }
                } finally { c.disconnect() }
            } }
            tasks.forEach { it.get() }
        } finally { executor.shutdownNow() }
    }

    fun canInstallPackages(context: Context) = android.os.Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    fun openInstallPermissionSettings(context: Context) { if (android.os.Build.VERSION.SDK_INT >= 26) context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION) })
    }
    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 20_000; requestMethod = "GET" }
    private fun fetchText(url: String): String {
        val connection = try {
            open(url)
        } catch (e: Exception) {
            throw IOException("无法连接更新服务器", e)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("更新服务器返回 HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("读取更新清单失败", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun formatCheckError(error: Exception): String {
        val detail = error.message?.takeIf { it.isNotBlank() }
        return when {
            detail?.contains("HTTP") == true -> "更新检查失败：$detail"
            detail != null -> "更新检查失败：$detail"
            else -> "更新检查失败：网络请求异常，请检查网络连接"
        }
    }
    private fun JSONArray.strings() = List(length()) { optString(it) }.filter { it.isNotBlank() }
    private fun progressText(done: Long, total: Long, bps: Double, threads: Int): String { val percent = if (total > 0) "%.1f%%".format(done * 100.0 / total) else "已下载 ${done / 1024} KB"; val speed = if (bps >= 1_048_576) "%.2f MB/s".format(bps / 1_048_576) else "%.0f KB/s".format(bps / 1024); return "下载中（${threads}线程）：$percent · $speed" }
    /**
     * Compares the API version with the installed APK version.
     * Both values are required to be exactly three decimal components (a.b.c).
     * Components are compared numerically from major to patch level.
     */
    private fun compareVersions(remote: String, current: String): Int? {
        fun parse(value: String): IntArray? {
            val parts = value.trim().split('.')
            if (parts.size != 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
            return runCatching { IntArray(3) { index -> parts[index].toInt() } }.getOrNull()
        }

        val remoteParts = parse(remote) ?: return null
        val currentParts = parse(current) ?: return null
        for (index in 0..2) {
            val difference = remoteParts[index].compareTo(currentParts[index])
            if (difference != 0) return difference
        }
        return 0
    }
}
