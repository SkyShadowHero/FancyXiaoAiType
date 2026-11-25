package io.github.skyshadowhero.fancypad.hooks

/**
 * 单次随手写会话里的「手势 / 文字」竞争状态机。
 *
 * 这里只决定哪条分支可以继续，不持有 Android 对象：模型、输入连接和笔迹快照都由调用方
 * 保存。这样异步回调只能凭 [Ticket] 推进自己的会话，不能误伤后来开始的会话。
 */
internal class StylusGestureCompetition(val sessionId: Long) {

    data class Ticket(val sessionId: Long, val candidateId: Long)

    enum class State {
        COLLECTING,
        RECOGNIZING,
        READY,
        TEXT_ONLY,
        PERFORMING,
        RESOLVED,
    }

    sealed interface Effect {
        data object None : Effect
        data class RecognizeGesture(val ticket: Ticket) : Effect
        data class TryPerform(val ticket: Ticket) : Effect
        data object RecognizeText : Effect
        data object GestureConsumed : Effect
    }

    var state: State = State.COLLECTING
        private set

    var completedStrokeCount: Int = 0
        private set

    var sessionFinished: Boolean = false
        private set

    private var nextCandidateId = 0L
    private var activeTicket: Ticket? = null

    /** 第二笔一开始，第一笔就永久失去手势资格；未完成的第二笔也不能让它复活。 */
    fun onStrokeStarted(): Boolean {
        if (completedStrokeCount == 0) return false
        if (state != State.RECOGNIZING && state != State.READY) return false
        state = State.TEXT_ONLY
        activeTicket = null
        return true
    }

    /** 只有本会话第一条完整笔画能进入模型。 */
    fun onStrokeFinished(isGestureCandidate: Boolean): Effect {
        completedStrokeCount++
        if (completedStrokeCount != 1 || !isGestureCandidate || state != State.COLLECTING) {
            if (state == State.COLLECTING) state = State.TEXT_ONLY
            return Effect.None
        }
        val ticket = Ticket(sessionId, ++nextCandidateId)
        activeTicket = ticket
        state = State.RECOGNIZING
        return Effect.RecognizeGesture(ticket)
    }

    fun onRecognitionFinished(ticket: Ticket, accepted: Boolean): Effect {
        if (ticket != activeTicket || state != State.RECOGNIZING) return Effect.None
        if (!accepted) return rejectCandidate(ticket)
        state = State.READY
        return if (sessionFinished) Effect.TryPerform(ticket) else Effect.None
    }

    /** 模型超时、空间门控失败、输入连接失效都走同一条无损文字分支。 */
    fun rejectCandidate(ticket: Ticket): Effect {
        if (ticket != activeTicket || (state != State.RECOGNIZING && state != State.READY)) {
            return Effect.None
        }
        state = State.TEXT_ONLY
        activeTicket = null
        if (!sessionFinished) return Effect.None
        state = State.RESOLVED
        return Effect.RecognizeText
    }

    fun finishSession(hasInk: Boolean): Effect {
        sessionFinished = true
        if (!hasInk) {
            state = State.RESOLVED
            activeTicket = null
            return Effect.None
        }
        return when (state) {
            State.READY -> Effect.TryPerform(activeTicket ?: return Effect.RecognizeText)
            State.RECOGNIZING, State.PERFORMING, State.RESOLVED -> Effect.None
            State.COLLECTING, State.TEXT_ONLY -> {
                state = State.RESOLVED
                activeTicket = null
                Effect.RecognizeText
            }
        }
    }

    /** 只有结束会话后的 READY 候选能产生一次编辑器副作用。 */
    fun beginPerform(ticket: Ticket): Boolean {
        if (!sessionFinished || state != State.READY || ticket != activeTicket) return false
        state = State.PERFORMING
        return true
    }

    fun onPerformResult(ticket: Ticket, success: Boolean): Effect {
        if (ticket != activeTicket || state != State.PERFORMING) return Effect.None
        activeTicket = null
        state = State.RESOLVED
        return if (success) Effect.GestureConsumed else Effect.RecognizeText
    }

    /** 已经提交给编辑器但迟迟没有回调：只封口，不能冒险再写一次 OCR。 */
    fun abandonUnknownPerform(ticket: Ticket): Boolean {
        if (ticket != activeTicket || state != State.PERFORMING) return false
        activeTicket = null
        state = State.RESOLVED
        return true
    }
}

