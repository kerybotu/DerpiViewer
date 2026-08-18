package com.kerybotu.derpibooru.mirror.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }

data class DownloadTask(
    val taskId: Long,
    val imageId: Long,
    val sourceUrl: String,
    val fileName: String,
    val thumbnailUrl: String?,
    val status: DownloadStatus,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val outputUri: String? = null
)

data class DownloadRequestItem(
    val imageId: Long,
    val url: String,
    val fileName: String,
    val thumbnailUrl: String?
)

/** Persistent, bounded download queue shared by all image screens. */
class DownloadQueueManager private constructor(private val context: Context) {
    companion object {
        private const val PREFS = "download_queue"
        private const val KEY_TASKS = "tasks"
        private const val MAX_CONCURRENT = 2
        @Volatile private var instance: DownloadQueueManager? = null
        fun get(context: Context): DownloadQueueManager = instance ?: synchronized(this) {
            instance ?: DownloadQueueManager(context.applicationContext).also { instance = it }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val active = ConcurrentHashMap<Long, Job>()
    private val tasks = MutableStateFlow(loadTasks())

    val state: StateFlow<List<DownloadTask>> = tasks

    init { pump() }

    fun enqueue(items: List<DownloadRequestItem>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            val next = tasks.value.toMutableList()
            var id = (next.maxOfOrNull { it.taskId } ?: 0L) + 1L
            items.forEach { item ->
                next += DownloadTask(id++, item.imageId, item.url, item.fileName, item.thumbnailUrl, DownloadStatus.QUEUED)
            }
            publish(next)
        }
        pump()
    }

    fun enqueueImages(items: List<Image>) {
        enqueue(items.mapNotNull { image ->
            val url = image.fullUrl ?: image.thumbnailUrl ?: return@mapNotNull null
            DownloadRequestItem(image.id.toLong(), url, fileNameFor(image.id, url), image.thumbnailUrl)
        })
    }

    fun retry(taskId: Long) {
        synchronized(lock) { update(taskId) { it.copy(status = DownloadStatus.QUEUED, errorMessage = null, downloadedBytes = 0, totalBytes = 0) } }
        pump()
    }

    fun cancel(taskId: Long) {
        active.remove(taskId)?.cancel()
        synchronized(lock) { publish(tasks.value.filterNot { it.taskId == taskId }) }
    }

    fun delete(taskIds: Set<Long>, deleteFiles: Boolean) {
        if (deleteFiles) {
            tasks.value.filter { it.taskId in taskIds }.mapNotNull { it.outputUri }.forEach { uri ->
                runCatching {
                    val parsed = android.net.Uri.parse(uri)
                    if (parsed.scheme == "file") java.io.File(parsed.path.orEmpty()).delete()
                    else context.contentResolver.delete(parsed, null, null)
                }
            }
        }
        taskIds.forEach { active.remove(it)?.cancel() }
        synchronized(lock) { publish(tasks.value.filterNot { it.taskId in taskIds }) }
    }

    fun clearCompleted() = synchronized(lock) { publish(tasks.value.filterNot { it.status == DownloadStatus.COMPLETED }) }

    private fun pump() {
        synchronized(lock) {
            while (active.size < MAX_CONCURRENT) {
                val task = tasks.value.firstOrNull { it.status == DownloadStatus.QUEUED && !active.containsKey(it.taskId) } ?: break
                update(task.taskId) { it.copy(status = DownloadStatus.DOWNLOADING, errorMessage = null) }
                active[task.taskId] = scope.launch { execute(task.taskId) }
            }
        }
    }

    private suspend fun execute(taskId: Long) {
        try {
            val task = tasks.value.firstOrNull { it.taskId == taskId } ?: return
            val client = NetworkManager.imageHttpClient() ?: OkHttpClient()
            client.newCall(Request.Builder().url(task.sourceUrl).build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("空响应")
                val total = body.contentLength().coerceAtLeast(0L)
                val output = createOutput(task.fileName)
                var completed = 0L
                body.byteStream().use { input ->
                    output.stream.use { stream ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            stream.write(buffer, 0, count)
                            completed += count
                            update(taskId) { it.copy(downloadedBytes = completed, totalBytes = total) }
                        }
                    }
                }
                output.finish()
                synchronized(lock) { update(taskId) { it.copy(status = DownloadStatus.COMPLETED, downloadedBytes = completed, totalBytes = total, outputUri = output.uri) } }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Cancellation removes the task or leaves it queued for a later retry.
        } catch (error: Exception) {
            synchronized(lock) { update(taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = error.message ?: "下载失败") } }
        } finally {
            active.remove(taskId)
            pump()
        }
    }

    private data class Output(val stream: java.io.OutputStream, val uri: String?, val finish: () -> Unit)

    private fun createOutput(name: String): Output {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.MIME_TYPE, mimeFor(safe))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DerpiViewer")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建下载文件")
            val stream = context.contentResolver.openOutputStream(uri) ?: error("无法写入下载文件")
            return Output(stream, uri.toString(), {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            })
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DerpiViewer").apply { mkdirs() }
        val file = File(dir, safe)
        return Output(FileOutputStream(file), file.toURI().toString(), {})
    }

    private fun update(id: Long, transform: (DownloadTask) -> DownloadTask) {
        publish(tasks.value.map { if (it.taskId == id) transform(it) else it })
    }

    private fun publish(value: List<DownloadTask>) {
        tasks.value = value.sortedByDescending { it.createdAt }
        prefs.edit().putString(KEY_TASKS, JSONArray(tasks.value.map(::toJson)).toString()).apply()
    }

    private fun loadTasks(): List<DownloadTask> = runCatching {
        val array = JSONArray(prefs.getString(KEY_TASKS, "[]"))
        List(array.length()) { fromJson(array.getJSONObject(it)).let { task ->
            // A process death cannot leave a task permanently marked as active.
            if (task.status == DownloadStatus.DOWNLOADING) task.copy(status = DownloadStatus.QUEUED) else task
        } }
    }.getOrDefault(emptyList())

    private fun toJson(t: DownloadTask) = JSONObject().apply {
        put("taskId", t.taskId); put("imageId", t.imageId); put("sourceUrl", t.sourceUrl); put("fileName", t.fileName)
        put("thumbnailUrl", t.thumbnailUrl); put("status", t.status.name); put("totalBytes", t.totalBytes); put("downloadedBytes", t.downloadedBytes)
        put("errorMessage", t.errorMessage); put("createdAt", t.createdAt); put("outputUri", t.outputUri)
    }

    private fun fromJson(o: JSONObject) = DownloadTask(o.optLong("taskId"), o.optLong("imageId"), o.optString("sourceUrl"), o.optString("fileName"), o.optString("thumbnailUrl").takeIf { it.isNotBlank() }, runCatching { DownloadStatus.valueOf(o.optString("status")) }.getOrDefault(DownloadStatus.FAILED), o.optLong("totalBytes"), o.optLong("downloadedBytes"), o.optString("errorMessage").takeIf { it.isNotBlank() }, o.optLong("createdAt"), o.optString("outputUri").takeIf { it.isNotBlank() })

    private fun fileNameFor(id: Int, url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "jpg").take(5).ifBlank { "jpg" }
        return "derpi_${id}.$ext"
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "gif" -> "image/gif"; "webm" -> "video/webm"; "mp4" -> "video/mp4"; else -> "image/jpeg"
    }
}
