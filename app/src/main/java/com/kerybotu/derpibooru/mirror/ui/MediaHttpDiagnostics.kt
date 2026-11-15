package com.kerybotu.derpibooru.mirror.ui

import android.util.Log
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Resolve the current media route for every request, including seeks. */
internal object MediaHttpDiagnostics {
    private var source: OkHttpClient? = null
    private var diagnosticClient: OkHttpClient? = null

    fun calls(purpose: String): Call.Factory = Call.Factory { request ->
        client().newCall(request.newBuilder().tag(Purpose::class.java, Purpose(purpose)).build())
    }

    @Synchronized private fun client(): OkHttpClient {
        val current = NetworkManager.videoHttpClient() ?: error("媒体网络尚未就绪")
        if (source !== current) {
            source = current
            diagnosticClient = current.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                val purpose = request.tag(Purpose::class.java)?.name.orEmpty()
                val version = NetworkManager.configurationVersion.value
                try {
                    val response = chain.proceed(request)
                    val redirects = generateSequence(response.priorResponse) { it.priorResponse }.count()
                    val node = NetworkManager.currentPreferredIps()[response.request.url.host] ?: "system-dns"
                    Log.i("MediaHttp", "phase=response source=okhttp purpose=$purpose config=$version " +
                        "route=${if (NetworkManager.localProxyPort() == null) "direct" else "optimized"} " +
                        "url=${safeUrl(request.url)} status=${response.code} " +
                        "type=${response.header("Content-Type").orEmpty()} " +
                        "length=${response.header("Content-Length").orEmpty()} " +
                        "contentRange=${response.header("Content-Range").orEmpty()} " +
                        "acceptRanges=${response.header("Accept-Ranges").orEmpty()} " +
                        "requestRange=${request.header("Range").orEmpty()} redirects=$redirects finalHost=${response.request.url.host} node=$node")
                    response
                } catch (error: java.io.IOException) {
                    val kind = when (error) {
                        is java.net.UnknownHostException -> "dns"
                        is javax.net.ssl.SSLException -> "tls"
                        is java.net.SocketTimeoutException -> "timeout"
                        else -> "connection"
                    }
                    Log.w("MediaHttp", "phase=transport source=okhttp purpose=$purpose config=$version " +
                        "url=${safeUrl(request.url)} category=$kind cause=${error.javaClass.simpleName}")
                    throw error
                }
            }.build()
        }
        return checkNotNull(diagnosticClient)
    }

    internal fun safeUrl(url: HttpUrl): String = "${url.scheme}://${url.host}${url.encodedPath}"
    private data class Purpose(val name: String)
}
