package club.xiaojiawei.hsscriptbasestrategy.box

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * CEF DevTools 协议最小客户端（JDK 内置 java.net.http，零第三方依赖）。
 *
 * 网易炉石盒子以 --remote-debugging-port 启动后，悬浮窗的每个页面
 * （记牌器/胜率预估/推荐打法）都是一个调试目标；通过 HTTP /json/list
 * 列目标、WebSocket 发 Runtime.evaluate 即可毫秒级直读页面实时 DOM。
 */
class DevToolsClient(
    private val port: Int = 9222,
    private val timeoutMs: Long = 5000,
) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build()

    /** 调试端口是否在线 */
    fun available(): Boolean = try {
        val request = request("http://127.0.0.1:$port/json/version")
        client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
    } catch (e: Exception) {
        false
    }

    /**
     * 全部 page 调试目标，返回 (页面URL, webSocketDebuggerUrl) 列表。
     * DevTools /json/list 的 JSON 结构稳定，用正则提取避免引依赖。
     */
    fun pageTargets(): List<Pair<String, String>> = try {
        val request = request("http://127.0.0.1:$port/json/list")
        val body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
        TARGET_REGEX.findAll(body)
            .map { it.groupValues[1] to it.groupValues[2] } // (url, webSocketDebuggerUrl)
            .toList()
    } catch (e: Exception) {
        emptyList()
    }

    /** 按 URL 子串找 page 目标的 WebSocket 地址 */
    fun findWebSocketUrl(urlSubstring: String): String? =
        pageTargets().firstOrNull { it.first.contains(urlSubstring) }?.second

    /**
     * 在指定页面执行表达式并返回其字符串值（returnByValue）。
     * 对含中文/换行的页面文本建议用 [readPageText]（Base64 通道免转义问题）。
     */
    fun evaluate(wsUrl: String, expr: String): String? = try {
        val responseFuture = CompletableFuture<String>()
        val message =
            """{"id":1,"method":"Runtime.evaluate","params":{"expression":${jsonString(expr)},"returnByValue":true}}"""
        val ws = client.newWebSocketBuilder()
            .buildAsync(URI.create(wsUrl), object : WebSocket.Listener {
                private val buffer = StringBuilder()

                override fun onText(
                    webSocket: WebSocket,
                    data: CharSequence,
                    last: Boolean,
                ): CompletionStage<Void>? {
                    buffer.append(data)
                    if (last) {
                        val text = buffer.toString()
                        buffer.setLength(0)
                        if (text.contains("\"id\":1")) {
                            responseFuture.complete(text)
                        }
                    }
                    webSocket.request(1)
                    return null
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    responseFuture.completeExceptionally(error)
                }
            })
            .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .join()
        ws.sendText(message, true)
        val response = responseFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
        extractResultValue(response)
    } catch (e: TimeoutException) {
        null
    } catch (e: Exception) {
        null
    }

    /**
     * 读取页面 document.body.innerText。
     * 经 Base64 通道回传，规避 JSON 字符串转义问题。
     */
    fun readPageText(urlSubstring: String): String? {
        val wsUrl = findWebSocketUrl(urlSubstring) ?: return null
        val expr =
            "(function(){try{return btoa(unescape(encodeURIComponent(document.body.innerText)))}catch(e){return ''}})()"
        val b64 = evaluate(wsUrl, expr) ?: return null
        return try {
            String(Base64.getDecoder().decode(b64.trim()), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun request(url: String): HttpRequest =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .GET()
            .build()

    companion object {
        // group1=url 值，group2=webSocketDebuggerUrl（JSON 里 url 键在前）
        private val TARGET_REGEX =
            Regex("\"url\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"webSocketDebuggerUrl\"\\s*:\\s*\"([^\"]+)\"")

        private fun jsonString(s: String): String {
            val sb = StringBuilder("\"")
            for (ch in s) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
                }
            }
            sb.append('"')
            return sb.toString()
        }

        /** 从 Runtime.evaluate 响应中提取 result.result.value 的 JSON 字符串值 */
        private fun extractResultValue(response: String): String? {
            val key = "\"value\":\""
            val start = response.indexOf(key)
            if (start < 0) return null
            val sb = StringBuilder()
            var i = start + key.length
            while (i < response.length) {
                val c = response[i]
                when {
                    c == '\\' && i + 1 < response.length -> {
                        val n = response[i + 1]
                        when (n) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                val hex = response.substring(i + 2, minOf(i + 6, response.length))
                                val code = hex.toIntOrNull(16) ?: return sb.toString()
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> sb.append(n)
                        }
                        i += 2
                    }
                    c == '"' -> return sb.toString()
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return sb.toString()
        }
    }
}
