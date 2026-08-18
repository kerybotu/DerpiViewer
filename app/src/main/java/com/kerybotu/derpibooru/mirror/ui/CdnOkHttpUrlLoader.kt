package com.kerybotu.derpibooru.mirror.ui

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/** Bridges Glide's image pipeline to NetworkManager's proxy-aware OkHttp client. */
class CdnOkHttpUrlLoader : ModelLoader<GlideUrl, InputStream> {
    override fun buildLoadData(
        model: GlideUrl,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream> = ModelLoader.LoadData(
        ObjectKey(model),
        Fetcher(model)
    )

    override fun handles(model: GlideUrl): Boolean = true

    class Factory : ModelLoaderFactory<GlideUrl, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<GlideUrl, InputStream> =
            CdnOkHttpUrlLoader()

        override fun teardown() = Unit
    }

    private class Fetcher(private val url: GlideUrl) : DataFetcher<InputStream> {
        private var call: Call? = null
        private var response: Response? = null
        private var stream: InputStream? = null

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            val client = NetworkManager.imageHttpClient()
            if (client == null) {
                callback.onLoadFailed(IOException("网络尚未准备就绪"))
                return
            }
            try {
                val request = Request.Builder().url(url.toStringUrl()).build()
                call = client.newCall(request)
                response = call!!.execute()
                val body = response?.body
                if (response?.isSuccessful == true && body != null) {
                    stream = body.byteStream()
                    callback.onDataReady(stream)
                } else {
                    callback.onLoadFailed(IOException("图片请求失败: HTTP ${response?.code}"))
                }
            } catch (error: Exception) {
                callback.onLoadFailed(error)
            }
        }

        override fun cleanup() {
            try { stream?.close() } catch (_: IOException) { }
            response?.close()
            stream = null
            response = null
        }

        override fun cancel() {
            call?.cancel()
        }

        override fun getDataClass(): Class<InputStream> = InputStream::class.java

        override fun getDataSource(): DataSource = DataSource.REMOTE
    }
}
