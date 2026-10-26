package io.github.skyshadowhero.fancypad

import java.io.File

/**
 * 落盘诊断：把「关键事实」写到目标进程自己的数据目录，root 侧 `cat` 即可。
 *
 * ## 为什么需要这个
 *
 * 本机 logd 的常规缓冲区不收集（`logcat -b main -d` 恒为 0 行），LSPosed 日志也不一定
 * 收得到模块日志 —— 于是「版本分档到底匹配上没有 / 哪些资源名解析到了」这种问题
 * 只能靠猜。这里给一条**不依赖日志**的取证通道。
 *
 * ## 落盘位置
 *
 * | 进程 | 文件 |
 * |---|---|
 * | 普通应用（含输入法） | `/data/data/<包名>/files/fancypad-diag.txt` |
 * | system_server | `/data/system/fancypad-diag.txt` |
 *
 * 每次写入是**追加**，并在开头带时间戳；文件超过 [MAX_BYTES] 时截断重写，避免无限增长。
 */
object Diag {

    private const val MAX_BYTES = 96 * 1024

    @Volatile
    private var processName: String? = null

    fun attach(name: String) {
        processName = name
    }

    /** 追加一行/一段。全程 best-effort：诊断通道自己绝不能把目标进程搞崩。 */
    fun write(section: String, body: String) {
        val name = processName ?: return
        try {
            val file = fileFor(name)
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val text = "[$stamp] === $section ===\n$body\n"
            val existing = if (file.isFile) file.readText() else ""
            val kept = if (existing.length + text.length > MAX_BYTES) "" else existing
            file.parentFile?.mkdirs()
            file.writeText(kept + text)
        } catch (t: Throwable) {
            // 诊断失败绝不能影响功能
        }
    }

    private fun fileFor(name: String): File {
        val host = name.substringBefore(':')
        val system = host == "system" || host == "android"
        return if (system) {
            File("/data/system/fancypad-diag.txt")
        } else {
            File("/data/data/$host/files/fancypad-diag.txt")
        }
    }
}
