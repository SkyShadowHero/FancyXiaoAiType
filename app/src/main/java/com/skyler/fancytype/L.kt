package com.skyler.fancytype

import android.util.Log

/** 结构化日志：每条都带 event= 便于 logcat 过滤与链路核对。 */
object L {
    const val TAG = "TypeMod"

    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String) = Log.w(TAG, msg)
    fun e(msg: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, msg) else Log.e(TAG, msg, t)
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
            Log.i(TAG, "${block()} (sample $n/$limit)")
        }
    }
}
