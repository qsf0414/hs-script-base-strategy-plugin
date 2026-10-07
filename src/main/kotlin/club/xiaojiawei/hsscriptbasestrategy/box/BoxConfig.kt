package club.xiaojiawei.hsscriptbasestrategy.box

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * 盒子跟随插件配置。
 *
 * 读取软件根目录 config/box-follow.properties（工作目录即软件根目录）：
 * ```
 * # 网易炉石盒子主程序完整路径
 * box.exe=D:\Software\炉石传说盒子\HSAng.exe
 * # CEF DevTools 调试端口
 * box.devtools.port=9222
 * # 跟随盒子出牌/攻击
 * follow.play=true
 * # 跟随盒子起手换牌
 * follow.mulligan=true
 * # 盒子无指令时回落基础策略逻辑
 * fallback.base=true
 * # 每个动作后额外等待毫秒数（等内存状态同步）
 * action.extraDelayMs=600
 * ```
 */
class BoxConfig private constructor() {

    val boxExe: String
    val devToolsPort: Int
    val followPlay: Boolean
    val followMulligan: Boolean
    val fallbackBase: Boolean
    val actionExtraDelayMs: Long
    val ocrApiUrl: String
    val ocrPythonExe: String
    val ocrServiceDir: String

    init {
        val props = Properties()
        val candidates = listOf(
            Path.of("config", "box-follow.properties"),
            Path.of("box-follow.properties"),
        )
        for (p in candidates) {
            if (Files.exists(p)) {
                runCatching {
                    // Properties 默认按 ISO-8859-1 读取，中文路径必须显式用 UTF-8
                    Files.newBufferedReader(p, Charsets.UTF_8).use { props.load(it) }
                }
                break
            }
        }
        boxExe = props.getProperty("box.exe", "").trim()
        devToolsPort = props.getProperty("box.devtools.port", "9222").trim().toIntOrNull() ?: 9222
        followPlay = props.getProperty("follow.play", "true").equals("true", ignoreCase = true)
        followMulligan = props.getProperty("follow.mulligan", "true").equals("true", ignoreCase = true)
        fallbackBase = props.getProperty("fallback.base", "true").equals("true", ignoreCase = true)
        actionExtraDelayMs = props.getProperty("action.extraDelayMs", "600").trim().toLongOrNull() ?: 600L
        ocrApiUrl = props.getProperty("ocr.api.url", "http://127.0.0.1:9233").trim()
        ocrPythonExe = props.getProperty("ocr.python.exe", "").trim()
        ocrServiceDir = props.getProperty("ocr.service.dir", "").trim()
    }

    companion object {
        val INSTANCE: BoxConfig by lazy { BoxConfig() }
    }
}
