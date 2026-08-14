package com.kerybotu.derpibooru.mirror

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class LocalProxyServer(
    targetDomain: String,
    preferredIp: String
) {
    @Volatile
    var targetDomain: String = targetDomain
        private set

    fun updateTargetDomain(newDomain: String) {
        targetDomain = newDomain
    }

    @Volatile
    var preferredIp: String = preferredIp
        private set

    fun updateTargetIp(newIp: String) {
        preferredIp = newIp
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false

    var port: Int = 0
        private set

    fun start() {
        serverSocket = ServerSocket(0, 50, InetSocketAddress("127.0.0.1", 0).address)
        port = serverSocket!!.localPort
        running = true
        executor.execute {
            while (running) {
                try {
                    val client = serverSocket!!.accept()
                    executor.execute { handleClient(client) }
                } catch (e: IOException) {
                    if (running) e.printStackTrace()
                }
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
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

        while (true) {
            val line = readLine(clientIn) ?: break
            if (line.isEmpty()) break
        }

        val connectHost = if (host.equals(targetDomain, ignoreCase = true)) preferredIp else host

        val remote = Socket()
        try {
            remote.connect(InetSocketAddress(connectHost, portNum), 10000)
        } catch (e: Exception) {
            try {
                remote.connect(InetSocketAddress(host, portNum), 10000)
            } catch (e2: Exception) {
                try {
                    clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                } catch (_: Exception) {}
                client.close()
                return
            }
        }

        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        clientOut.flush()

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
        val connectHost = if (host.equals(targetDomain, ignoreCase = true)) preferredIp else host

        val remote = Socket()
        try {
            remote.connect(InetSocketAddress(connectHost, 80), 10000)
        } catch (e: Exception) {
            client.close(); return
        }

        val remoteOut = remote.getOutputStream()
        remoteOut.write((requestLine + "\r\n").toByteArray())
        for (h in headers) remoteOut.write((h + "\r\n").toByteArray())
        remoteOut.write("\r\n".toByteArray())
        remoteOut.flush()

        relay(client, remote)
    }

    private fun relay(a: Socket, b: Socket) {
        val t1 = Thread { pipe(a, b) }
        val t2 = Thread { pipe(b, a) }
        t1.start(); t2.start()
        t1.join(); t2.join()
        try { a.close() } catch (_: Exception) {}
        try { b.close() } catch (_: Exception) {}
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