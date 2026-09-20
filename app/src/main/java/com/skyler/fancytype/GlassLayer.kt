package com.skyler.fancytype

import android.view.View
import android.view.ViewTreeObserver
import java.lang.reflect.Method
import kotlin.math.ceil

/**
 * 自建 SurfaceFlinger 特效层，用来做真正的「键盘背景模糊」。
 *
 * ## 为什么需要自己建
 *
 * MIUI 的应用层材质（`bb.b0` + `xe.b`）走的是「透过窗口模糊」，而宿主窗口能否被穿透
 * 由 system_server 侧云端下发的 `PassWindowBlurFilterData` 名单决定 —— 实测
 * `isPassWindowBlurWhitelisted("com.android.quicksearchbox") = true`，其它应用一律 false。
 * 所以在这条链路上，非白名单应用永远糊不起来。
 *
 * `SurfaceControl.Transaction.setBackgroundBlurRadius()` 不一样：它是 SurfaceFlinger 级的
 * 能力，糊的是合成器里该 surface **背后的内容**，跨窗口，完全不经过 MIUI 那张名单。
 * 做法就是在输入法窗口的 SurfaceControl 下挂一个带 effect-layer 标记的子 surface：
 *
 * ```
 * SurfaceControl.Builder().setName(...).setEffectLayer().setParent(imeWindowSurface).build()
 * Transaction().reparent/setLayer(-1)/setPosition/setWindowCrop/setCornerRadius
 *             .setBackgroundBlurRadius(px).show().apply()
 * ```
 *
 * 思路来自 XiaoAiTypeUnblock（GPL-3.0）的 `KeyboardStyleHook.ensureDynamicGlassSurfacePrimer`。
 *
 * ## 为什么全是反射
 *
 * `setEffectLayer` / `setBackgroundBlurRadius` / `ViewRootImpl.mSurfaceControl` 在公开 SDK 里
 * 要么是 @hide，要么根本不存在。模块注入在输入法进程内，LSPosed 已解除该进程的隐藏 API 限制，
 * 所以反射可用；任何一步失败都直接降级回原厂材质，不能把输入法搞崩。
 */
object GlassLayer {

    private const val PRIMER_NAME = "FancyTypeGlass"
    private const val BLUR_LIMIT = 400

    private val surfaceClass: Class<*>? =
        runCatching { Class.forName("android.view.SurfaceControl") }.getOrNull()
    private val txClass: Class<*>? =
        runCatching { Class.forName("android.view.SurfaceControl\$Transaction") }.getOrNull()
    private val builderClass: Class<*>? =
        runCatching { Class.forName("android.view.SurfaceControl\$Builder") }.getOrNull()

    private val intPrim = Int::class.javaPrimitiveType
    private val floatPrim = Float::class.javaPrimitiveType
    private val boolPrim = Boolean::class.javaPrimitiveType

    /** null = 还没试过 */
    @Volatile
    private var supported: Boolean? = null

    @Volatile
    private var primer: Any? = null

    @Volatile
    private var primerParent: Any? = null

    @Volatile
    private var tracked: View? = null

    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    @Volatile
    private var lastGeometry: Geometry? = null

    private data class Geometry(val x: Int, val y: Int, val w: Int, val h: Int, val shown: Boolean)

    @Volatile
    private var diagLogged = 0

    private fun diag(msg: String) {
        if (diagLogged < 12) {
            diagLogged++
            L.i("event=glass_layer $msg")
        }
    }

    private fun method(cls: Class<*>?, name: String, vararg types: Class<*>?): Method? {
        if (cls == null) return null
        val resolved = ArrayList<Class<*>>(types.size)
        for (t in types) resolved.add(t ?: return null)
        return try {
            cls.getDeclaredMethod(name, *resolved.toTypedArray()).also { it.isAccessible = true }
        } catch (t: Throwable) {
            null
        }
    }