/** 纯浮点几何，供可见文字行门控与 JVM 单测共用。 */
internal data class StylusPoint(val x: Float, val y: Float)

internal data class StylusLineBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun isUsable(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right > left && bottom > top

    fun contains(point: StylusPoint): Boolean =
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom
}

internal object StylusTextGate {

    /**
     * 未提供边界时宽容放行；有边界时，划除必须穿过文字行，圈选还允许把文字行包在环内。
     */
    fun hitsVisibleText(
        stroke: List<StylusPoint>,
        visibleLines: List<StylusLineBounds>,
        closedSelection: Boolean,
    ): Boolean {
        val lines = visibleLines.filter { it.isUsable() }
        if (lines.isEmpty()) return true
        if (stroke.size < 2) return false
        return lines.any { line ->
            stroke.any(line::contains) ||
                stroke.zipWithNext().any { (a, b) -> segmentIntersectsBounds(a, b, line) } ||
                (closedSelection && boundsInsidePolygon(line, stroke))
        }
    }

    private fun boundsInsidePolygon(bounds: StylusLineBounds, polygon: List<StylusPoint>): Boolean {
        val center = StylusPoint(
            (bounds.left + bounds.right) / 2f,
            (bounds.top + bounds.bottom) / 2f,
        )
        return pointInPolygon(center, polygon) ||
            pointInPolygon(StylusPoint(bounds.left, bounds.top), polygon) ||
            pointInPolygon(StylusPoint(bounds.right, bounds.top), polygon) ||
            pointInPolygon(StylusPoint(bounds.left, bounds.bottom), polygon) ||
            pointInPolygon(StylusPoint(bounds.right, bounds.bottom), polygon)
    }

    private fun segmentIntersectsBounds(
        a: StylusPoint,
        b: StylusPoint,
        bounds: StylusLineBounds,
    ): Boolean {
        if (bounds.contains(a) || bounds.contains(b)) return true
        val topLeft = StylusPoint(bounds.left, bounds.top)
        val topRight = StylusPoint(bounds.right, bounds.top)
        val bottomLeft = StylusPoint(bounds.left, bounds.bottom)
        val bottomRight = StylusPoint(bounds.right, bounds.bottom)
        return segmentsIntersect(a, b, topLeft, topRight) ||
            segmentsIntersect(a, b, topRight, bottomRight) ||
            segmentsIntersect(a, b, bottomRight, bottomLeft) ||
            segmentsIntersect(a, b, bottomLeft, topLeft)
    }

    private fun segmentsIntersect(
        a: StylusPoint,
        b: StylusPoint,
        c: StylusPoint,
        d: StylusPoint,
    ): Boolean {
        val abC = cross(a, b, c)
        val abD = cross(a, b, d)
        val cdA = cross(c, d, a)
        val cdB = cross(c, d, b)
        if (((abC > EPSILON && abD < -EPSILON) || (abC < -EPSILON && abD > EPSILON)) &&
            ((cdA > EPSILON && cdB < -EPSILON) || (cdA < -EPSILON && cdB > EPSILON))
        ) {
            return true
        }
        return (kotlin.math.abs(abC) <= EPSILON && onSegment(a, b, c)) ||
            (kotlin.math.abs(abD) <= EPSILON && onSegment(a, b, d)) ||
            (kotlin.math.abs(cdA) <= EPSILON && onSegment(c, d, a)) ||
            (kotlin.math.abs(cdB) <= EPSILON && onSegment(c, d, b))
    }

    private fun cross(a: StylusPoint, b: StylusPoint, p: StylusPoint): Float =
        (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

    private fun onSegment(a: StylusPoint, b: StylusPoint, p: StylusPoint): Boolean =
        p.x >= minOf(a.x, b.x) - EPSILON && p.x <= maxOf(a.x, b.x) + EPSILON &&
            p.y >= minOf(a.y, b.y) - EPSILON && p.y <= maxOf(a.y, b.y) + EPSILON

    private fun pointInPolygon(point: StylusPoint, polygon: List<StylusPoint>): Boolean {
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            val crosses = (current.y > point.y) != (previous.y > point.y)
            if (crosses) {
                val intersectionX = (previous.x - current.x) * (point.y - current.y) /
                    (previous.y - current.y) + current.x
                if (point.x < intersectionX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private const val EPSILON = 0.001f
}
