package com.kerybotu.derpibooru.mirror

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class LocalProxyRegressionTest {
    @Test fun newConnectIsNotQueuedBehindFourLongLivedTunnels() {
        val upstream = ServerSocket(0)
        val remotes = CopyOnWriteArrayList<Socket>()
        Thread({
            try {
                while (!upstream.isClosed) {
                    val socket = upstream.accept()
                    remotes.add(socket)
                    Thread({
                        try {
                            val input = socket.getInputStream()
                            val output = socket.getOutputStream()
                            while (!socket.isClosed) {
                                val byte = input.read()
                                if (byte < 0) break
                                output.write(byte); output.flush()
                            }
                        } catch (_: Exception) {} finally { socket.close() }
                    }, "ProxyTestEcho").apply { isDaemon = true; start() }
                }
            } catch (_: Exception) {}
        }, "ProxyTestAccept").apply { isDaemon = true; start() }
        val proxy = LocalProxyServer(emptyMap())
        val clients = mutableListOf<Socket>()
        try {
            proxy.start()
            repeat(8) {
                val client = Socket("127.0.0.1", proxy.port).apply { soTimeout = 3000 }
                clients += client
                val out = client.getOutputStream()
                out.write("CONNECT 127.0.0.1:${upstream.localPort} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray()); out.flush()
                val input = client.getInputStream()
                val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) {
                    val byte = input.read(); assertTrue(byte >= 0); headers.append(byte.toChar())
                }
                assertTrue(headers.startsWith("HTTP/1.1 200"))
                out.write(42); out.flush()
                assertEquals(42, input.read())
            }
            // The old request-parsing timeout must not kill an idle TLS tunnel.
            Thread.sleep(16_000)
            clients.first().getOutputStream().apply { write(43); flush() }
            assertEquals(43, clients.first().getInputStream().read())
            proxy.stop()
            assertEquals(-1, clients.last().getInputStream().read())
        } finally {
            proxy.stop()
            clients.forEach { runCatching { it.close() } }
            upstream.close()
            remotes.forEach { runCatching { it.close() } }
        }
    }
}
