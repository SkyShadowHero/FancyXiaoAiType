package com.skyler.fancytype

import android.content.Context
import android.content.res.Configuration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 功能 2：竖屏强制普通键盘。
 *
 * 仅在开关打开、且原值为 true（用户确实开着分离键盘）时才压为 false，
 * 保证「用户自己关掉分离键盘」的默认语义不被破坏。
 */
object PortraitGuard {

    private val loggedUnavailable = AtomicBoolean(false)

    fun apply(originalEnabled: Boolean): Boolean {
        if (!originalEnabled) return false
        if (!ConfigLoader.snapshot().portraitForceNormal) return true
        return isLandscape()
    }

    fun isLandscape(): Boolean {
        val ctx = application() ?: return true // 取不到上下文时不干预，避免误关分离键盘
        return try {
            ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        } catch (t: Throwable) {
            L.e("event=orientation_failed", t)
            true
        }
    }

    /** ActivityThread 属 hidden API，用反射取当前 Application，避免编译期依赖。 */
    private fun application(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    } catch (t: Throwable) {
        if (loggedUnavailable.compareAndSet(false, true)) {
            L.w("event=application_unavailable (orientation 判定降级)")
        }
        null
    }
}
