package com.skyler.fancytype

import java.util.concurrent.TimeUnit

/**
 * 从系统层面强制关闭输入法进程。
 *
 * 为什么要 root：`am force-stop` 走 system_server 的正规流程，需要 `FORCE_STOP_PACKAGES`
 * 权限，模块 App（普通 uid）拿不到，`ActivityManager.forceStopPackage` 同理。
 * 唯一可行的是借 KernelSU / Magisk 的 `su` 执行，等于以 root 身份下令。
 *
 * 拿不到 root 就直接报错，不做降级 —— 之前那套「写信号让输入法自己在生命周期点轮询后自杀」
 * 的方案只能由输入法杀自己、时机还晚到下次弹键盘，没有意义，已经删掉。
 */
object ImeRestarter {

    private const val TARGET = "com.xiaomi.type"

    /** 要给用户留出点「允许 root」的时间，所以给得宽一些 */
    private const val TIMEOUT_SEC = 20L

    /** su 位置随 root 方案不同，按常见路径试 */
    private val SU_CANDIDATES = listOf("su", "/system/bin/su", "/debug_ramdisk/su")

    sealed interface Result {
        /** 已在系统层面杀死（进程确认消失） */
        data object Killed : Result

        /** 失败原因，直接展示给用户 */
        data class Failed(val reason: String) : Result
    }

    fun forceStop(): Result {
        val reasons = ArrayList<String>()
        for (su in SU_CANDIDATES) {
            val stop = run(su, "am force-stop $TARGET")
            if (!stop.ok) {
                reasons.add("$su: ${stop.detail}")
                continue
            }
            // force-stop 是异步的，轮询确认进程真的没了再报成功
            repeat(12) {
                if (pidOf(su) == null) return Result.Killed
                sleep(200)
            }
            return Result.Failed("已下发 force-stop，但进程仍在")
        }
        return Result.Failed(reasons.joinToString("；").ifBlank { "未找到 su" })
    }

    private data class ShellResult(val ok: Boolean, val detail: String)

    /**
     * 执行一条 root 命令。
     *
     * 注意：**不要去读它的 stdout**。`su` 在等用户授权时会一直持有管道，
     * 先 `readText()` 就永久阻塞，后面那句带超时的 `waitFor` 根本执行不到
     * （第一版就是这么写死的，表现为点了重启一直没反应）。
     * 这些命令输出极少，直接不读、只靠 `waitFor(timeout)` 兜底。
     */
    private fun run(su: String, command: String): ShellResult = try {
        val proc = ProcessBuilder(su, "-c", command)
            .redirectErrorStream(true)
            .start()
        val finished = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            ShellResult(false, "超时（root 未授权？）")
        } else {
            ShellResult(proc.exitValue() == 0, "exit=${proc.exitValue()}")
        }
    } catch (t: Throwable) {
        ShellResult(false, t.message ?: t.javaClass.simpleName)
    }

    /** @return 进程存活时返回 pid；已退出返回 null。只在上一步已确认 root 可用时才调用。 */
    private fun pidOf(su: String): String? = try {
        val proc = ProcessBuilder(su, "-c", "pidof $TARGET")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
        proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
        out.ifBlank { null }
    } catch (t: Throwable) {
        null
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
