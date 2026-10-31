package com.kerybotu.derpibooru.mirror

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class LocalProxyServer(
    preferredIps: Map<String, String>
) {
    @Volatile
    private var preferredIps: Map<String, String> = preferredIps.mapKeys { it.key.lowercase() }
        private set

    fun updatePreferredIps(newIps: Map<String, String>) {
        preferredIps = newIps.mapKeys { it.key.lowercase() }
    }

    private var serverSocket: ServerSocket? = null
    // Each accepted proxy connection fans out to two relay threads; keep the
    // connection count bounded. Do not queue CONNECT behind long-lived relays.
    private val executor = ThreadPoolExecutor(
        0, 32, 60L, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadPoolExecutor.AbortPolicy()
    )
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var running = false

    var port: Int = 0
        private set

    fun start() {
        serverSocket = ServerSocket(0, 50, InetSocketAddress("127.0.0.1", 0).address)
        port = serverSocket!!.localPort
        running = true
        Thread({
            while (running) {
                try {
                    val client = serverSocket!!.accept()
                    sockets.add(client)
                    try { executor.execute { try { handleClient(client) } finally { sockets.remove(client); client.close() } } }
                    catch (_: RejectedExecutionException) { sockets.remove(client); client.close() }
                } catch (e: IOException) {
                    if (running) e.printStackTrace()
                }
            }
        }, "DerpiProxyAccept").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        executor.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val requestLine = readLine(input) ?: run { client.close(); return }

            if (requestLine.startsWith("CONNECT", ignoreCase = true)) {
                handleConnect(requestLine, input, output, client)
            } else {
                handlePlainHttp(requestLine, input, output, client)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleConnect(
        requestLine: String,
        clientIn: java.io.InputStream,
        clientOut: java.io.OutputStream,
        client: Socket
    ) {
        val hostPort = requestLine.substringAfter("CONNECT ").substringBefore(" ")
        val host = hostPort.substringBefore(":")
        val portNum = hostPort.substringAfter(":", "443").toIntOrNull() ?: 443

        Log.d("LocalProxyServer", "CONNECT 请求: $hostPort")

        while (true) {
            val line = readLine(clientIn) ?: break
            if (line.isEmpty()) break
        }

        val connectHost = resolveHost(host)
        Log.d("LocalProxyServer", "目标主机: $host, 连接 IP: $connectHost, 端口: $portNum")

        var remote = Socket()
        sockets.add(remote)
        try {
            remote.connect(InetSocketAddress(connectHost, portNum), 10000)
            Log.d("LocalProxyServer", "远程连接成功: $connectHost:$portNum")
        } catch (e: Exception) {
            Log.e("LocalProxyServer", "远程连接失败: $connectHost:$portNum", e)
            sockets.remove(remote)
            runCatching { remote.close() }
            remote = Socket()
            sockets.add(remote)
            try {
                remote.connect(InetSocketAddress(host, portNum), 10000)
                Log.d("LocalProxyServer", "备用连接成功: $host:$portNum")
            } catch (e2: Exception) {
                Log.e("LocalProxyServer", "备用连接也失败: $host:$portNum", e2)
                try {
                    clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                } catch (_: Exception) {}
                client.close()
                sockets.remove(remote)
                runCatching { remote.close() }
                return
            }
        }

        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        clientOut.flush()
        client.soTimeout = 0 // HTTP parsing deadline must not become a tunnel idle deadline.
        relay(client, remote)
    }

    private fun handlePlainHttp(
        requestLine: String,
        clientIn: java.io.InputStream,
        clientOut: java.io.OutputStream,
        client: Socket
    ) {
        val headers = mutableListOf<String>()
        while (true) {
            val line = readLine(clientIn) ?: break
            if (line.isEmpty()) break
            headers.add(line)
        }
        val hostHeader = headers.find { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: run { client.close(); return }

        val host = hostHeader.substringBefore(":")
        val connectHost = resolveHost(host)

        val remote = Socket()
        sockets.add(remote)
        try {
            remote.connect(InetSocketAddress(connectHost, 80), 10000)
        } catch (e: Exception) {
            sockets.remove(remote)
            runCatching { remote.close() }
            client.close(); return
        }

        val remoteOut = remote.getOutputStream()
        remoteOut.write((requestLine + "\r\n").toByteArray())
        for (h in headers) remoteOut.write((h + "\r\n").toByteArray())
        remoteOut.write("\r\n".toByteArray())
        remoteOut.flush()
        client.soTimeout = 0
        relay(client, remote)
    }

    private fun relay(a: Socket, b: Socket) {
        val t1 = Thread { pipe(a, b) }
        val t2 = Thread { pipe(b, a) }
        t1.start(); t2.start()
        t1.join(); t2.join()
        try { a.close() } catch (_: Exception) {}
        try { b.close() } catch (_: Exception) {}
        sockets.remove(a)
        sockets.remove(b)
    }

    private fun resolveHost(host: String): String {
        val normalizedHost = host.lowercase()
        return preferredIps[normalizedHost]
            ?: preferredIps.entries.firstOrNull { (domain, _) ->
                normalizedHost.endsWith(".$domain")
            }?.value
            ?: host
    }

    private fun pipe(from: Socket, to: Socket) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
            // 连接关闭属正常情况
        } finally {
            try { from.shutdownInput() } catch (_: Exception) {}
            try { to.shutdownOutput() } catch (_: Exception) {}
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }
}
