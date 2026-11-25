package io.github.skyshadowhero.fancypad.ui.utils

import java.util.concurrent.TimeUnit

/** 使用 KernelSU / Magisk 的 Root Shell 批量强制关闭目标应用。 */
object AppForceStopper {

    private const val TIMEOUT_SEC = 20L
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private val packageName = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private val suCandidates = listOf("su", "/system/bin/su", "/debug_ramdisk/su")
    private val blockedPackages = setOf("android", "system", "system_server")

    sealed interface Result {
        data class Stopped(val packages: Set<String>) : Result
        data class Partial(
            val stopped: Set<String>,
            val failed: Map<String, String>,
        ) : Result

        data class Failed(val reason: String) : Result
    }

    /** system_server 不能按普通应用强停；包名也必须经过白名单格式校验。 */
    fun isForceStoppablePackage(value: String): Boolean =
        value !in blockedPackages && packageName.matches(value)

    fun forceStop(packages: Collection<String>): Result {
        val targets = packages.asSequence()
            .filter(::isForceStoppablePackage)
            .distinct()
            .toList()
        if (targets.isEmpty()) return Result.Failed("未选择可强制关闭的应用")

        val rootErrors = ArrayList<String>()
        for (su in suCandidates) {
            val probe = run(su, "id")
            if (!probe.ok || "uid=0" !in probe.detail) {
                rootErrors += "$su: ${probe.detail}"
                continue
            }

            val stopped = linkedSetOf<String>()
            val failed = linkedMapOf<String, String>()
            for (target in targets) {
                val oldPids = pidOf(su, target)
                // SystemUI 是 system_server 管理的 persistent 进程。force-stop 可能被系统忽略，
                // 或留下 stopped 状态而不重新拉起；直接杀掉旧 PID 才是可靠的“重启系统界面”。
                val stop = if (target == SYSTEM_UI_PACKAGE) {
                    if (oldPids.isEmpty()) {
                        failed[target] = "未找到系统界面进程"
                        continue
                    }
                    run(su, "kill -9 ${oldPids.joinToString(" ")}")
                } else {
                    run(su, "am force-stop --user current $target")
                }
                if (!stop.ok) {
                    failed[target] = stop.detail
                    continue
                }

                val stoppedSuccessfully = if (target == SYSTEM_UI_PACKAGE) {
                    // 不只确认旧进程退出，还要等 system_server 拉起一个新 PID。
                    waitForProcessReplacement(su, target, oldPids)
                } else {
                    oldPids.isEmpty() || waitForOldProcessExit(su, target, oldPids)
                }
                if (stoppedSuccessfully) {
                    stopped += target
                } else {
                    failed[target] = if (target == SYSTEM_UI_PACKAGE) {
                        "系统界面未被重新拉起"
                    } else {
                        "旧进程仍在运行"
                    }
                }
            }

            return when {
                failed.isEmpty() -> Result.Stopped(stopped)
                stopped.isNotEmpty() -> Result.Partial(stopped, failed)
                else -> Result.Failed(failed.values.firstOrNull() ?: "强制关闭失败")
            }
        }

        return Result.Failed(rootErrors.joinToString("；").ifBlank { "未找到 su" })
    }

    private fun waitForOldProcessExit(su: String, target: String, oldPids: Set<String>): Boolean {
        repeat(12) {
            val current = pidOf(su, target)
            if (current.isEmpty() || current.none(oldPids::contains)) return true
            sleep(200)
        }
        return false
    }

    private fun waitForProcessReplacement(su: String, target: String, oldPids: Set<String>): Boolean {
        repeat(30) {
            if (pidOf(su, target).any { it !in oldPids }) return true
            sleep(200)
        }
        return false
    }

    private data class ShellResult(val ok: Boolean, val detail: String)

    /**
     * 不在 waitFor 前读取输出：su 等待用户授权时会一直持有管道，先读会绕过超时保护。
     */
    private fun run(su: String, command: String): ShellResult = try {
        val process = ProcessBuilder(su, "-c", command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            ShellResult(false, "超时（Root 未授权？）")
        } else {
            val exitCode = process.exitValue()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            ShellResult(exitCode == 0, output.ifBlank { "exit=$exitCode" })
        }
    } catch (t: Throwable) {
        ShellResult(false, t.message ?: t.javaClass.simpleName)
    }

    private fun pidOf(su: String, target: String): Set<String> = try {
        val process = ProcessBuilder(su, "-c", "pidof $target")
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            emptySet()
        } else {
            process.inputStream.bufferedReader().use { it.readText() }
                .trim()
                .splitToSequence(Regex("\\s+"))
                .filter { value -> value.isNotBlank() && value.all(Char::isDigit) }
                .toSet()
        }
    } catch (_: Throwable) {
        emptySet()
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
