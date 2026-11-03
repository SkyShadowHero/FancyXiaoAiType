package io.github.skyshadowhero.fancypad

import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import io.github.libxposed.service.HookedTarget
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
        // 补写 service 未就绪期间被排队的设置
        runCatching {
            val p = prefs() ?: return@runCatching
            while (pendingWrites.isNotEmpty()) {
                val block = pendingWrites.removeAt(0)
                val e = p.edit()
                block(e)
                e.apply()
            }
        }
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
     * 当前绑定的框架服务对象。
     *
     * 光标页需要它做 Remote Files 的读写（`listRemoteFiles` / `openRemoteFile` /
     * `deleteRemoteFile`）——那些能力只在 `libxposed:service` 的 [XposedService] 上，
     * 不在 RemotePreferences 里。三个功能域共用这一个连接，不再各自注册监听器。
     */
    fun service(): XposedService? = service

    // ---------------------------------------------------------- 框架 / 作用域

    /** 框架 API 版本（101 / 102…）；未连接返回 -1。 */
    fun apiVersion(): Int = runCatching { service?.apiVersion ?: -1 }.getOrDefault(-1)

    /** 框架名（如 LSPosed）；未连接返回 null。 */
    fun frameworkName(): String? = runCatching { service?.frameworkName }.getOrNull()

    /**
     * 当前 LSPosed 作用域（包名集合）。
     *
     * 首页卡片与「功能」页置灰都读它：作用域里没有目标包，注入根本不会发生，
     * 那个域即使开关打开也不会生效。未连接时返回空集合（全部按「未加入」处理）。
     */
    fun scope(): Set<String> = runCatching {
        service?.scope?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    /**
     * 正在被注入的进程（API 102 起）。用来区分「已生效」与「开了但进程还没重启」。
     */
    fun runningTargets(): List<HookedTarget> = runCatching {
        val s = service ?: return emptyList()
        if (s.apiVersion < XposedService.API_102) emptyList() else s.runningTargets
    }.getOrDefault(emptyList())

    /** Remote Files 列表；未连接或出错时返回空列表。 */
    fun listRemoteFiles(): List<String> = try {
        service?.listRemoteFiles()?.toList() ?: emptyList()
    } catch (t: Throwable) {
        L.e("event=list_remote_files_failed", t)
        emptyList()
    }

    /** 读一个 Remote File；不存在返回 null。 */
    fun openRemoteFile(name: String): ParcelFileDescriptor? = try {
        service?.openRemoteFile(name)
    } catch (t: Throwable) {
        null
    }

    /** 删除一个 Remote File；未连接时忽略。 */
    fun deleteRemoteFile(name: String) {
        try {
            service?.deleteRemoteFile(name)
        } catch (t: Throwable) {
            L.e("event=delete_remote_file_failed", t)
        }
    }

    /**
     * 供「重试连接」使用：清掉已缓存的服务引用，让下次检测重新走绑定流程。
     * 框架服务偶发绑定较慢或被系统回收，用户手动重试比让用户重启应用体验更好。
     */
    fun reset() {
        service = null
        L.i("event=service_reset")
    }

    /** 统一写入口，异常不外抛。 */
    /** service 未就绪时把写入排队，绑定成功后补写，避免「开关自己关掉」。 */
    private val pendingWrites = java.util.concurrent.CopyOnWriteArrayList<(android.content.SharedPreferences.Editor) -> Unit>()

    fun edit(block: (SharedPreferences.Editor) -> Unit): Boolean = try {
        val p = prefs()
        if (p == null) {
            L.w("event=write_queued reason=service_not_ready")
            if (pendingWrites.size < 64) pendingWrites.add(block)
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
}