    /** 沿继承链找字段：`ViewRootImpl.mSurfaceControl` 不一定声明在最外层。 */
    private fun field(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (t: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    private fun call(target: Any?, m: Method?, vararg args: Any?): Any? {
        if (target == null || m == null) return null
        return try {
            m.invoke(target, *args)
        } catch (t: Throwable) {
            null
        }
    }

    /** 输入法窗口自己的 SurfaceControl（挂在它下面才有「背后」可言）。 */
    private fun windowSurface(decor: View): Any? = try {
        val getVri = View::class.java.getDeclaredMethod("getViewRootImpl").also { it.isAccessible = true }
        val vri = getVri.invoke(decor) ?: return null
        field(vri, "mSurfaceControl")
    } catch (t: Throwable) {
        null
    }

    private fun isValid(sc: Any?): Boolean {
        if (sc == null) return false
        val m = method(surfaceClass, "isValid") ?: return true
        return call(sc, m) == true
    }

    /**
     * 让特效层跟随 `material`（键盘材质层）的位置与尺寸。
     *
     * @return 是否成功挂上
     */
    fun sync(material: View, blurDp: Float, cornerDp: Float): Boolean {
        if (!ConfigLoader.snapshot().materialEnabled) {
            detach("material_disabled")
            return false
        }
        if (material.width <= 0 || material.height <= 0 || !material.isAttachedToWindow) {
            diag("skip not_ready w=${material.width} h=${material.height} attached=${material.isAttachedToWindow}")
            return false
        }
        val decor = material.rootView ?: return false
        val parentSurface = windowSurface(decor)
        if (!isValid(parentSurface)) {
            if (supported == null) {
                supported = false
                L.w("event=glass_layer unsupported reason=no_window_surface")
            }
            return false
        }

        val ensured = ensurePrimer(parentSurface) ?: return false
        val ok = applyGeometry(ensured, parentSurface, material, decor, blurDp, cornerDp)
        if (ok) {
            supported = true
            trackGeometry(material)
        }
        return ok
    }

    /** 建立（或在窗口 surface 变化后重建）特效层。 */
    private fun ensurePrimer(parentSurface: Any?): Any? {
        val existing = primer
        if (existing != null && primerParent === parentSurface && isValid(existing)) {
            return existing
        }
        detach("recreate")

        val builder = try {
            builderClass?.getDeclaredConstructor()?.also { it.isAccessible = true }?.newInstance()
        } catch (t: Throwable) {
            null
        } ?: return null

        call(builder, method(builderClass, "setName", String::class.java), PRIMER_NAME)
        // 关键：声明为 effect layer，setBackgroundBlurRadius 才会被 SurfaceFlinger 接受
        call(builder, method(builderClass, "setEffectLayer"))
        call(builder, method(builderClass, "setParent", surfaceClass), parentSurface)

        val built = call(builder, method(builderClass, "build"))
        if (built == null) {
            L.w("event=glass_layer build_failed")
            return null
        }
        primer = built
        primerParent = parentSurface
        lastGeometry = null
        diag("primer_created")
        return built
    }

    private fun applyGeometry(
        sc: Any,
        parentSurface: Any?,
        material: View,
        decor: View,
        blurDp: Float,
        cornerDp: Float,
    ): Boolean {
        val transaction = try {
            txClass?.getDeclaredConstructor()?.also { it.isAccessible = true }?.newInstance()
        } catch (t: Throwable) {
            null
        } ?: return false

        return try {
            val loc = IntArray(2)
            material.getLocationInWindow(loc)
            val density = material.resources.displayMetrics.density
            val cornerPx = (cornerDp * density).coerceAtLeast(0f)

            // 停靠键盘只圆上面两个角：把下边缘多裁掉一个圆角半径，
            // 下方圆角就落在窗口外看不见了。
            val docked = loc[1] + material.height >= decor.height
            val cropHeight = material.height + if (docked) ceil(cornerPx.toDouble()).toInt() else 0

            call(transaction, method(txClass, "reparent", surfaceClass, surfaceClass), sc, parentSurface)
            call(transaction, method(txClass, "setLayer", surfaceClass, intPrim), sc, -1)
            call(
                transaction,
                method(txClass, "setPosition", surfaceClass, floatPrim, floatPrim),
                sc, loc[0].toFloat(), loc[1].toFloat(),
            )
            call(
                transaction,
                method(txClass, "setWindowCrop", surfaceClass, intPrim, intPrim),
                sc, material.width, cropHeight,
            )
            call(transaction, method(txClass, "setCornerRadius", surfaceClass, floatPrim), sc, cornerPx)

            val blurPx = (blurDp * density + 0.5f).toInt().coerceIn(0, BLUR_LIMIT)
            call(
                transaction,
                method(txClass, "setBackgroundBlurRadius", surfaceClass, intPrim),
                sc, blurPx,
            )

            val shown = material.isShown
            call(transaction, method(txClass, if (shown) "show" else "hide", surfaceClass), sc)

            val apply = method(txClass, "apply")
            call(transaction, apply)

            val geo = Geometry(loc[0], loc[1], material.width, material.height, shown)
            if (geo != lastGeometry) {
                lastGeometry = geo
                diag(
                    "geometry ${material.width}x$cropHeight@${loc[0]},${loc[1]} " +
                        "blur=${blurPx}px(${blurDp}dp) corner=${cornerPx}px shown=$shown docked=$docked"
                )
            }
            (transaction as? AutoCloseable)?.close()
            true
        } catch (t: Throwable) {
            L.e("event=glass_layer apply_failed", t)
            runCatching { (transaction as? AutoCloseable)?.close() }
            detach("apply_failed")
            false
        }
    }

    /**
     * 键盘尺寸/位置会随布局、转屏、浮窗切换变化，靠 pre-draw 保持跟随。
     * 只在几何真的变了时才发事务，避免每帧都提交。
     */
    private fun trackGeometry(material: View) {
        if (tracked === material && preDrawListener != null) return
        stopTracking()
        val observer = material.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            val cfg = ConfigLoader.snapshot()
            if (!cfg.materialEnabled || material.width <= 0 || !material.isAttachedToWindow) {
                detach("pre_draw_gone")
            } else {
                val loc = IntArray(2)
                material.getLocationInWindow(loc)
                val geo = Geometry(loc[0], loc[1], material.width, material.height, material.isShown)
                if (geo != lastGeometry) {
                    val decor = material.rootView
                    if (decor != null) {
                        applyGeometry(
                            primer ?: return@OnPreDrawListener true,
                            primerParent, material, decor,
                            cfg.materialBlurDp, cfg.materialCornerDp,
                        )
                    }
                }
            }
            true
        }
        observer.addOnPreDrawListener(listener)
        preDrawListener = listener
        tracked = material
    }

    private fun stopTracking() {
        val listener = preDrawListener
        val view = tracked
        if (listener != null && view != null) {
            runCatching { view.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        preDrawListener = null
        tracked = null
    }

    /** 移除特效层。 */
    fun detach(reason: String) {
        stopTracking()
        lastGeometry = null
        val sc = primer
        primer = null
        primerParent = null
        if (sc == null) return
        diag("detach reason=$reason")
        try {
            val transaction = txClass?.getDeclaredConstructor()?.also { it.isAccessible = true }?.newInstance()
            call(transaction, method(txClass, "remove", surfaceClass), sc)
            call(transaction, method(txClass, "apply"))
            (transaction as? AutoCloseable)?.close()
            call(sc, method(surfaceClass, "release"))
        } catch (t: Throwable) {
            // 清理失败只影响下一次重建，不抛
        }
    }
}
