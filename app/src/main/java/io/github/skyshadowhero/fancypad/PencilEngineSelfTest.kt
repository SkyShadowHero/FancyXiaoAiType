package io.github.skyshadowhero.fancypad

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/**
 * 笔引擎自检页（调试用）。
 *
 * 不依赖触控笔，也不依赖 LSPosed：用**内置的合成笔迹**直接调系统 OCR 引擎，
 * 验证「PathClassLoader 挂 /system_ext/framework/xiaomi-pencilengine-pad.jar
 * + 绕过 EnableAuthData 包名白名单 + 本地 /system_ext/etc/ocr_model.tflite 出字」
 * 这条链路在当前进程里是否真的通。
 *
 * 触发方式（模块 App 的 Activity 是 exported，shell 可直接拉起来）：
 * ```
 * am start -n io.github.skyshadowhero.fancypad/.PencilEngineSelfTest
 * ```
 * 结果同时写进 logcat（`adb logcat -s FancyPad`，事件名 `pencil_selftest_*`），
 * 所以不用看屏幕也能取到结论。
 */
class PencilEngineSelfTest : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.BLACK)
            setText("笔引擎自检中…")
        }
        setContentView(ScrollView(this).apply { addView(text) })

        thread(name = "pencil-selftest") {
            val report = runSelfTest(this)
            L.i("event=pencil_selftest_done\n$report")
            runOnUiThread { text.text = report }
        }
    }

    companion object {
        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 1
        private const val ACTION_MOVE = 2

        /**
         * 自检主流程：开引擎 → 逐字识别内置笔迹 → 关引擎，返回可读报告。
         *
         * 报告**同时写文件**（`files/selftest.txt`）：这台机器上 logcat 完全不可用
         * （`logcat -d` 一行都读不出来），屏幕又可能被别的窗口盖住，落盘才是最稳的取证方式。
         * 逐行追加而不是最后一次性写 —— 万一卡在某一步，也能看出走到哪了。
         */
        fun runSelfTest(context: Context): String {
            val file = File(context.filesDir, FILE_NAME)
            val sb = StringBuilder()
            fun emit(line: String) {
                sb.append(line).append('\n')
                runCatching { file.writeText(sb.toString()) }
            }

            emit("jar = ${PencilEngine.JAR_PATH}")
            emit("pkg = ${context.packageName}")
            emit("----")

            val engine = PencilEngine.open(context)
            if (engine == null) {
                emit("✗ 引擎打开失败（event=pencil_engine_open_failed）")
                return sb.toString()
            }
            try {
                for ((label, strokes) in samples()) {
                    val r = engine.recognize(strokes)
                    emit("$label → ${r ?: "(null)"}")
                }
            } finally {
                engine.close()
                emit("---- done")
            }
            return sb.toString()
        }

        /** 自检报告文件名（App 私有目录内）。 */
        const val FILE_NAME = "selftest.txt"

        /**
         * 合成笔迹：把每个字画在一个固定方框里（坐标是屏幕像素量级，
         * 与 `Ink.Point.obtain(MotionEvent)` 拿到的坐标系一致）。
         * 单笔顺序靠 [line] 的起始时间戳串起来，模拟真实落笔节奏。
         */
        private fun samples(): List<Pair<String, List<List<PencilEngine.Pt>>>> {
            var t = 0L
            fun next(strokes: Int): Long = (t + strokes * 100L).also { t += 600L }

            return listOf(
                "一" to listOf(line(300f, 800f, 1300f, 800f, next(1))),
                "二" to listOf(
                    line(400f, 600f, 1200f, 600f, next(1)),
                    line(300f, 1000f, 1300f, 1000f, next(1)),
                ),
                "三" to listOf(
                    line(420f, 500f, 1180f, 500f, next(1)),
                    line(350f, 800f, 1250f, 800f, next(1)),
                    line(300f, 1100f, 1300f, 1100f, next(1)),
                ),
                "十" to listOf(
                    line(300f, 800f, 1300f, 800f, next(1)),
                    line(800f, 400f, 800f, 1250f, next(1)),
                ),
                "大" to listOf(
                    line(350f, 700f, 1250f, 700f, next(1)),
                    line(800f, 500f, 420f, 1250f, next(1)),
                    line(800f, 500f, 1240f, 1200f, next(1)),
                ),
                "口" to listOf(
                    line(400f, 500f, 400f, 1100f, next(1)),
                    line(400f, 500f, 1200f, 500f, next(1)),
                    line(1200f, 500f, 1200f, 1100f, next(1)),
                    line(400f, 1100f, 1200f, 1100f, next(1)),
                ),
            )
        }

        /** 把一条直线采样成一串点，首点 DOWN、末点 UP、中间 MOVE。 */
        private fun line(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            t0: Long,
            steps: Int = 20,
        ): List<PencilEngine.Pt> {
            val out = ArrayList<PencilEngine.Pt>(steps + 1)
            for (i in 0..steps) {
                val f = i.toFloat() / steps
                val action = when (i) {
                    0 -> ACTION_DOWN
                    steps -> ACTION_UP
                    else -> ACTION_MOVE
                }
                out.add(
                    PencilEngine.Pt(
                        x = x0 + (x1 - x0) * f,
                        y = y0 + (y1 - y0) * f,
                        action = action,
                        time = t0 + i * 10L,
                    )
                )
            }
            return out
        }
    }
}
