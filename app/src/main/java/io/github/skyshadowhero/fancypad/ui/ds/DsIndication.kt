package io.github.skyshadowhero.fancypad.ui.ds

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Glasmio 外观下的按压反馈：一层很淡的填充，**没有 Material 的水波纹**。
 *
 * 圆角可配：[cornerRadius] = 0.dp 时是整行方角（侧栏那种列表用），默认 10dp。
 */
private class DsPressedIndication(
    private val color: Color,
    private val cornerRadius: Dp,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        DsIndicationNode(color, cornerRadius, interactionSource)

    override fun equals(other: Any?): Boolean =
        other is DsPressedIndication && other.color == color && other.cornerRadius == cornerRadius

    override fun hashCode(): Int = 31 * color.hashCode() + cornerRadius.hashCode()
}

private class DsIndicationNode(
    private val color: Color,
    private val cornerRadius: Dp,
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {

    // 不能用 Animatable：Modifier.Node 的作用域没有 Compose 的 MonotonicFrameClock。
    // iOS 的行选中本来就是即时高亮，切一个布尔量就够。
    private var pressed by mutableStateOf(false)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onAttach() {
        scope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                }
            }
        }
    }

    override fun onDetach() {
        scope.cancel()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) {
            drawRoundRect(
                color = color.copy(alpha = 0.07f),
                cornerRadius = CornerRadius(cornerRadius.toPx()),
            )
        }
    }
}

@Composable
fun rememberDsIndication(
    color: Color,
    cornerRadius: Dp = 10.dp,
): androidx.compose.foundation.Indication {
    return remember(color, cornerRadius) { DsPressedIndication(color, cornerRadius) }
}
