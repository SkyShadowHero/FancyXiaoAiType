package io.github.skyshadowhero.fancypad

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import kotlin.concurrent.thread

/**
 * ONNX Runtime 自检页（调试用）—— 为「换更好的识别模型」做可行性验证。
 *
 * 为什么要这个页面：模型功能依赖两件在真机上不一定成立的事，
 * 而本机 logcat 缓冲区是死的（`logcat -d` 只有几个月前的旧行）、模块日志也进不去 LSPosed 日志，
 * 所以只能把结论**落盘**，再由 root 侧 `am start` 拉起来、`cat` 结果：
 *
 * ```bash
 * su -c 'am start -n io.github.skyshadowhero.fancypad/.OrtSelfTest'
 * su -c 'cat /data/data/io.github.skyshadowhero.fancypad/files/ort_selftest.txt'
 * ```
 *
 * 两件事：
 * 1. **ONNX Runtime 能不能在本机用起来**。APK 里只有 `ai.onnxruntime` 的 Java 绑定
 *    （AAR 的 native 库被 packaging 排除了），运行时靠系统自带的
 *    `/system_ext/lib64/libonnxruntime.so` + `libonnxruntime4j_jni.so`（同为 1.15.1）。
 *    这一步会打印版本与可用 provider，验证 JNI 是否真能挂上。
 * 2. **模型吃什么**。把 `.onnx` 丢进 `files/models/` 再跑一次，就会打印每个模型的
 *    输入/输出张量名字、类型、形状，并用全零输入真跑一遍推理 ——
 *    这些规格就是写预处理/解码所需要的全部信息。
 *
 * 页面本身不导出（和其他自检页一致）：它会加载模型跑推理，不该让任何应用随手拉起来。
 * root uid 在组件权限检查里是放行的，`su -c am start` 起得来。
 */
