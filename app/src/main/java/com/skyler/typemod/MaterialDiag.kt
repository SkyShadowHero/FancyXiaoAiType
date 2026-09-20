package com.skyler.typemod

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 超级材质的「透明度」链路诊断与修正。
 *
 * 现象：圆角有了、但键盘背景仍是不透明的实色。
 *
 * 原因：`bb.b0` 的材质应用受离屏填充能力门控制（jadx 里叫 `z7.a.f18746a`），
 * 它等于 `SystemProperties["persist.sys.advanced_visual_release"] >= 6`。
 * 本机该属性为 5，于是：
 *   - `bb.b0` 只在门为真时才调 `xe.h.x(view, 65536)`（`setMiBlurWinType`），
 *     把窗口标记为可离屏填充；少了这一步，后设的模糊半径采不到背后内容，
 *     看起来就是实心；
 *   - 门为假时还会先刷一层实色背景（20ms 后才清）。
 *
 * 修正方式：在门被读取的那一刻把属性值说成 6，而不是反射改写 static final
 * —— 后者在 ART 上要绕 final 语义，各版本行为不一致。
 *
 * 注意：必须在**任何代码触碰到该门之前**装好这个 Hook，否则 `<clinit>` 已经算完，
 * 再改属性也没用。所以安装顺序是「先 Hook 属性，再做诊断」。
 *
 * 另外 jadx 输出里的 `f18746a` / `f18279a` 是它的重命名，不是 DEX 里的真名，
 * 因此这里一律用运行时枚举字段，不写死名字。
 */
object MaterialDiag {

    /** `z7.a` 读取的系统属性名 */
    const val PROP_ADVANCED_VISUAL = "persist.sys.advanced_visual_release"

    /** 强制上报的版本号：>= 6 才能让离屏填充门为真 */
    const val FORCED_ADVANCED_VISUAL = "6"

    @Volatile
    private var envLogged = false

    /** 把静态字段（含值）全部列出来，绕开 jadx 重命名带来的名字不确定性。 */
    private fun dumpStaticFields(cl: ClassLoader, className: String): String = try {
        val c = cl.loadClass(className)
        val parts = c.declaredFields
            .filter { Modifier.isStatic(it.modifiers) }
            .joinToString(" ") { f ->
                f.isAccessible = true
                val v = try {
                    short(f.get(null))
                } catch (t: Throwable) {
                    "err"
                }
                "${f.name}=$v"
            }
        if (parts.isEmpty()) "$className{无静态字段}" else "$className{$parts}"
    } catch (t: Throwable) {
        "$className{err:${t.javaClass.simpleName}}"
    }

    private fun shortAny(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.joinToString(",", "[", "]") { Integer.toHexString(it) }
        is FloatArray -> v.joinToString(",", "[", "]")
        else -> v.toString()
    }

    /**
     * 对象字段的一层展开：数组只给长度/十六进制，嵌套对象只给它的标量字段，
     * 避免把日志刷爆。
     */
    private fun short(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.joinToString(",", "[", "]") { Integer.toHexString(it) }
        is FloatArray -> v.joinToString(",", "[", "]")
        is String, is Number, is Boolean -> v.toString()
        else -> try {
            val nested = v.javaClass.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
                .joinToString(",", "(", ")") { f ->
                    f.isAccessible = true
                    val nv = try {
                        f.get(v)
                    } catch (t: Throwable) {
                        "err"
                    }
                    "${f.name}=${shortAny(nv)}"
                }
            "${v.javaClass.simpleName}$nested"
        } catch (t: Throwable) {
            v.javaClass.simpleName
        }
    }

    /** 只记一次：把决定材质能否透明的所有前置条件打出来。 */
    fun logEnvironment(cl: ClassLoader) {
        if (envLogged) return
        envLogged = true
        try {
            val prop = try {
                val sp = Class.forName("android.os.SystemProperties")
                sp.getMethod("get", String::class.java, String::class.java)
                    .invoke(null, PROP_ADVANCED_VISUAL, "") as? String
            } catch (t: Throwable) {
                "err"
            }
            L.i("event=material_env prop_$PROP_ADVANCED_VISUAL=$prop plus6=${(prop?.toIntOrNull() ?: -1) >= 6}")
            L.i("event=material_env_gate ${dumpStaticFields(cl, Target.CLS_ADVANCED_VISUAL_GATE)}")
            L.i("event=material_env_blur ${dumpStaticFields(cl, Target.CLS_BLUR_GATE)}")
        } catch (t: Throwable) {
            L.e("event=material_env_failed", t)
        }
    }

    /** 描述材质描述符：模糊模式 / 半径 / 混合色 / 圆角是否都在。 */
    fun describe(descriptor: Any?): String {
        if (descriptor == null) return "descriptor=null"
        return try {
            val fields: List<Field> = descriptor.javaClass.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
            val parts = fields.joinToString(" ") { f ->
                f.isAccessible = true
                val v = try {
                    f.get(descriptor)
                } catch (t: Throwable) {
                    "err"
                }
                "${f.name}=${short(v)}"
            }
            "descriptor{${descriptor.javaClass.simpleName} $parts}"
        } catch (t: Throwable) {
            "descriptor=err:${t.javaClass.simpleName}:${t.message}"
        }
    }
}
