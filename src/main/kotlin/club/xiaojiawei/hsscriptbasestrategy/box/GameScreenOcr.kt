package club.xiaojiawei.hsscriptbasestrategy.box

import club.xiaojiawei.hsscriptbase.config.log
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 游戏屏幕截图 + OCR 定位器。
 *
 * OCR 走本机 FastAPI 服务（RapidOCR/PP-OCR 模型，由 [OcrServiceManager]
 * 自动拉起）：截图存盘 → POST /ocr → 返回各文字行的中心点（文本经
 * Base64 通道回传）→ 匹配目标名字 → 得到屏幕像素坐标。截图与点击
 * 同一像素坐标系，天然无换算误差，任意发现/抉择布局通吃。
 */
object GameScreenOcr {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** 全屏截图存盘，返回图片文件路径 */
    fun captureToFile(): String? = runCatching {
        val screen = Toolkit.getDefaultToolkit().screenSize
        val image: BufferedImage = Robot().createScreenCapture(
            Rectangle(0, 0, screen.width, screen.height)
        )
        val dir = File("ocr_res/box_follow").apply { mkdirs() }
        dir.listFiles()?.let { files ->
            if (files.size > 12) files.sortedBy { it.name }.first().delete()
        }
        val f = File(dir, "capture_${System.currentTimeMillis()}.png")
        ImageIO.write(image, "png", f)
        f.absolutePath
    }.getOrNull()

    /**
     * 在最新截图中定位目标文字，返回中心点（屏幕像素坐标）。
     * 中文 OCR 可能切碎词组：整词命中 > 包含命中 > 相邻框拼接命中。
     */
    fun locateTextOnScreen(baseUrl: String, name: String): Pair<Int, Int>? {
        val capturePath = captureToFile() ?: return null
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/ocr"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """{"image_path":${jsonString(capturePath)}}"""
                )
            )
            .build()
        val body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()

        val words = LINE_REGEX.findAll(body).map { m ->
            val text = String(
                Base64.getDecoder().decode(m.groupValues[1]), Charsets.UTF_8
            )
            Triple(text, m.groupValues[2].toDouble(), m.groupValues[3].toDouble())
        }.toList()

        var best: Triple<Int, Int, Int>? = null // (匹配分, x, y)
        for ((text, cx, cy) in words) {
            if (text.length < 2) continue
            val score = when {
                text == name -> 1000 + text.length
                text.contains(name) -> 500 + text.length
                name.contains(text) && text.length >= 2 -> text.length
                else -> 0
            }
            if (score > 0 && (best == null || score > best.first)) {
                best = Triple(score, cx.toInt(), cy.toInt())
            }
        }
        if (best == null && words.size > 1) {
            // 相邻框拼接兜底（名字被 OCR 切碎）
            val sorted = words.sortedBy { it.second }
            for (i in sorted.indices) {
                var joined = sorted[i].first
                for (j in i + 1 until sorted.size) {
                    joined += sorted[j].first
                    if (name == joined) {
                        best = Triple(
                            1000,
                            ((sorted[i].second + sorted[j].second) / 2).toInt(),
                            sorted[i].third.toInt(),
                        )
                        break
                    }
                }
                if (best != null) break
            }
        }
        return best?.let { it.second to it.third }
    }

    /** 在屏幕像素坐标左键单击（游戏处于前台时生效） */
    fun clickAt(x: Int, y: Int) {
        val robot = Robot()
        robot.mouseMove(x, y)
        Thread.sleep(80)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(40)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** /ocr 响应行：{"b64":"...","cx":...,"cy":...,"score":...} */
    private val LINE_REGEX = Regex(
        "\\{\"b64\":\"([^\"]*)\",\"cx\":(-?[\\d.]+),\"cy\":(-?[\\d.]+)"
    )
}
