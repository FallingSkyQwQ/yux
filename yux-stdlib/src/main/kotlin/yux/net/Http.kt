package yux.net

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * HTTP 客户端标准库（yux.net）。
 *
 * Yux 语言程序通过 `import yux.net.Http` + `Http.get("https://...")` 静态调用本类方法，
 * 因此全部公开方法为 `object` + `@JvmStatic`。实现基于 JDK 内置 `java.net.http`，
 * 响应体一律按 UTF-8 解码；网络错误统一包装为 [RuntimeException]。
 */
object Http {

    /** 连接与请求超时时间（秒）。 */
    private const val TIMEOUT_SECONDS: Long = 10

    /** 成功状态码区间下界（含）。 */
    private const val HTTP_OK_MIN: Int = 200

    /** 成功状态码区间上界（含）。 */
    private const val HTTP_OK_MAX: Int = 299

    /** 共享的 HTTP 客户端：惰性创建，连接超时 10 秒。 */
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build()
    }

    /**
     * 发起 HTTP GET 请求，返回按 UTF-8 解码的响应体。
     *
     * @param url 目标 URL（如 `https://example.com/api`）
     * @return 响应体文本
     * @throws RuntimeException 网络错误（消息含 URL，cause 保留原始 [IOException]/[InterruptedException]）
     *   或非 2xx 状态码（消息为 `yux.net: HTTP <code> <url>`）
     */
    @JvmStatic
    fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build()
        return send(request, url)
    }

    /**
     * 发起 HTTP POST 请求（Content-Type: text/plain），返回按 UTF-8 解码的响应体。
     *
     * @param url 目标 URL（如 `https://example.com/api`）
     * @param body 请求体文本
     * @return 响应体文本
     * @throws RuntimeException 网络错误（消息含 URL，cause 保留原始 [IOException]/[InterruptedException]）
     *   或非 2xx 状态码（消息为 `yux.net: HTTP <code> <url>`）
     */
    @JvmStatic
    fun post(url: String, body: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .header("Content-Type", "text/plain")
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build()
        return send(request, url)
    }

    /**
     * 通用资源获取入口，当前委托 [get]。
     *
     * 设计文档 §10.4 中的 async `fetch` 在 v0.1 以同步方式实现（返回完整响应体），
     * 后续里程碑再演进为异步 API。
     *
     * @param url 目标 URL
     * @return 响应体文本
     * @throws RuntimeException 与 [get] 相同
     */
    @JvmStatic
    fun fetch(url: String): String = get(url)

    private fun send(request: HttpRequest, url: String): String {
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (e: IOException) {
            throw RuntimeException("yux.net: 无法访问 $url", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException("yux.net: 请求被中断 $url", e)
        }
        if (response.statusCode() !in HTTP_OK_MIN..HTTP_OK_MAX) {
            throw RuntimeException("yux.net: HTTP ${response.statusCode()} $url")
        }
        return response.body()
    }
}
