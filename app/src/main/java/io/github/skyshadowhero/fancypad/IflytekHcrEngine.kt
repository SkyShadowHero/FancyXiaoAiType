package io.github.skyshadowhero.fancypad

import android.inputmethodservice.InputMethodService
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 讯飞手写识别引擎（HCR）—— 小爱**自带**的商用引擎，直接借来用。
 *
 * ## 为什么要单独接它
 *
 * 小爱里其实有两条手写识别路线：
 * 1. **讯飞 HCR**（`SmartEngineManager` / `IHcrEngine`）—— 商用引擎，输入是原始笔迹点，
 *    支持写区域、逐点喂、抬手出候选；
 * 2. **系统笔引擎**（`xiaomi-pencilengine-pad.jar` + `/system_ext/etc/ocr_model.tflite`）——
 *    那是我们早先接的那条。实测那个模型输入是 **64 个整数的定长序列**、只输出
 *    **top-4 候选**（`nativeOCR(float[],float[],int[],...)`，模型 I/O 为
 *    `INT32[1,64,1,1] → [1,1,4]`）—— 拿这么小的输入做 3755 类汉字识别，天花板很低，
 *    这正是"识别好差"的来源。
 *
 * 而讯飞引擎平时**只有在小爱切到手写键盘时才被初始化**（`v4.x.g(ime)` 非空）；
 * 普通随手写走的都是那条弱的系统引擎。这里我们**自己把它拉起来**，不碰小爱的键盘类型。
 *
 * ## 调用链（反编译自 `r9/j.q()` 与 `r9/j.x()`）
 *
 * ```
 * r9.f.k(context)                              // 幂等：初始化整个讯飞 SDK（initSmart）
 * SmartEngineManager.get()
 *   ├── initHcrEngine()                        // 注意：要求 initSmart 已完成
 *   └── getHcrEngine()  -> IHcrEngine
 * IHcrEngine
 *   ├── switchHcr() / setRecognizeType(...) / setHcrInterval(ms) / setWritingArea(l,t,r,b)
 *   ├── inputPoint(x, y, action)               // action 语义同 MotionEvent
 *   ├── finishInput()                          // 抬手：结束本次输入
 *   └── resetHcr()                             // 取消：丢弃
 * 取结果：SmartEngineManager.getDecodeResult(null) -> SmartResultElement.candWords
 * ```
 *
 * 全部走反射：这些类都在输入法自己的 dex 里（混淆名 `r9.f` 是应用类，
 * `com.iflytek.*` 是它捆绑的 SDK，名字没混淆），模块编译期看不到它们。
 *
 * ## 边界
 *
 * 任何一步失败都只记日志并让 [isActive] 保持 false —— 调用方会自动退回系统笔引擎，
 * **绝不让输入法崩**。也**不 release** 引擎（小爱的键盘可能还在用它）。
 */
internal class IflytekHcrEngine {

    @Volatile private var engine: Any? = null
    @Volatile private var manager: Any? = null

    /** `RecognizeType` 类，首次解析后缓存（configure 每次会话都要用）。 */
    @Volatile private var recognizeTypeClass: Class<*>? = null


    private var mInputPoint: Method? = null
    private var mFinishInput: Method? = null
    private var mResetHcr: Method? = null
    private var mSetWritingArea: Method? = null
    private var mSetHcrInterval: Method? = null
    private var mSetRecognizeType: Method? = null
    private var mSwitchHcr: Method? = null
    private var mGetDecodeResult: Method? = null
    private var fCandWords: Field? = null
    private var mGetCandWords: Method? = null

    /** `SmartResult.getWord()` —— candWords 里装的是 SmartResult 对象，文本在这里。 */
    private var mGetWord: Method? = null

    /** 引擎是否已就绪（就绪才值得把笔迹交给它）。 */
    val isActive: Boolean get() = engine != null