class OrtSelfTest : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.BLACK)
            setText("ONNX Runtime 自检中…")
        }
        setContentView(ScrollView(this).apply { addView(text) })

        thread(name = "ort-selftest") {
            val report = runSelfTest(this)
            L.i("event=ort_selftest_done\n$report")
            runOnUiThread { text.text = report }
        }
    }

    companion object {

        /** 报告文件名（App 私有目录内）。 */
        const val FILE_NAME = "ort_selftest.txt"

        /** 模型存放目录：`files/models/` 下的 `.onnx`。 */
        fun modelsDir(ctx: Context): File = File(ctx.filesDir, "models")

        /** 探针：按**绝对路径**加载系统里的库（namespace 是否放行）。 */
        private fun probeAbsolute(path: String, emit: (String) -> Unit) {
            val r = try {
                System.load(path)
                "✓ 可以"
            } catch (t: Throwable) {
                "✗ ${t.toString().take(160)}"
            }
            emit("  System.load($path)")
            emit("    $r")
        }

        /** 探针：把系统里的库**拷到自己数据目录**再加载（自带运行时的备选方案）。 */
        private fun probeCopyAndLoad(dir: File, src: String, emit: (String) -> Unit) {
            try {
                dir.mkdirs()
                val dst = File(dir, File(src).name)
                File(src).inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                val r = try {
                    System.load(dst.absolutePath)
                    "✓ 可以"
                } catch (t: Throwable) {
                    "✗ ${t.toString().take(160)}"
                }
                emit("  拷贝到 ${dst.absolutePath}（${dst.length()} 字节）后 System.load")
                emit("    $r")
            } catch (t: Throwable) {
                emit("  拷贝失败：${t.toString().take(160)}")
            }
        }

        /**
         * 自检主流程，返回可读报告（同时逐步落盘 —— 卡住时也能看出走到哪一步）。
         */
        fun runSelfTest(ctx: Context): String {
            val file = File(ctx.filesDir, FILE_NAME)
            val sb = StringBuilder()
            fun emit(line: String) {
                sb.append(line).append('\n')
                runCatching { file.writeText(sb.toString()) }
            }

            emit("pkg   = ${ctx.packageName}")
            emit("abi   = ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            emit("----")

            // ---- 0) native 库加载能力（决定"能不能自带运行时"）----
            //
            // 已知：**不能**指望系统里那些私有推理库。实测 `System.loadLibrary("onnxruntime4j_jni")`
            // 会报 `dlopen failed: library "/system_ext/lib64/libonnxruntime4j_jni.so" ...
            // is not accessible for the namespace "clns-10"` —— 它们不在 public.libraries.txt 里，
            // 普通应用的 linker namespace 根本看不到。（注意：Termux 里用 ctypes 试是**假阳性**，
            // Termux 的 namespace 和普通应用不同。）
            //
            // 所以只剩"把 .so 打进 APK、或拷到自己数据目录再 System.load"这条路，这里直接验证它。
            emit("[0] native 加载能力探针")
            probeAbsolute("/system_ext/lib64/libtflite_interface_wrap.touch.so") { emit(it) }
            probeCopyAndLoad(File(ctx.filesDir, "probe"), "/system/lib64/libtflite.so") { emit(it) }
            emit("----")

            // ---- 1) ONNX Runtime 本体 ----
            emit("[1] ONNX Runtime")
            val env = try {
                val e = OrtEnvironment.getEnvironment()
                emit("  ✓ OrtEnvironment 创建成功")
                // getVersion() 是实例方法；getAvailableProviders() 是静态方法（javap 核对过）
                emit("  version   = ${e.version}")
                emit("  providers = ${OrtEnvironment.getAvailableProviders().joinToString()}")
                e
            } catch (t: Throwable) {
                emit("  ✗ 失败：$t")
                emit("  （native 库由系统提供：/system_ext/lib64/libonnxruntime4j_jni.so）")
                emit("---- 结束：运行时不可用，模型这条路走不通")
                return sb.toString()
            }

            // ---- 2) 模型规格 ----
            val dir = modelsDir(ctx)
            emit("----")
            emit("[2] 模型规格（目录 $dir）")
            if (!dir.isDirectory) {
                emit("  （目录不存在；先 mkdir 并放入 .onnx）")
                return sb.toString()
            }
            val models = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }
                ?.sortedBy { it.name }.orEmpty()
            if (models.isEmpty()) {
                emit("  （目录里没有 .onnx）")
                return sb.toString()
            }

            for (m in models) {
                emit("")
                emit("  ● ${m.name}  (${m.length()} 字节 / ${m.length() / 1024 / 1024} MB)")
                inspectModel(env, m, ::emit)
            }
            emit("---- done")
            return sb.toString()
        }

        /** 打印一个模型的输入/输出规格，并用全零输入真跑一遍。 */
        private fun inspectModel(env: OrtEnvironment, file: File, emit: (String) -> Unit) {
            var session: OrtSession? = null
            try {
                session = env.createSession(file.absolutePath, OrtSession.SessionOptions())
                emit("    inputs:")
                for ((name, info) in session.inputInfo) {
                    emit("      $name  ${describe(info.info)}")
                }
                emit("    outputs:")
                for ((name, info) in session.outputInfo) {
                    emit("      $name  ${describe(info.info)}")
                }

                // 用全零输入跑一遍：只为验证"能推理"，结果无意义
                val inputs = HashMap<String, OnnxTensor>()
                try {
                    for ((name, info) in session.inputInfo) {
                        val ti = info.info as? TensorInfo ?: continue
                        // 动态维度（-1）一律按 1 处理
                        val shape = ti.shape.map { if (it <= 0) 1L else it }.toLongArray()
                        val count = shape.fold(1L) { a, b -> a * b }.toInt()
                        when (ti.type) {
                            ai.onnxruntime.OnnxJavaType.FLOAT ->
                                inputs[name] = OnnxTensor.createTensor(
                                    env, FloatBuffer.allocate(count), shape,
                                )
                            ai.onnxruntime.OnnxJavaType.INT64 ->
                                inputs[name] = OnnxTensor.createTensor(
                                    env, java.nio.LongBuffer.allocate(count), shape,
                                )
                            ai.onnxruntime.OnnxJavaType.INT32 ->
                                inputs[name] = OnnxTensor.createTensor(
                                    env, java.nio.IntBuffer.allocate(count), shape,
                                )
                            else -> emit("      （跳过推理：${ti.type} 暂未构造输入）")
                        }
                    }
                    if (inputs.isEmpty()) {
                        emit("    ✗ 没有可构造的输入，跳过推理")
                        return
                    }
                    val t0 = android.os.SystemClock.uptimeMillis()
                    session.run(inputs).use { result ->
                        val ms = android.os.SystemClock.uptimeMillis() - t0
                        emit("    ✓ 推理成功（全零输入）耗时 ${ms}ms")
                        for (i in 0 until result.size()) {
                            val v = result[i]
                            val s = (v as? OnnxTensor)?.info?.let { describe(it) } ?: v.toString()
                            emit("      out[$i] = $s")
                        }
                    }
                } finally {
                    for (t in inputs.values) runCatching { t.close() }
                }
            } catch (t: Throwable) {
                emit("    ✗ 失败：$t")
            } finally {
                runCatching { session?.close() }
            }
        }

        /** 把 ValueInfo 描述成一行。 */
        private fun describe(info: ai.onnxruntime.ValueInfo): String = when (info) {
            is TensorInfo -> "Tensor<${info.type}> shape=${info.shape.joinToString("[", ",", "]")}"
            else -> info.toString()
        }
    }
}
