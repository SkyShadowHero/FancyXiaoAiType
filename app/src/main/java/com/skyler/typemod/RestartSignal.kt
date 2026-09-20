package com.skyler.typemod

import android.os.Process
import java.util.concurrent.atomic.AtomicLong

/**
 * 重启输入法的 Hook 侧执行者。
 *
 * 设置页写入 PrefKeys.RESTART_SIGNAL（自增计数），本进程在输入法生命周期点轮询；
 * 发现比基线大就自杀，系统随后重新拉起输入法 —— 等效于「直接杀掉 com.xiaomi.type」。
 *
 * 用轮询而非监听器：监听器由 Binder 线程触发，任意时刻杀进程不可控。
 * 注意：只有输入法进程自己能杀自己（模块 App 无权限杀别的进程），所以必须走这条路径。
 */
object RestartSignal {

    /** 已处理过的信号值。启动时用当前配置值做基线（见 [initBaseline]）。 */
    private val lastSeen = AtomicLong(0L)

    /**
     * 安装钩子时调用：把配置里当前的值记为基线。
     * 不做这一步的话，配置里残留的旧信号会让每个新进程一启动就立刻自杀（实测成死循环，
     * 输入法反复重启、键盘无法使用）。
     */
    fun initBaseline() {
        val current = ConfigLoader.readLong(PrefKeys.RESTART_SIGNAL, 0L)
        lastSeen.set(current)
        L.i("event=restart_baseline signal=$current")
    }

    fun poll() {
        val signal = ConfigLoader.readLong(PrefKeys.RESTART_SIGNAL, 0L)
        if (signal <= 0L) return

        val prev = lastSeen.get()
        if (signal <= prev) return
        if (!lastSeen.compareAndSet(prev, signal)) return

        L.w("event=restart_executing signal=$signal prev=$prev -> killing ime process")
        try {
            Process.killProcess(Process.myPid())
        } catch (t: Throwable) {
            L.e("event=restart_kill_failed", t)
            try {
                System.exit(0)
            } catch (_: Throwable) {
            }
        }
    }
}
