package com.skyler.fancytype.ui

/**
 * 尺寸类设置（键盘间隙 / 按键间距）过大时的统一风险提示。
 *
 * 阈值规则统一：任一项超过**该项上限的 6/10** 即告警。
 * 间隙与按键间距走同一套判定与同一个说明性对话框，避免两处提示标准不一致。
 */
object SpaceWarning {

    /** 警告阈值占该项上限的比例 */
    const val THRESHOLD_RATIO = 0.6f

    /** 触发阈值 */
    fun threshold(max: Float): Float = max * THRESHOLD_RATIO

    /** 一个待检查的尺寸项：名称 + 当前值 + 该项上限（决定安全阈值）。 */
    data class Item(val label: String, val value: Float, val max: Float) {
        val limit: Float get() = threshold(max)
        val exceeded: Boolean get() = value > limit
    }

    /** 取出所有超限项（保持传入顺序，便于按页面顺序展示）。 */
    fun exceeded(items: List<Item>): List<Item> = items.filter { it.exceeded }
}