    /**
     * 初始化并配置引擎。返回是否可用。
     *
     * @param area 写区域（`setWritingArea(l,t,r,b)`）——我们喂的是屏幕坐标，所以给整屏。
     * @param intervalMs 引擎内部的识别间隔（它会 clamp 到 50~1000）。
     * @param recognizeMode 见 [MODE_*]；默认跟随小爱自己的默认（FREE_STROKE）。
     */
    fun open(
        ime: InputMethodService,
        areaLeft: Int,
        areaTop: Int,
        areaRight: Int,
        areaBottom: Int,
        intervalMs: Int,
        recognizeMode: String = MODE_FREE_STROKE,
    ): Boolean {
        // ★ 引擎只创建一次，但**配置每次会话都要重新下发** ——
        // 早先 `if (engine != null) return true` 直接返回，于是用户在设置里改的
        // "停笔识别延迟"（= setHcrInterval）只在第一次会话生效过，之后永远用旧值
        // （表现为"讯飞的延迟跟我滑块设的对不上"）。写区域同理。
        if (engine != null) {
            return configure(areaLeft, areaTop, areaRight, areaBottom, intervalMs, recognizeMode)
        }
        return try {
            val cl = ime.javaClass.classLoader ?: ClassLoader.getSystemClassLoader()

            // ① 先让整个讯飞 SDK 就绪（`r9.f.k(Context)` 幂等；失败也不硬退，可能本来就已初始化）
            runCatching {
                cl.loadClass(CLS_ADAPTER)
                    .getMethod("k", android.content.Context::class.java)
                    .invoke(null, ime)
            }.onFailure { L.w("event=iflytek_sdk_init_failed msg=${it.message}") }

            val mgrCls = cl.loadClass(CLS_MANAGER)
            val mgr = mgrCls.getMethod("get").invoke(null) ?: run {
                L.w("event=iflytek_no_manager")
                emit("✗ get() 返回空")
                return false
            }

            // ② 拉起 HCR 引擎
            if (mgrCls.getMethod("initHcrEngine").invoke(mgr) != true) {
                emit("✗ initHcrEngine()=false（SDK 1387 HCR 引擎不可用；多半是 initSmart 没完成）")
                return false
            }
            val eng = mgrCls.getMethod("getHcrEngine").invoke(mgr) ?: run {
                emit("✗ getHcrEngine() 返回空")
                return false
            }

            // ③ 方法句柄一律从**公开接口**上找：实现类是包私有的，直接 getMethod 找不到
            val ihcr = cl.loadClass(CLS_HCR_IFACE)
            mInputPoint = ihcr.getMethod("inputPoint", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            mFinishInput = ihcr.getMethod("finishInput")
            mResetHcr = ihcr.getMethod("resetHcr")
            mSwitchHcr = ihcr.getMethod("switchHcr")
            mSetWritingArea = ihcr.getMethod(
                "setWritingArea",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            mSetHcrInterval = ihcr.getMethod("setHcrInterval", Int::class.javaPrimitiveType)

            val rtCls = cl.loadClass(CLS_RECOGNIZE_TYPE).also { recognizeTypeClass = it }
            mSetRecognizeType = ihcr.getMethod("setRecognizeType", rtCls)
            val mode = rtCls.enumConstants
                ?.map { it as Enum<*> }
                ?.firstOrNull { it.name == recognizeMode }
                ?: rtCls.enumConstants?.firstOrNull()

            // ④ 配置（首次；之后每次会话由 configure() 重新下发）
            configure(areaLeft, areaTop, areaRight, areaBottom, intervalMs, recognizeMode, mode)

            // ⑤ 取结果的句柄
            val gprCls = cl.loadClass(CLS_GENERAL_PROCESS_RET)
            mGetDecodeResult = mgrCls.getMethod("getDecodeResult", gprCls)
            val elementCls = cl.loadClass(CLS_SMART_RESULT_ELEMENT)
            fCandWords = runCatching { elementCls.getField("candWords") }.getOrNull()
            if (fCandWords == null) {
                mGetCandWords = runCatching { elementCls.getMethod("getCandWords") }.getOrNull()
            }

            manager = mgr
            engine = eng
            emit("✓ 就绪 mode=$recognizeMode area=($areaLeft,$areaTop,$areaRight,$areaBottom) interval=$intervalMs")
            true
        } catch (t: Throwable) {
            emit("✗ 异常 ${t.javaClass.simpleName}: ${t.message}")
            engine = null
            false
        }
    }

    /**
     * 识别一段字迹：**清空引擎 → 喂归一化后的全部笔画 → 抬手 → 取候选**。
     *
     * 为什么每个周期都重喂全部笔画（而不是只喂新的）：
     * - 引擎按写区域归一化，而我们每次都要把"到目前为止的整段字迹"重新缩放到写区域里，
     *   否则用户写到第二个字时，第一个字在区域里的位置就错了；
     * - 重喂之前 `resetHcr()` 清掉引擎内的旧输入，所以不会重复。
     *
     * 真机教训：早先直接喂屏幕坐标 + 写区域给整屏，结果引擎把一个字看成一个「点」，
     * 候选是 `·`、`、`、`502` 这种东西。
     *
     * @return 候选词列表（按引擎给的顺序，第一个最优）；空 = 没认出来。
     */
    fun recognize(strokes: List<List<PencilEngine.Pt>>): List<String> {
        val e = engine ?: return emptyList()
        val mgr = manager ?: return emptyList()
        if (strokes.isEmpty()) return emptyList()
        return try {
            runCatching { mResetHcr?.invoke(e) }

            // 归一化到写区域那套坐标（等比 + 居中），引擎才会把字看成"填满书写区"
            val norm = InkNormalizer.toBox(strokes)
            var fed = 0
            for (stroke in norm) {
                for (p in stroke) {
                    runCatching { mInputPoint?.invoke(e, p.x.toInt(), p.y.toInt(), p.action) }
                    fed++
                }
            }

            runCatching { mFinishInput?.invoke(e) }
            val element = mGetDecodeResult?.invoke(mgr, null) ?: return emptyList()
            val raw = readCandWords(element) ?: return emptyList()
            // ★ candWords 里是 SmartResult 对象，**必须**用 getWord() 取文本
            //（早先直接 toString() 的结果是把 "SmartResult@788c4a68" 当字上屏了）
            val words = raw.filterNotNull().mapNotNull { wordOf(it)?.takeIf { w -> w.isNotBlank() } }
            emit(
                "recognize fed=$fed ${InkNormalizer.lastBox} words=${words.size}" +
                    (if (words.isEmpty()) "（取不到文本；raw[0]=${raw.firstOrNull()?.javaClass?.name}）"
                     else "  候选=${words.take(6).joinToString("/")}")
            )
            words
        } catch (t: Throwable) {
            emit("recognize 异常 ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
    }

    /**
     * 把当前设置下发给引擎（**每次会话都会调**）。
     *
     * @param mode 已经解析好的 `RecognizeType` 枚举值；为 null 时按 [recognizeMode] 名字现找。
     */
    private fun configure(
        areaLeft: Int,
        areaTop: Int,
        areaRight: Int,
        areaBottom: Int,
        intervalMs: Int,
        recognizeMode: String,
        mode: Any? = null,
    ): Boolean {
        val e = engine ?: return false
        val resolved = mode ?: resolveMode(recognizeMode)
        val interval = intervalMs.coerceIn(50, 1000)
        runCatching { mSwitchHcr?.invoke(e) }
            .onFailure { L.w("event=iflytek_switch_hcr_failed msg=${it.message}") }
        runCatching { if (resolved != null) mSetRecognizeType?.invoke(e, resolved) }
        runCatching { mSetHcrInterval?.invoke(e, interval) }
            .onFailure { L.w("event=iflytek_set_interval_failed msg=${it.message}") }
        runCatching { mSetWritingArea?.invoke(e, areaLeft, areaTop, areaRight, areaBottom) }
        emit(
            "✓ 已配置 间隔=${interval}ms 模式=${(resolved as? Enum<*>)?.name ?: recognizeMode} " +
                "写区域=($areaLeft,$areaTop,$areaRight,$areaBottom)"
        )
        return true
    }

    /** 按名字找 `RecognizeType` 枚举值。 */
    private fun resolveMode(name: String): Any? = try {
        val cls = recognizeTypeClass ?: return null
        cls.enumConstants?.map { it as Enum<*> }?.firstOrNull { it.name == name }
            ?: cls.enumConstants?.firstOrNull()
    } catch (t: Throwable) {
        null
    }

    /** 丢弃当前这次输入（取消/会话结束）。 */
    fun reset() {
        val e = engine ?: return
        runCatching { mResetHcr?.invoke(e) }
            .onFailure { L.w("event=iflytek_reset_failed msg=${it.message}") }
    }

    /** 记一行日志（本机 logcat 不可用，留着方便以后接别的输出通道）。 */
    private fun emit(line: String) {
        L.i("event=iflytek $line")
    }

    /** 从 `SmartResult` 取文本（小爱自己的转换器就是调 `getWord()`）。 */
    private fun wordOf(item: Any): String? {
        mGetWord?.let { return runCatching { it.invoke(item) as? String }.getOrNull() }
        val m = runCatching { item.javaClass.getMethod("getWord") }.getOrNull() ?: run {
            emit("✗ SmartResult 上没有 getWord()，类=${item.javaClass.name}")
            return null
        }
        mGetWord = m
        return runCatching { m.invoke(item) as? String }.getOrNull()
    }

    private fun readCandWords(element: Any): List<*>? = try {
        (fCandWords?.get(element) as? List<*>)
            ?: (mGetCandWords?.invoke(element) as? List<*>)
    } catch (t: Throwable) {
        null
    }

    private companion object {
        /** 应用侧的讯飞适配器（混淆名），只用来调它的幂等 SDK 初始化。 */
        const val CLS_ADAPTER = "r9.f"

        const val CLS_MANAGER = "com.iflytek.depend.common.base.SmartEngineManager"
        const val CLS_HCR_IFACE = "com.iflytek.depend.common.hcr.IHcrEngine"
        const val CLS_RECOGNIZE_TYPE = "com.iflytek.depend.common.hcr.RecognizeType"
        const val CLS_GENERAL_PROCESS_RET = "com.iflytek.inputmethod.smart.api.entity.GeneralProcessRet"
        const val CLS_SMART_RESULT_ELEMENT = "com.iflytek.inputmethod.smart.api.entity.SmartResultElement"

        /** 小爱自己的默认手写模式（`handwriting_mode` 缺省值 "FREE_STROKE"）。 */
        const val MODE_FREE_STROKE = "HCR_RECOGNITION_SENT_FS"

        /** 单字模式（识别更保守，适合一次写一个字）。 */
        const val MODE_CHAR = "HCR_RECOGNITION_CHAR"

    }
}
