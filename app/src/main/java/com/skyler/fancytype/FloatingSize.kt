package com.skyler.fancytype

import android.content.SharedPreferences
import android.os.SystemClock

/**
 * 悬浮键盘最大尺寸解锁。
 *
 * ## 输入法原本的限制
 *
 * 悬浮键盘（平板上可拖动、可缩放的触屏键盘）不按像素定尺寸，而是按
 * **自然宽度的倍率**布局。输入法把这个倍率夹在 `0.65 ~ 1.1`，窗口宽度同样
 * 夹在 `0.65 × 自然宽度 ~ 1.1 × 自然宽度`。三处夹取各管一段：
 *
 * | 时机 | 夹的是什么 | 谁在做 |
 * |---|---|---|
 * | 拖动中 | 窗口宽度（int） | FloatingResizeController 的 resize 回调 |
 * | 松手提交 | 缩放倍率（float） | 同一控制器的 endResize |
 * | 显示 / 恢复 | 两者都有 | FloatingKeyboardManager |
 *
 * 所以平板上最大只能到自然尺寸的 110%，看着很小。三处走的是**同一个**夹取
 * 工具方法（Kotlin `coerceIn` 的实现），因此只接管那一个方法就能全部生效。
 *
 * 高度不必单独处理：输入法的窗口高度是由宽度按同一倍率推出来的。
 *
 * ## 为什么值缓存在这里
 *
 * 那个夹取方法在输入法里有 190 多处调用，属于热点路径 —— 每次调用都去读
 * 40 多项 SharedPreferences 太贵。这里缓存成一个 volatile，按 [TTL_MS] 节流
 * 刷新：既便宜，又能在一秒内跟上设置页的改动，不依赖监听回调一定送达。
 */
object FloatingSize {

    /** 缓存有效期。设置改动最迟一秒后生效。 */
    private const val TTL_MS = 1000L

    /** 缓存的倍率上限；等于原生上限即表示未解锁 */
    @Volatile
    private var cached = PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE

    @Volatile
    private var lastReadMs = 0L

    /**
     * 当前应当使用的倍率上限。未解锁时就是输入法自己的 1.1。
     *
     * 这个 getter 会在夹取方法的热点路径上被调用，所以只做一次 volatile 读
     * 加一次时间比较，过期才去碰 SharedPreferences。
     */
    val maxScale: Float
        get() {
            val now = SystemClock.uptimeMillis()
            if (now - lastReadMs >= TTL_MS) {
                lastReadMs = now
                refresh(ConfigLoader.raw)
            }
            return cached
        }

    /** 是否处于解锁状态（上限真的被抬高了） */
    val unlocked: Boolean get() = maxScale > PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE

    /**
     * 立即按给定 prefs 刷新缓存。挂上远端配置时、以及配置变更回调里都会调。
     *
     * 这里刻意不用 `coerceIn` —— 它正是被本功能 hook 的方法，
     * 虽然特征不匹配不会递归，但没必要在这里绕一圈，手工夹更清楚。
     */
    fun refresh(p: SharedPreferences?) {
        if (p == null) {
            cached = PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE
            return
        }
        val on = p.runCatching { getBoolean(PrefKeys.FLOAT_KB_UNLOCK, false) }.getOrDefault(false)
        if (!on) {
            cached = PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE
            return
        }
        val v = p.runCatching {
            getFloat(PrefKeys.FLOAT_KB_MAX_SCALE, PrefKeys.FLOAT_KB_MAX_SCALE_DEFAULT)
        }.getOrDefault(PrefKeys.FLOAT_KB_MAX_SCALE_DEFAULT)
        cached = when {
            v < PrefKeys.FLOAT_KB_MAX_SCALE_MIN -> PrefKeys.FLOAT_KB_MAX_SCALE_MIN
            v > PrefKeys.FLOAT_KB_MAX_SCALE_MAX -> PrefKeys.FLOAT_KB_MAX_SCALE_MAX
            else -> v
        }
    }
}
