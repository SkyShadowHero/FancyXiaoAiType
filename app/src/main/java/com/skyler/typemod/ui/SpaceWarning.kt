package com.skyler.typemod.ui

/**
 * 尺寸类设置（键盘间隙 / 按键间距）过大时的统一风险提示。
 *
 * 阈值统一取「上限的 6/10」；跨过阈值时由 AppShell 弹出说明性对话框，
 * 而不是一闪而过的提示 —— 因为过大的值会把键盘布局压坏，属于需要用户明确知晓的风险。
 */
object SpaceWarning {

    /** 警告阈值占上限的比例 */
    const val THRESHOLD_RATIO = 0.6f

    /** 触发阈值 */
    fun threshold(max: Float): Float = max * THRESHOLD_RATIO

    /** 一组值里是否有任意一个超过上限的 6/10 */
    fun exceeded(max: Float, values: List<Float>): Boolean {
        val limit = threshold(max)
        return values.any { it > limit }
    }
}
