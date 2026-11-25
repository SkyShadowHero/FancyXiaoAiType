package io.github.skyshadowhero.fancypad.hooks

import io.github.skyshadowhero.fancypad.*

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 悬浮工具栏与候选窗口共用的真实阴影渲染器。
 *
 * Android elevation 的 spot shadow 会随窗口在屏幕上的位置改变，无法做出位置无关的
 * `14dp blur / 0dp X / 4dp Y / #191A1A1A`。这里给目标 Popup 增加一个同 token、
 * 不可触摸的透明伴随窗口，只画 [Paint.setShadowLayer]，与随手写工具栏使用同一配方。
 * 原 Popup 的资源阴影在统一模式下为 0，因此不会叠两层。
 */
internal object UnifiedFloatingShadow {

    enum class Kind { TOOLBAR, CANDIDATE }

    private data class Observation(
        val kind: Kind,
        val windowVisibility: ViewTreeObserver.OnWindowVisibilityChangeListener,
        val globalLayout: ViewTreeObserver.OnGlobalLayoutListener,
    )

    private data class Geometry(
        val contentWidth: Int,
        val contentHeight: Int,
        val x: Int,
        val y: Int,
        val gravity: Int,
        val type: Int,
        val token: android.os.IBinder,
        val padding: Int,
        val radius: Float,
    )

    private data class Record(
        val windowManager: WindowManager,
        val shadow: ShadowOnlyView,
        val params: WindowManager.LayoutParams,
        val originalElevation: Float,
        val originalAmbientColor: Int,
        val originalSpotColor: Int,
        var geometry: Geometry,
    )

    private data class ToolbarState(
        val originalElevation: Float,
        val originalAmbientColor: Int,
        val originalSpotColor: Int,
        val originalPaddingLeft: Int,
        val originalPaddingTop: Int,
        val originalPaddingRight: Int,
        val originalPaddingBottom: Int,
        var bitmap: Bitmap? = null,
        var contentWidth: Int = 0,
        var contentHeight: Int = 0,
        var padding: Int = 0,
        var radius: Float = -1f,
        var clipLogged: Boolean = false,
        var expanded: Boolean = false,
        var insetsListener: Any? = null,
        var insetsObserver: ViewTreeObserver? = null,
        var shadowDrawable: Drawable? = null,
    )

    private val observations = WeakHashMap<View, Observation>()
    private val records = WeakHashMap<View, Record>()
    private val toolbarStates = WeakHashMap<View, ToolbarState>()
    private val toolbarPositionProviders = WeakHashMap<Any, View>()
    private val pending = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val suppressed = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun isToolbarPositionProvider(provider: Any?): Boolean {
        if (provider?.javaClass?.name != "ja.a") return false
        return runCatching {
            val callbackField = provider.javaClass.declaredFields.first {
                it.type.name == "md.b"
            }.also { it.isAccessible = true }
            val callback = callbackField.get(provider) ?: return@runCatching false
            if (callback.javaClass.name != "fa.v2") return@runCatching false
            callback.javaClass.declaredFields.first {
                it.type == Int::class.javaPrimitiveType
            }.also { it.isAccessible = true }.getInt(callback) == MAIN_TOOLBAR_PROVIDER_TAG
        }.getOrDefault(false)
    }

    fun observeToolbar(host: View, positionProvider: Any) {
        toolbarPositionProviders[positionProvider] = host
        toolbarStates.getOrPut(host) {
            ToolbarState(
                originalElevation = host.elevation,
                originalAmbientColor = host.outlineAmbientShadowColor,
                originalSpotColor = host.outlineSpotShadowColor,
                originalPaddingLeft = host.paddingLeft,
                originalPaddingTop = host.paddingTop,
                originalPaddingRight = host.paddingRight,
                originalPaddingBottom = host.paddingBottom,
            )
        }
        observe(host, Kind.TOOLBAR)
        prepareToolbarSurface(host)
    }

