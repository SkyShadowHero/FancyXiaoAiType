package com.skyler.fancytype

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 侧：连接 LSPosed 框架，拿到可读写远端配置。
 *
 * 生命周期要点（见 lsposed-mod-dev/knowledge/03）：
 * - registerListener 只调用一次；
 * - onServiceBind 可能被多次回调；
 * - 必须处理 onServiceDied。
 */
object RemoteConfig {

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var registered = false

    /** 服务就绪回调（主线程调用方用于触发配置重读） */
    @Volatile
    var onServiceReady: (() -> Unit)? = null

    fun init(context: Context) {
        if (registered) return
        registered = true
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(s: XposedService) {
                    service = s
                    L.i("event=service_bound framework=${s.frameworkName} version=${s.frameworkVersion}")
                    onServiceReady?.invoke()
                }

                override fun onServiceDied(s: XposedService) {
                    if (service === s) service = null
                    L.w("event=service_died")
                }
            })
        } catch (t: Throwable) {
            L.e("event=service_register_failed", t)
        }
    }

    /** 可写的远端 prefs；框架未就绪时返回 null（UI 需提示）。 */
    fun prefs(): SharedPreferences? = try {
        service?.getRemotePreferences(PrefKeys.GROUP)
    } catch (t: Throwable) {
        L.e("event=remote_prefs_failed", t)
        null
    }

    val isReady: Boolean get() = service != null

    /**
     * 供「重试连接」使用：清掉已缓存的服务引用，让下次检测重新走绑定流程。
     * 框架服务偶发绑定较慢或被系统回收，用户手动重试比让用户重启应用体验更好。
     */
    fun reset() {
        service = null
        L.i("event=service_reset")
    }

    /** 统一写入口，异常不外抛。 */
    fun edit(block: (SharedPreferences.Editor) -> Unit): Boolean = try {
        val p = prefs()
        if (p == null) {
            L.w("event=write_skipped reason=service_not_ready")
            false
        } else {
            val editor = p.edit()
            block(editor)
            editor.apply()
            true
        }
    } catch (t: Throwable) {
        L.e("event=write_failed", t)
        false
    }

    /**
     * 请求重启输入法：把计数器 +1 写入远端配置，Hook 侧下次键盘弹出时执行重启。
     */
    fun requestImeRestart(): Boolean = try {
        val p = prefs()
        if (p == null) {
            L.w("event=restart_skipped reason=service_not_ready")
            false
        } else {
            val next = p.getLong(PrefKeys.RESTART_SIGNAL, 0L) + 1L
            val editor = p.edit()
            editor.putLong(PrefKeys.RESTART_SIGNAL, next)
            editor.apply()
            L.i("event=restart_request_sent signal=$next")
            true
        }
    } catch (t: Throwable) {
        L.e("event=restart_request_failed", t)
        false
    }
}
