package club.xiaojiawei.hsscriptbasestrategy.box

import club.xiaojiawei.hsscriptbase.config.log
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 本机 OCR 服务（FastAPI + RapidOCR/PP-OCR）生命周期管理：
 * 端口不健康时用配置的 Python 环境自动拉起 uvicorn，等待模型预热完成。
 * 服务拉起后常驻（轻量），插件退出不回收，下次秒连。
 */
object OcrServiceManager {

    private var lastEnsureTs = 0L

    fun ensureReady(baseUrl: String, pythonExe: String, serviceDir: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastEnsureTs < 60_000) {
            return healthy(baseUrl)
        }
        lastEnsureTs = now
        if (healthy(baseUrl)) return true

        if (pythonExe.isBlank() || serviceDir.isBlank() ||
            !File(serviceDir, "main.py").exists() || !File(pythonExe).exists()
        ) {
            log.warn {
                "[盒子] OCR 服务不可用且启动参数不全（config/box-follow.properties 的 " +
                    "ocr.python.exe / ocr.service.dir），OCR 定位将回落几何点击"
            }
            return false
        }
        log.info { "[盒子] 启动本机 OCR 服务（FastAPI + RapidOCR）..." }
        return try {
            val process = ProcessBuilder(
                pythonExe, "-m", "uvicorn", "main:app",
                "--host", "127.0.0.1",
                "--port", baseUrl.substringAfterLast(':'),
            )
                .directory(File(serviceDir))
                .start()
            log.info { "[盒子] OCR 服务进程已启动 (pid=${process.pid()})" }
            waitHealthy(baseUrl, 30_000)
        } catch (e: Exception) {
            log.warn { "[盒子] OCR 服务启动失败: ${e.message}" }
            false
        }
    }

    private fun healthy(baseUrl: String): Boolean = try {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/health"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build()
        HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.ofString())
            .statusCode() == 200
    } catch (e: Exception) {
        false
    }

    private fun waitHealthy(baseUrl: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (healthy(baseUrl)) {
                log.info { "[盒子] OCR 服务就绪（$baseUrl）" }
                return true
            }
            Thread.sleep(1000)
        }
        log.warn { "[盒子] OCR 服务启动超时，OCR 定位将回落几何点击" }
        return false
    }
}
