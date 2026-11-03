package io.github.skyshadowhero.fancypad

import java.io.File

/**
 * 进程名 → 包名 / 是否系统进程。
 *
 * ## 为什么需要这一层
 *
 * 模块按**包名**分派功能域（[XposedEntry.installForPackage] 的 `when (pkg)`），
 * 但热重载回调只给**进程名**（`HotReloadedParam.processName`），两者不是一回事：
 *
 * | 进程 | packageName | processName |
 * |---|---|---|
 * | 输入法 | `com.xiaomi.type` | `com.xiaomi.type` |
 * | SystemUI 子进程 | `com.android.systemui` | `com.android.systemui:screenshot` |
 * | system_server | `android` | `system` / `system_server` |
 *
 * 早先热重载直接把 `processName.substringBefore(':')` 当包名传回去，system_server
 * 就会掉进「其它包」分支 —— 在 system_server 里装右键菜单域（`View.setBackground`
 * 那种全局 Hook），而光标和随手写声明不会重装。所以统一在这里归一。
 *
 * ## system_server 的进程名不止一种写法
 *
 * `/proc/self/cmdline` 是 `system_server`，但部分框架实现上报成 `system`，
 * 早期 Xposed 系还用过 `android`。三种都按系统进程处理，宁可多认一个名字，
 * 也不要漏认导致误装。
 */
internal object ProcessNames {

    /** 所有视为「系统进程」的进程名（不含子进程后缀）。 */
    private val SYSTEM_PROCESS_NAMES = setOf("system", "system_server", "android")

    /** 是否系统进程（system_server 的任意一种写法）。子进程 `xxx:remote` 按主进程名判断。 */
    fun isSystemProcess(processName: String?): Boolean = hostOf(processName) in SYSTEM_PROCESS_NAMES

    /**
     * 进程名 → 它所属的包名；拿不到（null / 空串）时返回 null。
     *
     * 系统进程统一归到 [Target.SYSTEM_PACKAGE]（`android`），与 `onPackageReady` 的
     * `param.packageName` 一致，这样热重载与首次安装走的是同一个分支。
     */
    fun packageOf(processName: String?): String? {
        val host = hostOf(processName)
        if (host.isEmpty()) return null
        return if (host in SYSTEM_PROCESS_NAMES) Target.SYSTEM_PACKAGE else host
    }

    /**
     * 诊断落盘目录：系统进程写 `/data/system`，普通应用写自己数据目录的 `files/`。
     *
     * 两边都用 [isSystemProcess] 判定，避免「标记文件写到了 /data/data/system_server」
     * 那种查起来很像没注入的假象（那个目录不存在，写入只会静默失败）。
     */
    fun diagDir(processName: String?): File = if (isSystemProcess(processName)) {
        File("/data/system")
    } else {
        File("/data/data/${hostOf(processName)}/files")
    }

    /** 去掉子进程后缀，拿到主进程名。 */
    private fun hostOf(processName: String?): String =
        processName?.trim()?.substringBefore(':').orEmpty()
}
