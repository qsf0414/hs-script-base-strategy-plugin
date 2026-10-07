package club.xiaojiawei.hsscriptbasestrategy.box

import club.xiaojiawei.hsscriptbase.config.log
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 网易炉石盒子进程管理：确保 HSAng 以 CEF 调试端口运行。
 *
 * 盒子带 requireAdministrator 清单，脚本本体以管理员运行（注入需要），
 * 一般可直接 CreateProcess；若被拒则回退 PowerShell 提权启动（弹 UAC）。
 */
object BoxManager {

    private const val BOX_PROCESS = "HSAng.exe"
    private var lastEnsureTs = 0L

    /** 确保盒子调试端口在线；60 秒内不重复探测 */
    fun ensureBoxReady(config: BoxConfig): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastEnsureTs < 60_000) {
            return DevToolsClient(config.devToolsPort).available()
        }
        lastEnsureTs = now
        val client = DevToolsClient(config.devToolsPort)
        if (client.available()) return true

        if (isBoxRunning()) {
            log.info { "[盒子] 盒子在运行但未开启 DevTools 调试端口，带参重启..." }
            killBox()
            Thread.sleep(2000)
        }
        if (config.boxExe.isBlank() || !Files.exists(Path.of(config.boxExe))) {
            log.warn { "[盒子] 盒子路径未配置或不存在（config/box-follow.properties 的 box.exe），无法自动启动" }
            return false
        }
        launch(config)
        return waitForPort(config.devToolsPort, 20_000)
    }

    private fun isBoxRunning(): Boolean = try {
        val process = ProcessBuilder("tasklist", "/FI", "IMAGENAME eq $BOX_PROCESS", "/NH")
            .start()
        process.waitFor(10, TimeUnit.SECONDS)
        process.inputStream.bufferedReader().readText().contains(BOX_PROCESS)
    } catch (e: Exception) {
        false
    }

    private fun killBox() = try {
        val process = ProcessBuilder("taskkill", "/F", "/IM", BOX_PROCESS).start()
        process.waitFor(10, TimeUnit.SECONDS)
    } catch (e: Exception) {
        // 忽略
    }

    private fun launch(config: BoxConfig) {
        val exe = Path.of(config.boxExe)
        log.info { "[盒子] 自动启动网易炉石盒子（带 DevTools 调试端口）..." }
        try {
            ProcessBuilder(
                exe.toString(),
                "--remote-debugging-port=${config.devToolsPort}",
                "--remote-allow-origins=*",
            ).directory(exe.parent.toFile())
                .start()
        } catch (e: IOException) {
            log.info { "[盒子] 直接启动失败（可能需要管理员权限），改用 PowerShell 提权（如弹 UAC 请点「是」）" }
            val ps = "Start-Process -FilePath '${config.boxExe}' " +
                "-ArgumentList '--remote-debugging-port=${config.devToolsPort}','--remote-allow-origins=*' " +
                "-WorkingDirectory '${exe.parent}'"
            ProcessBuilder("powershell", "-NoProfile", "-Command", ps).start()
        }
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val client = DevToolsClient(port)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (client.available()) {
                log.info { "[盒子] DevTools 调试端口就绪（:$port）" }
                return true
            }
            Thread.sleep(1000)
        }
        log.warn { "[盒子] DevTools 端口未就绪，直读不可用（将回落基础策略逻辑）" }
        return false
    }
}
