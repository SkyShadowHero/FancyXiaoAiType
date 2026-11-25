package io.github.skyshadowhero.fancypad.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusGestureCompetitionTest {

    @Test
    fun onlyFirstStrokeCanEnterGestureRecognition() {
        val competition = StylusGestureCompetition(sessionId = 7L)
        val effect = competition.onStrokeFinished(isGestureCandidate = true)

        assertTrue(effect is StylusGestureCompetition.Effect.RecognizeGesture)
        assertEquals(1, competition.completedStrokeCount)

        assertTrue(competition.onStrokeStarted())
        assertEquals(StylusGestureCompetition.State.TEXT_ONLY, competition.state)
        assertEquals(
            StylusGestureCompetition.Effect.None,
            competition.onStrokeFinished(isGestureCandidate = true),
        )
        assertEquals(2, competition.completedStrokeCount)
    }

    @Test
    fun secondStrokeInvalidatesLateModelResult() {
        val competition = StylusGestureCompetition(sessionId = 1L)
        val ticket = (competition.onStrokeFinished(true) as
            StylusGestureCompetition.Effect.RecognizeGesture).ticket

        competition.onStrokeStarted()

        assertEquals(
            StylusGestureCompetition.Effect.None,
            competition.onRecognitionFinished(ticket, accepted = true),
        )
        assertEquals(StylusGestureCompetition.State.TEXT_ONLY, competition.state)
    }

    @Test
    fun acceptedGestureWaitsUntilSessionFinishes() {
        val competition = StylusGestureCompetition(sessionId = 2L)
        val ticket = (competition.onStrokeFinished(true) as
            StylusGestureCompetition.Effect.RecognizeGesture).ticket

        assertEquals(
            StylusGestureCompetition.Effect.None,
            competition.onRecognitionFinished(ticket, accepted = true),
        )
        assertEquals(StylusGestureCompetition.State.READY, competition.state)
        assertEquals(
            StylusGestureCompetition.Effect.TryPerform(ticket),
            competition.finishSession(hasInk = true),
        )
        assertTrue(competition.beginPerform(ticket))
    }

    @Test
    fun modelFailureAfterSessionFinishFallsBackToText() {
        val competition = StylusGestureCompetition(sessionId = 3L)
        val ticket = (competition.onStrokeFinished(true) as
            StylusGestureCompetition.Effect.RecognizeGesture).ticket

        assertEquals(
            StylusGestureCompetition.Effect.None,
            competition.finishSession(hasInk = true),
        )
        assertEquals(
            StylusGestureCompetition.Effect.RecognizeText,
            competition.onRecognitionFinished(ticket, accepted = false),
        )
    }

    @Test
    fun rejectedCandidateBeforeFinishStillFinalizesAsTextExactlyOnce() {
        val competition = StylusGestureCompetition(sessionId = 31L)
        val ticket = (competition.onStrokeFinished(true) as
            StylusGestureCompetition.Effect.RecognizeGesture).ticket

        assertEquals(
            StylusGestureCompetition.Effect.None,
            competition.rejectCandidate(ticket),
        )
        assertEquals(
            StylusGestureCompetition.Effect.RecognizeText,
            competition.finishSession(hasInk = true),
        )
        assertEquals(
            StylusGestureCompetition.Effect.None,
            competition.finishSession(hasInk = true),
        )
    }

    @Test
    fun callbackWithForeignTicketCannotAdvanceSession() {
        val competition = StylusGestureCompetition(sessionId = 32L)
        competition.onStrokeFinished(true)
        val foreign = StylusGestureCompetition.Ticket(sessionId = 99L, candidateId = 1L)

        assertEquals(
            StylusGestureCompetition.Effect.None,
            competition.onRecognitionFinished(foreign, accepted = true),
        )
        assertEquals(StylusGestureCompetition.State.RECOGNIZING, competition.state)
    }

    @Test
    fun onlySuccessConsumesGesture() {
        val success = readyCompetition(sessionId = 4L)
        assertEquals(
            StylusGestureCompetition.Effect.GestureConsumed,
            success.competition.onPerformResult(success.ticket, success = true),
        )

        val failure = readyCompetition(sessionId = 5L)
        assertEquals(
            StylusGestureCompetition.Effect.RecognizeText,
            failure.competition.onPerformResult(failure.ticket, success = false),
        )
    }

    @Test
    fun unknownPerformCanOnlyBeAbandonedOnce() {
        val ready = readyCompetition(sessionId = 6L)

        assertTrue(ready.competition.abandonUnknownPerform(ready.ticket))
        assertFalse(ready.competition.abandonUnknownPerform(ready.ticket))
        assertEquals(StylusGestureCompetition.State.RESOLVED, ready.competition.state)
    }

    @Test
    fun textGateAllowsUnknownBoundsAndRejectsDistantKnownBounds() {
        val stroke = listOf(StylusPoint(0f, 0f), StylusPoint(100f, 0f))

        assertTrue(StylusTextGate.hitsVisibleText(stroke, emptyList(), closedSelection = false))
        assertFalse(
            StylusTextGate.hitsVisibleText(
                stroke,
                listOf(StylusLineBounds(0f, 50f, 100f, 70f)),
                closedSelection = false,
            ),
        )
    }

    @Test
    fun textGateAcceptsStrikeThroughLineAndCircleAroundLine() {
        val line = listOf(StylusLineBounds(30f, 40f, 70f, 60f))
        val strike = listOf(StylusPoint(0f, 50f), StylusPoint(100f, 50f))
        val loop = listOf(
            StylusPoint(0f, 0f),
            StylusPoint(100f, 0f),
            StylusPoint(100f, 100f),
            StylusPoint(0f, 100f),
            StylusPoint(0f, 0f),
        )

        assertTrue(StylusTextGate.hitsVisibleText(strike, line, closedSelection = false))
        assertTrue(StylusTextGate.hitsVisibleText(loop, line, closedSelection = true))
        assertFalse(StylusTextGate.hitsVisibleText(loop, line, closedSelection = false))
    }

    private fun readyCompetition(sessionId: Long): ReadyCompetition {
        val competition = StylusGestureCompetition(sessionId)
        val ticket = (competition.onStrokeFinished(true) as
            StylusGestureCompetition.Effect.RecognizeGesture).ticket
        competition.onRecognitionFinished(ticket, accepted = true)
        competition.finishSession(hasInk = true)
        competition.beginPerform(ticket)
        return ReadyCompetition(competition, ticket)
    }

    private data class ReadyCompetition(
        val competition: StylusGestureCompetition,
        val ticket: StylusGestureCompetition.Ticket,
    )
}
