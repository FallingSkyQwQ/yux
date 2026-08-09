package yux.net

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpTest {

    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private lateinit var baseUrl: String

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        executor.shutdown()
    }

    @Test
    fun `get 发送 GET 请求并返回响应体`() {
        val method = AtomicReference<String>()
        val path = AtomicReference<String>()
        server.createContext("/api/greet") { exchange ->
            method.set(exchange.requestMethod)
            path.set(exchange.requestURI.path)
            val body = "你好, Yux"
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        assertEquals("你好, Yux", Http.get("$baseUrl/api/greet"))
        assertEquals("GET", method.get())
        assertEquals("/api/greet", path.get())
    }

    @Test
    fun `post 发送请求体并返回响应`() {
        val method = AtomicReference<String>()
        val contentType = AtomicReference<String>()
        val receivedBody = AtomicReference<String>()
        server.createContext("/api/echo") { exchange ->
            method.set(exchange.requestMethod)
            contentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            receivedBody.set(requestBody)
            val body = "echo: $requestBody"
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        val response = Http.post("$baseUrl/api/echo", "data-123")
        assertEquals("echo: data-123", response)
        assertEquals("POST", method.get())
        assertEquals("text/plain", contentType.get())
        assertEquals("data-123", receivedBody.get())
    }

    @Test
    fun `fetch 委托 get 返回响应体`() {
        server.createContext("/api/fetch") { exchange ->
            val body = "fetched"
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        assertEquals("fetched", Http.fetch("$baseUrl/api/fetch"))
    }

    @Test
    fun `非 2xx 状态码抛出含状态码的异常`() {
        server.createContext("/api/error") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        val e = assertFailsWith<RuntimeException> { Http.get("$baseUrl/api/error") }
        assertEquals("yux.net: HTTP 404 $baseUrl/api/error", e.message)
    }

    @Test
    fun `连接不可达时抛出 RuntimeException`() {
        val e = assertFailsWith<RuntimeException> { Http.get("http://127.0.0.1:1/") }
        assertTrue(e.message!!.startsWith("yux.net"))
        assertTrue(e.cause is java.io.IOException)
    }
}
