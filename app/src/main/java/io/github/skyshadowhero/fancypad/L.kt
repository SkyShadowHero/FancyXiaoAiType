package io.github.skyshadowhero.fancypad

import android.util.Log

/**
 * 结构化日志：每条都带 `event=` 便于过滤与链路核对。
 *
 * ## 为什么要镜像到 LSPosed 日志
 *
 * 本机是 MIUI 正式版，**logd 不收集常规缓冲区**：
 *
 * ```
 * logcat -b main   -d | wc -l   →  0
 * logcat -b system -d | wc -l   →  0
 * logcat -b crash  -d | wc -l   →  0
 * logcat -b events -d | wc -l   → 164（只有开机那批 SELinux 审计）
 * ```
 *
 * 也就是说 `Log.i/w/e` 打出去就没了 —— 模块全部诊断日志在这台设备上等于失效，
 * 排查只能靠猜。LSPosed 自己的日志**不经过 logd**（由 lspd 直接落盘到
 * `/data/adb/lspd/log/`），所以 [attach] 一个出口把它接进来：root 侧 `grep` 那个目录即可取证。
 *
 * 在 `XposedEntry.onModuleLoaded` 里接（每个进程各调一次），并带上进程名，
 * 这样同一条日志能看出是 system_server、SystemUI 还是输入法进程打的。
 */
object L {
    const val TAG = "FancyPad"

    /** 额外出口（priority, message）。没接上时只走 logcat，行为与以前一致。 */
    @Volatile
    private var sink: ((Int, String) -> Unit)? = null

    /** 在模块入口接一次即可；重复调用以最后一次为准。 */
    fun attach(sink: (priority: Int, message: String) -> Unit) {
        this.sink = sink
    }

    private fun emit(priority: Int, msg: String) {
        sink?.let {
            // LSPosed 日志自己带时间戳与进程，这里不再重复拼
            runCatching { it(priority, msg) }
        }
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        emit(Log.INFO, msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        emit(Log.WARN, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, msg) else Log.e(TAG, msg, t)
        emit(Log.ERROR, if (t == null) msg else "$msg: $t")
    }

    /** 采样限流：热路径上避免刷爆日志（每个 key 最多记 N 次） */
    private val counts = HashMap<String, Int>()

    fun sampled(key: String, limit: Int = 8, block: () -> String) {
        val n = synchronized(counts) {
            val c = (counts[key] ?: 0) + 1
            counts[key] = c
            c
        }
        if (n <= limit) {
            i("${block()} (sample $n/$limit)")
        }
    }
}
