package ua.syt0r.kanji.core.srs.fsrs

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * FSRS-7 memory model (finished dual-stability version, 34 parameters).
 *
 * Ported from the reference implementation in open-spaced-repetition/srs-benchmark
 * (models/fsrs_v7.py and models/fsrs_v7_interval_penalty.py).
 *
 * The memory state has three components: long-term stability, short-term stability and
 * difficulty. The forgetting curve is a weighted mixture of a short-term recall component
 * (driven by short-term stability) and a long-term recall component (driven by long-term
 * stability, with difficulty stretching its timescale). Elapsed time is measured in
 * fractional days, so same-day reviews are modelled natively and no fixed learning steps
 * are needed.
 */
class Fsrs7(
    parameters: List<Double> = DEFAULT_PARAMETERS
) {

    data class MemoryState(
        val longStability: Double,
        val shortStability: Double,
        val difficulty: Double
    )

    private val w: DoubleArray = clip(parameters)

    // ---------------------------------------------------------------------------------
    // State transitions
    // ---------------------------------------------------------------------------------

    fun initialState(rating: FsrsReviewRating): MemoryState {
        val longStability = w[rating.grade - 1].coerceIn(S_MIN, S_MAX)
        return MemoryState(
            longStability = longStability,
            shortStability = (0.8 * longStability).coerceIn(S_MIN, S_MAX),
            difficulty = initialDifficulty(rating.grade).coerceIn(D_MIN, D_MAX)
        )
    }

    /**
     * Memory state after a review with [rating] done [elapsedDays] (fractional) after
     * the previous review.
     */
    fun nextState(
        state: MemoryState,
        elapsedDays: Double,
        rating: FsrsReviewRating
    ): MemoryState {
        val lastS = state.longStability.coerceIn(S_MIN, S_MAX)
        val lastSShort = state.shortStability.coerceIn(S_MIN, S_MAX)
        val lastD = state.difficulty.coerceIn(D_MIN, D_MAX)
        val t = max(elapsedDays, 0.0)

        // Mixed retrievability drives the long-term S and difficulty updates;
        // short-term S uses its own short-term recall component.
        val retrievability = retrievabilityInternal(t, lastS, lastSShort, lastD)
        val newLong = nextStability(lastS, lastD, retrievability, rating.grade, LONG_BLOCK)
        val r1 = shortComponentRecall(t, lastSShort)
        var newShort = nextStability(lastSShort, lastD, r1, rating.grade, SHORT_BLOCK)
        if (rating == FsrsReviewRating.Again) {
            // Post-lapse short-term reset
            newShort = min(newShort, 0.8 * newLong)
        }
        val newD = nextDifficulty(lastD, rating.grade, retrievability)

        return MemoryState(
            longStability = newLong.coerceIn(S_MIN, S_MAX),
            shortStability = newShort.coerceIn(S_MIN, S_MAX),
            difficulty = newD.coerceIn(D_MIN, D_MAX)
        )
    }

    // ---------------------------------------------------------------------------------
    // Forgetting curve
    // ---------------------------------------------------------------------------------

    /** Probability of recall after [elapsedDays] (fractional) since the last review. */
    fun retrievability(state: MemoryState, elapsedDays: Double): Double {
        return retrievabilityInternal(
            t = max(elapsedDays, 0.0),
            s = state.longStability.coerceIn(S_MIN, S_MAX),
            sShort = state.shortStability.coerceIn(S_MIN, S_MAX),
            d = state.difficulty.coerceIn(D_MIN, D_MAX)
        )
    }

    /**
     * Interval in fractional days after which retrievability drops to [desiredRetention].
     * The curve has no closed-form inverse, so it is solved with Newton's method in
     * log-time (as in the reference implementation) followed by a bisection safeguard.
     */
    fun interval(state: MemoryState, desiredRetention: Double): Double {
        val s = state.longStability.coerceIn(S_MIN, S_MAX)
        val sShort = state.shortStability.coerceIn(S_MIN, S_MAX)
        val d = state.difficulty.coerceIn(D_MIN, D_MAX)
        val target = desiredRetention.coerceIn(0.01, 0.999)

        val c = CurveCoefficients(s, sShort, d)

        // Phase 1: Newton in u = ln(t)
        var u = ln(max(s, 1e-10))
        repeat(NEWTON_ITERATIONS) {
            u = u.coerceIn(LN_MIN_T, LN_MAX_T)
            val t = exp(u).coerceIn(MIN_T, MAX_T)
            val (r, drdt) = c.valueAndDerivative(t)
            val dfdu = min(drdt * t, -1e-12)
            u -= (r - target) / dfdu
        }
        u = u.coerceIn(LN_MIN_T, LN_MAX_T)
        var t = exp(u).coerceIn(MIN_T, MAX_T)

        // Phase 2: bisection safeguard in log-space in case Newton has not converged
        if (kotlin.math.abs(c.valueAndDerivative(t).first - target) > 1e-6) {
            var lo = LN_MIN_T
            var hi = LN_MAX_T
            if (c.valueAndDerivative(MAX_T).first >= target) return MAX_T
            if (c.valueAndDerivative(MIN_T).first <= target) return MIN_T
            repeat(100) {
                val mid = (lo + hi) / 2
                if (c.valueAndDerivative(exp(mid)).first > target) lo = mid else hi = mid
            }
            t = exp((lo + hi) / 2)
        }
        return t
    }

    private inner class CurveCoefficients(s: Double, sShort: Double, d: Double) {
        val decay1 = -(w[23] * sShort.pow(w[33] - 0.3)).coerceIn(0.01, 0.95)
        val factor1 = exp(min(ln(max(w[25], 1e-9)) / decay1, 60.0)) - 1.0
        val a1 = factor1 / sShort
        val decay2 = -w[24].coerceIn(0.01, 0.95)
        val factor2 = max(w[26], 1e-9).pow(1.0 / decay2) - 1.0
        val a2 = factor2 * exp((d - 5.0) * (w[32] - 0.3)) / s
        val weight1 = w[27] * sShort.pow(-w[29])
        val weight2 = w[28] * s.pow(w[30]) * exp((d - 5.0) * (w[31] - 0.5))
        val weightSum = max(weight1 + weight2, 1e-9)

        fun valueAndDerivative(t: Double): Pair<Double, Double> {
            val i1 = max(a1 * t + 1.0, 1e-9)
            val i2 = max(a2 * t + 1.0, 1e-9)
            val r = (weight1 * i1.pow(decay1) + weight2 * i2.pow(decay2)) / weightSum
            val dr1 = decay1 * i1.pow(decay1 - 1.0) * a1
            val dr2 = decay2 * i2.pow(decay2 - 1.0) * a2
            val drdt = (weight1 * dr1 + weight2 * dr2) / weightSum
            return r to drdt
        }
    }

    private fun shortComponentRecall(t: Double, sShort: Double): Double {
        val decay1 = -(w[23] * sShort.pow(w[33] - 0.3)).coerceIn(0.01, 0.95)
        val factor1 = exp(min(ln(w[25]) / decay1, 60.0)) - 1.0
        return (t / sShort * factor1 + 1.0).pow(decay1)
    }

    private fun retrievabilityInternal(t: Double, s: Double, sShort: Double, d: Double): Double {
        val r1 = shortComponentRecall(t, sShort)

        val decay2 = -w[24].coerceIn(0.01, 0.95)
        val factor2 = w[26].pow(1.0 / decay2) - 1.0
        val dTimescale = exp((d - 5.0) * (w[32] - 0.3))
        val r2 = (t / s * factor2 * dTimescale + 1.0).pow(decay2)

        val weight1 = w[27] * sShort.pow(-w[29])
        val weight2 = w[28] * s.pow(w[30]) * exp((d - 5.0) * (w[31] - 0.5))

        val retention = (weight1 * r1 + weight2 * r2) / (weight1 + weight2)
        return retention * (1.0 - 2e-5) + 1e-5
    }

    // ---------------------------------------------------------------------------------
    // Stability & difficulty
    // ---------------------------------------------------------------------------------

    /**
     * [start] selects the parameter block: 7 for long-term S, 15 for short-term S.
     */
    private fun nextStability(
        lastS: Double,
        lastD: Double,
        r: Double,
        grade: Int,
        start: Int
    ): Double {
        val hardPenalty = if (grade == 2) w[start + 6] else 1.0
        val easyBonus = if (grade == 4) w[start + 7] else 1.0

        val newSFail = w[start + 3] *
                ((lastS + 1.0).pow(w[start + 4]) - 1.0) *
                exp((1.0 - r) * w[start + 5])
        val postLapseStability = min(lastS, newSFail)

        if (grade == 1) return postLapseStability

        val stabilityIncrease = exp(w[start] - 1.5) *
                (11.0 - lastD) *
                lastS.pow(-w[start + 1]) *
                (exp((1.0 - r) * w[start + 2]) - 1.0) *
                hardPenalty *
                easyBonus + 1.0
        return max(postLapseStability, lastS * stabilityIncrease)
    }

    private fun initialDifficulty(grade: Int): Double {
        return w[4] - exp(w[5] * (grade - 1)) + 1
    }

    private fun nextDifficulty(lastD: Double, grade: Int, retention: Double): Double {
        var deltaD = -w[6] * (grade - 3)
        if (grade == 1) {
            // Surprise-weighted lapse
            deltaD *= (retention + 0.1)
        }
        val newD = lastD + deltaD * (10.0 - lastD) / 9.0
        val reverted = 0.01 * initialDifficulty(4) + 0.99 * newD
        return reverted.coerceIn(D_MIN, D_MAX)
    }

    companion object {

        const val PARAMETERS_COUNT = 34

        const val S_MIN = 0.0001
        const val S_MAX = 36500.0
        const val D_MIN = 1.0
        const val D_MAX = 10.0

        private const val LONG_BLOCK = 7
        private const val SHORT_BLOCK = 15

        private const val MIN_T = 1.0 / 86_400.0
        private const val MAX_T = 36_500.0
        private val LN_MIN_T = ln(MIN_T)
        private val LN_MAX_T = ln(MAX_T)
        private const val NEWTON_ITERATIONS = 12

        /** Default FSRS-7 parameters (srs-benchmark models/fsrs_v7.py init_w). */
        val DEFAULT_PARAMETERS: List<Double> = listOf(
            // Initial stability
            0.1104, 2.2395, 3.9221, 11.7841,
            // Difficulty
            6.1686, 0.6457, 3.6807,
            // Long-term stability
            1.9795, 0.0, 1.3826, 0.7024, 0.5999, 0.8146, 0.6398, 1.0,
            // Short-term stability
            1.3207, 0.6707, 3.8668, 0.4416, 0.0934, 1.8631, 0.6162, 1.0869,
            // Forgetting curve
            0.1567, 0.0801, 0.2421, 0.9464, 0.1433, 0.7145, 0.0, 0.5667, 0.3734, 0.5333,
            0.3048
        )

        private val CLIP_LO = doubleArrayOf(
            0.0001, 0.0001, 0.0001, 0.0001, 1.0, 0.001, 0.1, 0.0, 0.0, 0.3, 0.01, 0.1, 0.0,
            0.0, 1.0, 0.0, 0.0, 0.5, 0.001, 0.001, 0.0, 0.0, 1.0, 0.01, 0.01, 0.2, 0.5, 0.01,
            0.1, 0.0, 0.1, 0.0, 0.0, 0.0
        )

        private val CLIP_HI = doubleArrayOf(
            50.0, 100.0, 100.0, 100.0, 10.0, 4.0, 4.0, 4.0, 1.2, 3.0, 1.5, 1.0, 3.5, 1.0,
            7.0, 4.0, 2.0, 6.0, 1.5, 1.0, 5.0, 1.0, 7.0, 0.25, 0.95, 0.85, 0.99, 1.0, 1.0,
            0.9, 1.1, 1.0, 0.6, 0.6
        )

        /** Applies the same parameter clamps as the reference FSRS7ParameterClipper. */
        fun clip(parameters: List<Double>): DoubleArray {
            require(parameters.size == PARAMETERS_COUNT) {
                "FSRS-7 expects $PARAMETERS_COUNT parameters, got ${parameters.size}"
            }
            val w = DoubleArray(PARAMETERS_COUNT) { parameters[it].coerceIn(CLIP_LO[it], CLIP_HI[it]) }
            w[1] = max(w[1], w[0])
            w[2] = max(w[2], w[1])
            w[3] = max(w[3], w[2])
            w[26] = max(w[26], w[25])
            return w
        }

    }

}
