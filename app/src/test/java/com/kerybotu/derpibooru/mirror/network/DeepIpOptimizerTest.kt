package com.kerybotu.derpibooru.mirror.network

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class DeepIpOptimizerTest {
    @Test
    fun decodeProbeBodyReadsChunkedJsonWithoutFraming() {
        val body = "{\"images\":[]}".toByteArray()
        val chunked = "${body.size.toString(16)}\r\n".toByteArray() + body + "\r\n0\r\n\r\n".toByteArray()
        assertEquals(
            "{\"images\":[]}",
            DeepIpOptimizer.decodeProbeBody(
                ByteArrayInputStream(chunked),
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n",
                1024
            )
        )
    }

    @Test
    fun decodeProbeBodyHonorsContentLengthAndLimit() {
        val bytes = "{\"images\":[1,2]}EXTRA".toByteArray()
        assertEquals(
            "{\"images\":[1,2]}",
            DeepIpOptimizer.decodeProbeBody(
                ByteArrayInputStream(bytes),
                "HTTP/1.1 200 OK\r\nContent-Length: 16\r\n",
                1024
            )
        )
    }
}