    /**
     * 扩边后的 Popup 尺寸不能传给原定位器，否则靠近屏幕边缘时它会按阴影尺寸提前吸附。
     * 定位计算仍使用原内容尺寸，最终只把 Popup Surface 的左上角外移 [padding]。
     */
    fun adjustToolbarContentSize(positionProvider: Any?, packedSize: Long): Long {
        val host = positionProvider?.let(toolbarPositionProviders::get) ?: return packedSize
        if (!ConfigLoader.snapshot().floatBarUnifiedShadow) return packedSize
        val padding = toolbarStates[host]?.padding
            ?.takeIf { it > 0 }
            ?: shadowPadding(host.resources.displayMetrics.density)
        val width = ((packedSize shr 32).toInt() - padding * 2).coerceAtLeast(1)
        val height = (packedSize.toInt() - padding * 2).coerceAtLeast(1)
        return (width.toLong() shl 32) or (height.toLong() and 0xffffffffL)
    }

    fun adjustToolbarPosition(positionProvider: Any?, packedPosition: Long): Long {
        if (positionProvider == null) return packedPosition
        val host = toolbarPositionProviders[positionProvider] ?: return packedPosition
        if (!ConfigLoader.snapshot().floatBarUnifiedShadow) return packedPosition
        val density = host.resources.displayMetrics.density
        val padding = shadowPadding(density)
        val x = (packedPosition shr 32).toInt() - padding
        val y = packedPosition.toInt() - padding
        return (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    }

    /** WindowManagerImpl.updateViewLayout 的入参原地修正；不额外触发第二次 relayout。 */
    fun prepareToolbarLayoutParams(host: View?, rawParams: Any?) {
        val view = host ?: return
        val state = toolbarStates[view] ?: return
        val params = rawParams as? WindowManager.LayoutParams ?: return
        if (!ConfigLoader.snapshot().floatBarUnifiedShadow) {
            restoreToolbarSurface(view, state)
            return
        }
        prepareToolbarSurface(view)
        val group = view as? android.view.ViewGroup ?: return
        val child = group.getChildAt(0) ?: return
        if (child.measuredWidth <= 0 || child.measuredHeight <= 0) return
        params.width = child.measuredWidth + view.paddingLeft + view.paddingRight
        params.height = child.measuredHeight + view.paddingTop + view.paddingBottom
        state.padding = shadowPadding(view.resources.displayMetrics.density)
    }

    fun observeCandidate(host: View) = observe(host, Kind.CANDIDATE)

    fun refresh(host: View) {
        if (observations.containsKey(host)) schedule(host)
    }

    /** Popup 的位置 Hook 已在主线程内：直接同帧更新，不能 post 到下一帧。 */
    fun refreshImmediately(host: View) {
        if (!observations.containsKey(host)) return
        pending.remove(host)
        syncNow(host)
    }

    fun setHostSuppressed(host: View, value: Boolean) {
        if (value) suppressed.add(host) else suppressed.remove(host)
        records[host]?.shadow?.visibility =
            if (value || host.visibility != View.VISIBLE) View.INVISIBLE else View.VISIBLE
        if (toolbarStates.containsKey(host)) host.invalidate()
        if (!value) schedule(host)
    }

    fun hostDismissed(host: View) {
        pending.remove(host)
        removeShadow(host, restoreNative = false)
    }

    fun hostHidden(host: View) {
        pending.remove(host)
        records[host]?.shadow?.visibility = View.INVISIBLE
    }

    /**
     * 工具栏直接在自己的 Popup Surface 内画阴影。窗口移动时内容和阴影是同一个
     * SurfaceControl，不再存在两个 Window 分帧提交造成的拖动滞后。
     */
    fun drawToolbarShadow(host: View, canvas: Canvas) {
        if (observations[host]?.kind != Kind.TOOLBAR) return
        val state = toolbarStates[host] ?: return
        val enabled = ConfigLoader.snapshot().floatBarUnifiedShadow
        if (
            !enabled || suppressed.contains(host) ||
            host.visibility != View.VISIBLE || host.alpha <= 0f ||
            host.width <= 0 || host.height <= 0
        ) {
            if (!enabled) restoreToolbarSurface(host, state)
            return
        }
        prepareToolbarSurface(host)

        val density = host.resources.displayMetrics.density
        val blur = SHADOW_BLUR_DP * density
        val dy = SHADOW_DY_DP * density
        val padding = ceil(blur + abs(dy)).toInt()
        val radius = ConfigLoader.snapshot().floatBarCornerDp.coerceAtLeast(0f) * density
        val contentWidth = host.width - host.paddingLeft - host.paddingRight
        val contentHeight = host.height - host.paddingTop - host.paddingBottom
        if (contentWidth <= 0 || contentHeight <= 0) return
        var bitmap = state.bitmap
        if (
            bitmap == null || bitmap.isRecycled ||
            state.contentWidth != contentWidth || state.contentHeight != contentHeight ||
            state.padding != padding || state.radius != radius
        ) {
            bitmap?.recycle()
            bitmap = createShadowBitmap(contentWidth, contentHeight, padding, radius, blur, dy)
            state.bitmap = bitmap
            state.contentWidth = contentWidth
            state.contentHeight = contentHeight
            state.padding = padding
            state.radius = radius
        }
        if (!state.clipLogged) {
            state.clipLogged = true
            L.i(
                "event=unified_shadow_in_host host=${host.width}x${host.height} " +
                    "content=${contentWidth}x$contentHeight clip=${canvas.clipBounds} pad=$padding",
            )
        }
        canvas.drawBitmap(
            bitmap,
            state.originalPaddingLeft.toFloat(),
            state.originalPaddingTop.toFloat(),
            null,
        )
    }

    private fun observe(host: View, kind: Kind) {
        if (observations.containsKey(host)) {
            schedule(host)
            return
        }
        val windowVisibility = ViewTreeObserver.OnWindowVisibilityChangeListener { visibility ->
            if (visibility == View.VISIBLE) {
                schedule(host)
            } else {
                records[host]?.shadow?.visibility = View.INVISIBLE
            }
        }
        val globalLayout = ViewTreeObserver.OnGlobalLayoutListener {
            if (
                host.visibility != View.VISIBLE ||
                host.windowVisibility != View.VISIBLE ||
                host.alpha <= 0f
            ) {
                records[host]?.shadow?.visibility = View.INVISIBLE
            } else {
                schedule(host)
            }
        }
        observations[host] = Observation(kind, windowVisibility, globalLayout)
        host.viewTreeObserver.addOnWindowVisibilityChangeListener(windowVisibility)
        host.viewTreeObserver.addOnGlobalLayoutListener(globalLayout)
        // 不使用永久 OnPreDrawListener：它会让静止工具栏也每帧跨窗口 relayout，
        // 是拖动明显卡顿的根因。尺寸变化只在真正 layout 时同步。
        host.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                toolbarStates[view]?.shadowDrawable?.setBounds(0, 0, right - left, bottom - top)
                schedule(view)
            }
        }
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val observation = observations[v] ?: return
                if (v.viewTreeObserver.isAlive) {
                    runCatching {
                        v.viewTreeObserver.addOnWindowVisibilityChangeListener(observation.windowVisibility)
                    }
                    runCatching {
                        v.viewTreeObserver.addOnGlobalLayoutListener(observation.globalLayout)
                    }
                }
                schedule(v)
            }

            override fun onViewDetachedFromWindow(v: View) {
                removeShadow(v, restoreNative = false)
                pending.remove(v)
            }
        })
        schedule(host)
    }

    private fun schedule(host: View) {
        if (!pending.add(host)) return
        host.post {
            pending.remove(host)
            syncNow(host)
        }
    }

    private fun syncNow(host: View) {
        val observation = observations[host] ?: return
        val enabled = ConfigLoader.snapshot().floatBarUnifiedShadow
        if (observation.kind == Kind.TOOLBAR) {
            if (enabled) {
                prepareToolbarSurface(host)
            } else {
                toolbarStates[host]?.let { restoreToolbarSurface(host, it) }
            }
            host.invalidate()
            return
        }
        if (!enabled) {
            removeShadow(host, restoreNative = true)
            return
        }
        if (!host.isAttachedToWindow || host.width <= 0 || host.height <= 0) return

        val hidden = suppressed.contains(host) ||
            host.visibility != View.VISIBLE ||
            host.windowVisibility != View.VISIBLE ||
            host.alpha <= 0f
        val existing = records[host]
        if (hidden) {
            existing?.shadow?.visibility = View.INVISIBLE
            return
        }

        val base = hostWindowParams(host) ?: return
        val token = base.token ?: return
        val contentWidth = if (observation.kind == Kind.TOOLBAR && base.width > 0) {
            base.width
        } else {
            host.width
        }
        val contentHeight = if (observation.kind == Kind.TOOLBAR && base.height > 0) {
            base.height
        } else {
            host.height
        }
        if (contentWidth <= 0 || contentHeight <= 0) return
        if (
            observation.kind == Kind.TOOLBAR &&
            !looksLikeFloatingToolbar(host, contentWidth, contentHeight)
        ) {
            removeShadow(host, restoreNative = true)
            return
        }
        val density = host.resources.displayMetrics.density
        val blur = SHADOW_BLUR_DP * density
        val dy = SHADOW_DY_DP * density
        val padding = ceil(blur + abs(dy)).toInt()
        val radiusDp = ConfigLoader.snapshot().floatBarCornerDp.coerceAtLeast(0f)
        val radius = radiusDp * density
        val geometry = geometry(host, base, token, contentWidth, contentHeight, padding, radius)

        if (existing == null) {
            val wm = host.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            val shadow = ShadowOnlyView(host.context).apply {
                configure(contentWidth, contentHeight, padding, radius, blur, dy)
                background = ColorDrawable(Color.TRANSPARENT)
            }
            val params = shadowParams(base, geometry)
            val record = Record(
                windowManager = wm,
                shadow = shadow,
                params = params,
                originalElevation = host.elevation,
                originalAmbientColor = host.outlineAmbientShadowColor,
                originalSpotColor = host.outlineSpotShadowColor,
                geometry = geometry,
            )
            val added = runCatching {
                wm.addView(shadow, params)
                true
            }.onFailure {
                L.w("event=unified_shadow_add_failed kind=${observation.kind} msg=${it.message}")
            }.getOrDefault(false)
            if (!added) {
                // 自绘层失败时宁可退回原生阴影，也不能再次让菜单完全无阴影。
                host.elevation = PrefKeys.FLOATBAR_UNIFIED_SHADOW_DP * density
                host.outlineAmbientShadowColor = Color.BLACK
                host.outlineSpotShadowColor = Color.BLACK
                return
            }
            records[host] = record
            disableNativeHostShadow(host)
            L.i(
                "event=unified_shadow_attached kind=${observation.kind} " +
                    "host=${contentWidth}x$contentHeight x=${base.x} y=${base.y} pad=$padding"
            )
            return
        }

        val old = existing.geometry
        val sizeChanged = old.contentWidth != geometry.contentWidth ||
            old.contentHeight != geometry.contentHeight ||
            old.padding != geometry.padding ||
            old.radius != geometry.radius
        val layoutChanged = sizeChanged ||
            old.x != geometry.x || old.y != geometry.y ||
            old.gravity != geometry.gravity || old.type != geometry.type ||
            old.token !== geometry.token
        if (sizeChanged) {
            existing.shadow.configure(contentWidth, contentHeight, padding, radius, blur, dy)
        }
        if (existing.shadow.visibility != View.VISIBLE) existing.shadow.visibility = View.VISIBLE
        if (layoutChanged) {
            updateShadowParams(base, existing.params, geometry)
            runCatching { existing.windowManager.updateViewLayout(existing.shadow, existing.params) }
                .onFailure { L.w("event=unified_shadow_update_failed msg=${it.message}") }
            existing.geometry = geometry
        }
        disableNativeHostShadow(host)
    }

    private fun looksLikeFloatingToolbar(host: View, width: Int, height: Int): Boolean {
        val density = host.resources.displayMetrics.density
        val widthDp = width / density
        val heightDp = height / density
        val aspect = width.toFloat() / height
        val full = widthDp >= 120f && aspect >= 2f
        // 原生 mini 为 63×48dp，并会随展开态高度等比缩放；不能继续用“宽至少
        // 120dp / 宽高比至少 2”过滤，否则收起后自绘阴影会消失。
        val mini = widthDp >= 35f && aspect in 1.15f..1.5f
        return heightDp in 20f..180f && (full || mini)
    }

    /** w2.p 自己保存了一份实时 WindowManager.LayoutParams；优先取它，避免拖动时 View 上的是旧副本。 */
    private fun hostWindowParams(host: View): WindowManager.LayoutParams? {
        val own = runCatching {
            host.javaClass.methods.firstOrNull {
                it.parameterCount == 0 &&
                    it.name != "getLayoutParams" &&
                    it.returnType == WindowManager.LayoutParams::class.java
            }?.invoke(host) as? WindowManager.LayoutParams
        }.getOrNull()
        return own ?: host.layoutParams as? WindowManager.LayoutParams
    }

    private fun geometry(
        host: View,
        base: WindowManager.LayoutParams,
        token: android.os.IBinder,
        contentWidth: Int,
        contentHeight: Int,
        padding: Int,
        radius: Float,
    ): Geometry {
        val absoluteGravity = Gravity.getAbsoluteGravity(base.gravity, host.layoutDirection)
        val x = if (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT) {
            base.x + padding
        } else {
            base.x - padding
        }
        val y = if (base.gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.BOTTOM) {
            base.y + padding
        } else {
            base.y - padding
        }
        return Geometry(
            contentWidth,
            contentHeight,
            x,
            y,
            base.gravity,
            base.type,
            token,
            padding,
            radius,
        )
    }

    private fun shadowParams(
        base: WindowManager.LayoutParams,
        geometry: Geometry,
    ): WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        copyFrom(base)
        token = geometry.token
        format = PixelFormat.TRANSLUCENT
        flags = flags or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        flags = flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        flags = flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        windowAnimations = 0
        dimAmount = 0f
        alpha = 1f
        runCatching {
            val field = WindowManager.LayoutParams::class.java.getDeclaredField("surfaceInsets")
                .also { it.isAccessible = true }
            (field.get(this) as? Rect)?.setEmpty()
        }
        setTitle("FancyPadUnifiedShadow")
        updateShadowParams(base, this, geometry)
    }

    private fun updateShadowParams(
        base: WindowManager.LayoutParams,
        out: WindowManager.LayoutParams,
        geometry: Geometry,
    ) {
        out.width = geometry.contentWidth + geometry.padding * 2
        out.height = geometry.contentHeight + geometry.padding * 2
        out.gravity = geometry.gravity
        out.type = geometry.type
        out.token = geometry.token
        out.x = geometry.x
        out.y = geometry.y
        // j(...) 会重写宿主 flags；阴影层同步布局语义，但始终不可触摸、不可聚焦。
        out.flags = base.flags or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        out.flags = out.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        out.flags = out.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
    }

    private fun disableNativeHostShadow(host: View) {
        if (host.elevation != 0f) host.elevation = 0f
        if (host.outlineAmbientShadowColor != Color.TRANSPARENT) {
            host.outlineAmbientShadowColor = Color.TRANSPARENT
        }
        if (host.outlineSpotShadowColor != Color.TRANSPARENT) {
            host.outlineSpotShadowColor = Color.TRANSPARENT
        }
    }

    private fun prepareToolbarSurface(host: View) {
        if (!ConfigLoader.snapshot().floatBarUnifiedShadow) return
        val state = toolbarStates[host] ?: return
        val padding = shadowPadding(host.resources.displayMetrics.density)
        state.padding = padding
        if (!state.expanded) {
            state.expanded = true
            host.setPadding(
                state.originalPaddingLeft + padding,
                state.originalPaddingTop + padding,
                state.originalPaddingRight + padding,
                state.originalPaddingBottom + padding,
            )
            installContentTouchRegion(host, state)
            host.requestLayout()
        }
        if (state.shadowDrawable == null) {
            val drawable = ToolbarShadowDrawable(host).apply {
                setBounds(0, 0, host.width, host.height)
            }
            state.shadowDrawable = drawable
            host.overlay.add(drawable)
        }
        // Surface 已由真实 padding 扩大，不再依赖 elevation 的隐式 surfaceInsets。
        // 原生阴影必须完全关闭，避免和自绘配方叠加。
        if (host.elevation != 0f) host.elevation = 0f
        if (host.outlineAmbientShadowColor != Color.TRANSPARENT) {
            host.outlineAmbientShadowColor = Color.TRANSPARENT
        }
        if (host.outlineSpotShadowColor != Color.TRANSPARENT) {
            host.outlineSpotShadowColor = Color.TRANSPARENT
        }
    }

    private fun restoreToolbarSurface(host: View, state: ToolbarState) {
        if (state.expanded) {
            state.expanded = false
            removeContentTouchRegion(state)
            host.setPadding(
                state.originalPaddingLeft,
                state.originalPaddingTop,
                state.originalPaddingRight,
                state.originalPaddingBottom,
            )
            host.requestLayout()
        }
        state.shadowDrawable?.let(host.overlay::remove)
        state.shadowDrawable = null
        if (host.elevation != state.originalElevation) host.elevation = state.originalElevation
        if (host.outlineAmbientShadowColor != state.originalAmbientColor) {
            host.outlineAmbientShadowColor = state.originalAmbientColor
        }
        if (host.outlineSpotShadowColor != state.originalSpotColor) {
            host.outlineSpotShadowColor = state.originalSpotColor
        }
        state.bitmap?.recycle()
        state.bitmap = null
        state.contentWidth = 0
        state.contentHeight = 0
        state.padding = 0
        state.radius = -1f
        state.clipLogged = false
    }

    /**
     * Popup 扩出的透明阴影边不能变成新的触摸遮罩。Android 的公开 SDK 没暴露
     * OnComputeInternalInsetsListener，因此用运行时接口注册 touchable region；反射失败只会
     * 退化为普通 Popup 区域，不影响阴影与工具栏本体。
     */
    private fun installContentTouchRegion(host: View, state: ToolbarState) {
        if (state.insetsListener != null) return
        runCatching {
            val observer = host.viewTreeObserver
            val add = ViewTreeObserver::class.java.declaredMethods.first {
                it.name == "addOnComputeInternalInsetsListener" && it.parameterCount == 1
            }.also { it.isAccessible = true }
            val listenerType = add.parameterTypes[0]
            val listener = Proxy.newProxyInstance(
                listenerType.classLoader,
                arrayOf(listenerType),
            ) { proxy, method, args ->
                when (method.name) {
                    "onComputeInternalInsets" -> {
                        val info = args?.firstOrNull()
                        if (info != null) {
                            runCatching {
                                info.javaClass.getDeclaredMethod(
                                    "setTouchableInsets",
                                    Int::class.javaPrimitiveType,
                                ).also { it.isAccessible = true }.invoke(info, TOUCHABLE_INSETS_REGION)
                                val region = info.javaClass.getDeclaredField("touchableRegion")
                                    .also { it.isAccessible = true }
                                    .get(info) as? Region
                                region?.set(
                                    host.paddingLeft,
                                    host.paddingTop,
                                    (host.width - host.paddingRight).coerceAtLeast(host.paddingLeft),
                                    (host.height - host.paddingBottom).coerceAtLeast(host.paddingTop),
                                )
                            }
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "FancyPadToolbarInsetsListener"
                    else -> null
                }
            }
            add.invoke(observer, listener)
            state.insetsObserver = observer
            state.insetsListener = listener
        }.onFailure {
            L.w("event=toolbar_touch_region_install_failed msg=${it.message}")
        }
    }

    private fun removeContentTouchRegion(state: ToolbarState) {
        val observer = state.insetsObserver
        val listener = state.insetsListener
        if (observer != null && listener != null && observer.isAlive) {
            runCatching {
                ViewTreeObserver::class.java.declaredMethods.first {
                    it.name == "removeOnComputeInternalInsetsListener" && it.parameterCount == 1
                }.also { it.isAccessible = true }.invoke(observer, listener)
            }
        }
        state.insetsObserver = null
        state.insetsListener = null
    }

    private fun createShadowBitmap(
        contentWidth: Int,
        contentHeight: Int,
        padding: Int,
        radius: Float,
        blur: Float,
        dy: Float,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(
            contentWidth + padding * 2,
            contentHeight + padding * 2,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        val rect = RectF(
            padding.toFloat(),
            padding.toFloat(),
            (padding + contentWidth).toFloat(),
            (padding + contentHeight).toFloat(),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(blur, 0f, dy, SHADOW_COLOR)
        }
        canvas.drawRoundRect(rect, radius, radius, paint)
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        canvas.drawRoundRect(rect, radius, radius, clear)
        return bitmap
    }

    private fun removeShadow(host: View, restoreNative: Boolean) {
        val record = records.remove(host) ?: return
        runCatching { record.windowManager.removeViewImmediate(record.shadow) }
        if (restoreNative && host.isAttachedToWindow) {
            host.elevation = record.originalElevation
            host.outlineAmbientShadowColor = record.originalAmbientColor
            host.outlineSpotShadowColor = record.originalSpotColor
        }
    }

    private class ShadowOnlyView(context: Context) : View(context) {
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val rect = RectF()
        private var radius = 0f

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            isClickable = false
            isFocusable = false
        }

        fun configure(
            contentWidth: Int,
            contentHeight: Int,
            padding: Int,
            radius: Float,
            blur: Float,
            dy: Float,
        ) {
            rect.set(
                padding.toFloat(),
                padding.toFloat(),
                (padding + contentWidth).toFloat(),
                (padding + contentHeight).toFloat(),
            )
            this.radius = radius
            shadowPaint.setShadowLayer(blur, 0f, dy, SHADOW_COLOR)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRoundRect(rect, radius, radius, shadowPaint)
            canvas.drawRoundRect(rect, radius, radius, clearPaint)
            canvas.restoreToCount(layer)
        }
    }

    /** Overlay 与宿主 View 共用同一 Surface；透明中心允许它在内容之后绘制而不遮挡按钮。 */
    private class ToolbarShadowDrawable(
        private val host: View,
    ) : Drawable() {
        override fun draw(canvas: Canvas) = drawToolbarShadow(host, canvas)
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private const val SHADOW_BLUR_DP = 14f
    private const val SHADOW_DY_DP = 4f
    private const val SHADOW_COLOR = 0x191A1A1A
    private const val MAIN_TOOLBAR_PROVIDER_TAG = 12
    private const val TOUCHABLE_INSETS_REGION = 3

    private fun shadowPadding(density: Float): Int =
        ceil((SHADOW_BLUR_DP + abs(SHADOW_DY_DP)) * density).toInt()
}
