package io.github.skyshadowhero.fancypad.hooks

/**
 * 随手写的**三态状态机**（纯逻辑，无 Android 依赖，便于单测）。
 *
 * ## 三种状态
 *
 * | 状态 | 含义 | 键盘 | 面板 |
 * |---|---|---|---|
 * | [Mode.SOFT_KEYBOARD] | 屏幕键盘（含悬浮小键盘） | 显示 | 不显示 |
 * | [Mode.HARD_KEYBOARD] | 已连接实体键盘 | 由系统决定（通常不可触摸） | 不显示 |
 * | [Mode.STYLUS] | 随手写 | 被面板盖住（INVISIBLE） | 显示 |
 *
 * ## 迁移规则（与真机行为对齐）
 *
 * - **连接 / 断开实体键盘**只改 [Mode.SOFT_KEYBOARD] ↔ [Mode.HARD_KEYBOARD]，
 *   **不会**打断随手写 —— 三态彼此独立，真实落笔可以从任一种进入随手写。
 * - **手指 / 笔真正落笔**时才 [enterStylus]；`TOOL_TYPE_STYLUS` 之类的"预显示"
 *   信号由调用方自己过滤（见 `StylusImeHooks.hookEditorToolType`）。
 * - **退出随手写**落到哪种状态由"此刻有没有实体键盘"决定，而不是回到进入前的状态：
 *   写的时候插上键盘，退出后就应该算「已连接键盘」。
 *
 * ## generation 是干什么的
 *
 * 面板的显示是 `post` 出去的（框架的 `AFTER_STYLUS_UP_ALLOW_PERIOD_MS` 只有 200ms，
 * 不能在回调里 inflate）。但 `post` 和 `onFinishInput` / `onWindowHidden` 里的
 * `post` 会交错，可能"先 hide 后 show"，把面板贴到一个已经退出的会话上。
 * 每次迁移自增 [generation]，延时任务拿 [isStale] 判一下就知道自己该不该继续。
 *
 * 这个类是 [StylusImeHooks] 内部状态的唯一真源：所有 hook 只读 [mode]，
 * 不允许再自己维护"是不是随手写"的布尔量。
 */
internal class StylusModeMachine {

    enum class Mode {
        /** 屏幕键盘：软键盘（含悬浮小键盘）正常显示。 */
        SOFT_KEYBOARD,

        /** 已连接实体键盘：系统通常把 IME 主窗口设为不可触摸。 */
        HARD_KEYBOARD,

        /** 随手写：面板显示、键盘内容被藏起、IME 上报零占用高度。 */
        STYLUS,
    }

    @Volatile
    var mode: Mode = Mode.SOFT_KEYBOARD
        private set

    /** 每次真实迁移自增；[isStale] 用它做延时任务的作废判断。 */
    @Volatile
    var generation: Long = 0L
        private set

    /** 实体键盘连接 / 断开时的状态校正。随手写期间不动（三态独立）。 */
    fun onKeyboardAvailability(hardware: Boolean): Boolean = when {
        mode == Mode.STYLUS -> false
        hardware && mode != Mode.HARD_KEYBOARD -> switchTo(Mode.HARD_KEYBOARD)
        !hardware && mode != Mode.SOFT_KEYBOARD -> switchTo(Mode.SOFT_KEYBOARD)
        else -> false
    }

    /**
     * 进入随手写。
     *
     * 已连接实体键盘**不是**拒绝理由：笔直接落在输入框上时，面板必须能出来
     * （面板会自己把 IME 窗口的 `FLAG_NOT_TOUCHABLE` 暂时清掉）。
     */
    fun enterStylus(): Boolean =
        if (mode == Mode.STYLUS) false else switchTo(Mode.STYLUS)

    /**
     * 退出随手写，落到「已连接键盘」或「屏幕键盘」。
     *
     * @param hardware 退出当刻是否连着实体键盘
     */
    fun exitStylus(hardware: Boolean): Boolean =
        if (mode != Mode.STYLUS) {
            false
        } else {
            switchTo(if (hardware) Mode.HARD_KEYBOARD else Mode.SOFT_KEYBOARD)
        }

    /** 热重载 / `dispose()`：回到初始态并作废所有延时任务。 */
    fun reset() {
        mode = Mode.SOFT_KEYBOARD
        generation++
    }

    /** 延时任务投递时取一个票据，执行前用 [isStale] 校验。 */
    fun ticket(): Long = generation

    fun isStale(ticket: Long): Boolean = ticket != generation

    private fun switchTo(next: Mode): Boolean {
        // 只在真的换了状态时自增 generation：重复调用不该作废正在排队的 show。
        if (mode == next) return false
        mode = next
        generation++
        return true
    }
}
