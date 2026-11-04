package ua.syt0r.kanji.core.srs.fsrs

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.DurationUnit

/**
 * Trains FSRS-7 parameters on a user's review history, following the reference training
 * setup of srs-benchmark (script.py + models/fsrs_v7.py):
 * - binary cross-entropy between predicted retrievability and whether the review passed,
 *   for every review except a card's first one (same-day reviews included)
 * - L2 penalty pulling parameters towards the defaults (weight 1/3, per-parameter sigmas)
 * - Adam (lr 0.0118, betas 0.70/0.98), cosine learning-rate annealing, batches of 512
 *   reviews, 9 epochs, parameter clipping after every step
 *
 * Gradients are computed with forward-mode automatic differentiation over the 34
 * parameters, since there is no autodiff library available in common Kotlin code.
 */
class Fsrs7Optimizer(
    private val epochs: Int = 9,
    private val batchSize: Int = 512,
    private val learningRate: Double = 0.0118,
    private val beta1: Double = 0.70,
    private val beta2: Double = 0.98,
    private val random: Random = Random(42)
) {

    /** A card's reviews as (elapsed days since previous review, grade). */
    class TrainingItem(val elapsedDays: DoubleArray, val grades: IntArray) {
        /** Number of reviews that get a prediction (all but the first). */
        val predictedReviews: Int get() = grades.size - 1
    }

    data class Metrics(
        val logLoss: Double,
        val rmseBins: Double,
        val reviewCount: Int
    )

    class NotEnoughDataException(val reviews: Int, val required: Int) :
        IllegalStateException("Not enough reviews to optimize: $reviews < $required")

    companion object {
        const val MIN_REVIEWS = 100

        private const val L2_WEIGHT = 0.3333

        private val L2_SIGMA = doubleArrayOf(
            9999.0, 9999.0, 9999.0, 9999.0, 0.523, 0.2528, 0.4329, 0.2966, 0.2139, 0.2889,
            0.1862, 0.175, 0.3812, 0.3013, 0.9104, 0.3234, 0.2448, 0.3273, 0.1842, 0.1735,
            0.4608, 0.311, 0.864, 0.0418, 0.2596, 0.0798, 0.0682, 0.1282, 0.1397, 0.1407,
            0.1489, 0.2, 0.15, 0.15
        )

        /** Builds training items from review logs grouped by card. */
        fun trainingItems(cards: Collection<List<FsrsReviewLog>>): List<TrainingItem> {
            return cards.mapNotNull { logs ->
                val sorted = logs.filter { it.grade in 1..4 }.sortedBy { it.timestamp }
                if (sorted.size < 2) return@mapNotNull null
                TrainingItem(
                    elapsedDays = DoubleArray(sorted.size) { index ->
                        if (index == 0) 0.0
                        else (sorted[index].timestamp - sorted[index - 1].timestamp)
                            .toDouble(DurationUnit.DAYS)
                            .coerceAtLeast(0.0)
                    },
                    grades = IntArray(sorted.size) { sorted[it].grade }
                )
            }
        }
    }

    /**
     * @param onProgress called with values in 0..1
     * @param isCancelled checked between batches, training stops early when it returns true
     */
    fun optimize(
        items: List<TrainingItem>,
        initialParameters: List<Double> = Fsrs7.DEFAULT_PARAMETERS,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): List<Double> {
        val totalReviews = items.sumOf { it.predictedReviews }
        if (totalReviews < MIN_REVIEWS) throw NotEnoughDataException(totalReviews, MIN_REVIEWS)

        val defaults = Fsrs7.DEFAULT_PARAMETERS.toDoubleArray()
        var w = Fsrs7.clip(initialParameters)
        val m = DoubleArray(Fsrs7.PARAMETERS_COUNT)
        val v = DoubleArray(Fsrs7.PARAMETERS_COUNT)

        // Split cards into batches of roughly batchSize predicted reviews
        val batchesPerEpoch = ((totalReviews + batchSize - 1) / batchSize).coerceAtLeast(1)
        val totalSteps = batchesPerEpoch * epochs
        var step = 0

        repeat(epochs) {
            val shuffled = items.shuffled(random)
            var batchStart = 0
            while (batchStart < shuffled.size) {
                if (isCancelled()) return w.toList()

                var batchReviews = 0
                var batchEnd = batchStart
                while (batchEnd < shuffled.size && batchReviews < batchSize) {
                    batchReviews += shuffled[batchEnd].predictedReviews
                    batchEnd++
                }

                val gradient = DoubleArray(Fsrs7.PARAMETERS_COUNT)
                val model = DualModel(w)
                for (index in batchStart until batchEnd) {
                    model.accumulateLossGradient(shuffled[index], gradient)
                }
                // L2 penalty, scaled so that it adds up to L2_WEIGHT * L2 per epoch
                val penaltyScale = L2_WEIGHT * batchReviews / totalReviews
                for (i in 0 until Fsrs7.PARAMETERS_COUNT) {
                    val sigma = L2_SIGMA[i]
                    gradient[i] += penaltyScale * 2 * (w[i] - defaults[i]) / (sigma * sigma)
                }

                // Adam step with cosine annealed learning rate
                val lr = learningRate * 0.5 * (1 + cos(PI * step / totalSteps))
                step++
                val updated = DoubleArray(Fsrs7.PARAMETERS_COUNT)
                for (i in 0 until Fsrs7.PARAMETERS_COUNT) {
                    val g = gradient[i]
                    m[i] = beta1 * m[i] + (1 - beta1) * g
                    v[i] = beta2 * v[i] + (1 - beta2) * g * g
                    val mHat = m[i] / (1 - beta1.pow(step))
                    val vHat = v[i] / (1 - beta2.pow(step))
                    updated[i] = w[i] - lr * mHat / (sqrt(vHat) + 1e-8)
                }
                w = Fsrs7.clip(updated.toList())

                onProgress(step.toFloat() / totalSteps)
                batchStart = batchEnd
            }
        }

        return w.toList()
    }

    /** Log loss and RMSE (bins) of the parameters' predictions on the given reviews. */
    fun evaluate(items: List<TrainingItem>, parameters: List<Double>): Metrics {
        val fsrs = Fsrs7(parameters)
        val predictions = ArrayList<Pair<Double, Int>>()
        items.forEach { item ->
            var state = fsrs.initialState(FsrsReviewRating.fromGrade(item.grades[0]))
            for (i in 1 until item.grades.size) {
                val r = fsrs.retrievability(state, item.elapsedDays[i])
                predictions.add(r to if (item.grades[i] > 1) 1 else 0)
                state = fsrs.nextState(state, item.elapsedDays[i], FsrsReviewRating.fromGrade(item.grades[i]))
            }
        }
        if (predictions.isEmpty()) return Metrics(Double.NaN, Double.NaN, 0)

        val logLoss = predictions.sumOf { (p, y) ->
            -(y * ln(p.coerceIn(1e-7, 1 - 1e-7)) + (1 - y) * ln((1 - p).coerceIn(1e-7, 1 - 1e-7)))
        } / predictions.size

        // RMSE between mean predicted and mean actual recall in 20 bins of predicted recall
        val bins = predictions.groupBy { (p, _) -> (p * 20).toInt().coerceIn(0, 19) }
        val squaredError = bins.values.sumOf { bin ->
            val predicted = bin.sumOf { it.first } / bin.size
            val actual = bin.sumOf { it.second }.toDouble() / bin.size
            (predicted - actual) * (predicted - actual) * bin.size
        }
        val rmse = sqrt(squaredError / predictions.size)

        return Metrics(logLoss, rmse, predictions.size)
    }

}

// -------------------------------------------------------------------------------------
// Forward-mode automatic differentiation
// -------------------------------------------------------------------------------------

/** A value together with its gradient with respect to the 34 FSRS-7 parameters. */
internal class Dual(val v: Double, val g: DoubleArray) {

    operator fun plus(o: Dual) = Dual(v + o.v, DoubleArray(g.size) { g[it] + o.g[it] })
    operator fun plus(c: Double) = Dual(v + c, g)
    operator fun minus(o: Dual) = Dual(v - o.v, DoubleArray(g.size) { g[it] - o.g[it] })
    operator fun minus(c: Double) = Dual(v - c, g)
    operator fun unaryMinus() = Dual(-v, DoubleArray(g.size) { -g[it] })
    operator fun times(o: Dual) = Dual(v * o.v, DoubleArray(g.size) { g[it] * o.v + o.g[it] * v })
    operator fun times(c: Double) = Dual(v * c, DoubleArray(g.size) { g[it] * c })
    operator fun div(o: Dual): Dual {
        val inv = 1.0 / o.v
        return Dual(v * inv, DoubleArray(g.size) { (g[it] * o.v - v * o.g[it]) * inv * inv })
    }

    operator fun div(c: Double) = times(1.0 / c)

    fun exp(): Dual {
        val e = kotlin.math.exp(v)
        return Dual(e, DoubleArray(g.size) { g[it] * e })
    }

    fun ln(): Dual = Dual(kotlin.math.ln(v), DoubleArray(g.size) { g[it] / v })

    /** this^exponent for a positive base. */
    fun pow(exponent: Dual): Dual {
        val value = kotlin.math.exp(exponent.v * kotlin.math.ln(v))
        val dBase = exponent.v * value / v
        val dExp = value * kotlin.math.ln(v)
        return Dual(value, DoubleArray(g.size) { dBase * g[it] + dExp * exponent.g[it] })
    }

    fun pow(exponent: Double): Dual {
        val value = kotlin.math.exp(exponent * kotlin.math.ln(v))
        val d = exponent * value / v
        return Dual(value, DoubleArray(g.size) { d * g[it] })
    }

    /** Like torch.clamp: gradient is zero where the value gets clamped. */
    fun clamp(lo: Double, hi: Double): Dual = when {
        v < lo -> constant(lo, g.size)
        v > hi -> constant(hi, g.size)
        else -> this
    }

    fun coerceAtMost(hi: Double): Dual = if (v > hi) constant(hi, g.size) else this

    companion object {
        fun constant(value: Double, size: Int) = Dual(value, DoubleArray(size))
        fun max(a: Dual, b: Dual) = if (a.v >= b.v) a else b
        fun min(a: Dual, b: Dual) = if (a.v <= b.v) a else b
    }

}

internal operator fun Double.times(d: Dual) = d * this
internal operator fun Double.minus(d: Dual) = (-d) + this

/** FSRS-7 forward pass on dual numbers; mirrors [Fsrs7] exactly. */
internal class DualModel(parameters: DoubleArray) {

    private val n = Fsrs7.PARAMETERS_COUNT
    private val w: Array<Dual> = Array(n) { i ->
        Dual(parameters[i], DoubleArray(n).also { it[i] = 1.0 })
    }

    class State(val s: Dual, val sShort: Dual, val d: Dual)

    private fun c(value: Double) = Dual.constant(value, n)

    private fun initialDifficulty(grade: Int): Dual = w[4] - (w[5] * (grade - 1).toDouble()).exp() + 1.0

    fun initialState(grade: Int): State {
        val s = w[grade - 1].clamp(Fsrs7.S_MIN, Fsrs7.S_MAX)
        return State(
            s = s,
            sShort = (s * 0.8).clamp(Fsrs7.S_MIN, Fsrs7.S_MAX),
            d = initialDifficulty(grade).clamp(Fsrs7.D_MIN, Fsrs7.D_MAX)
        )
    }

    private fun shortComponentRecall(t: Double, sShort: Dual): Dual {
        val decay1 = -(w[23] * sShort.pow(w[33] - 0.3)).clamp(0.01, 0.95)
        val factor1 = (w[25].ln() / decay1).coerceAtMost(60.0).exp() - 1.0
        return (factor1 * t / sShort + 1.0).pow(decay1)
    }

    fun retrievability(t: Double, state: State): Dual {
        val s = state.s
        val sShort = state.sShort
        val d = state.d
        val r1 = shortComponentRecall(t, sShort)
        val decay2 = -w[24].clamp(0.01, 0.95)
        val factor2 = w[26].pow(c(1.0) / decay2) - 1.0
        val dTimescale = ((d - 5.0) * (w[32] - 0.3)).exp()
        val r2 = (factor2 * dTimescale * t / s + 1.0).pow(decay2)
        val weight1 = w[27] * sShort.pow(-w[29])
        val weight2 = w[28] * s.pow(w[30]) * ((d - 5.0) * (w[31] - 0.5)).exp()
        val retention = (weight1 * r1 + weight2 * r2) / (weight1 + weight2)
        return retention * (1.0 - 2e-5) + 1e-5
    }

    private fun nextStability(lastS: Dual, lastD: Dual, r: Dual, grade: Int, start: Int): Dual {
        val newSFail = w[start + 3] *
                ((lastS + 1.0).pow(w[start + 4]) - 1.0) *
                ((1.0 - r) * w[start + 5]).exp()
        val postLapse = Dual.min(lastS, newSFail)
        if (grade == 1) return postLapse
        var increase = (w[start] - 1.5).exp() *
                (11.0 - lastD) *
                lastS.pow(-w[start + 1]) *
                (((1.0 - r) * w[start + 2]).exp() - 1.0)
        if (grade == 2) increase *= w[start + 6]
        if (grade == 4) increase *= w[start + 7]
        return Dual.max(postLapse, lastS * (increase + 1.0))
    }

    private fun nextDifficulty(lastD: Dual, grade: Int, retention: Dual): Dual {
        var deltaD = w[6] * (-(grade - 3).toDouble())
        if (grade == 1) deltaD *= (retention + 0.1)
        val newD = lastD + deltaD * (10.0 - lastD) / 9.0
        val reverted = initialDifficulty(4) * 0.01 + newD * 0.99
        return reverted.clamp(Fsrs7.D_MIN, Fsrs7.D_MAX)
    }

    fun nextState(state: State, t: Double, grade: Int): Pair<State, Dual> {
        val lastS = state.s.clamp(Fsrs7.S_MIN, Fsrs7.S_MAX)
        val lastSShort = state.sShort.clamp(Fsrs7.S_MIN, Fsrs7.S_MAX)
        val lastD = state.d.clamp(Fsrs7.D_MIN, Fsrs7.D_MAX)
        val clampedState = State(lastS, lastSShort, lastD)
        val retrievability = retrievability(t, clampedState)
        val newS = nextStability(lastS, lastD, retrievability, grade, 7)
        val r1 = shortComponentRecall(t, lastSShort)
        var newSShort = nextStability(lastSShort, lastD, r1, grade, 15)
        if (grade == 1) newSShort = Dual.min(newSShort, newS * 0.8)
        val newD = nextDifficulty(lastD, grade, retrievability)
        return State(
            s = newS.clamp(Fsrs7.S_MIN, Fsrs7.S_MAX),
            sShort = newSShort.clamp(Fsrs7.S_MIN, Fsrs7.S_MAX),
            d = newD.clamp(Fsrs7.D_MIN, Fsrs7.D_MAX)
        ) to retrievability
    }

    /** Adds the gradient of the summed BCE loss over the item's reviews to [gradient]. */
    fun accumulateLossGradient(item: Fsrs7Optimizer.TrainingItem, gradient: DoubleArray): Double {
        var state = initialState(item.grades[0])
        var loss = 0.0
        for (i in 1 until item.grades.size) {
            val (next, r) = nextState(state, item.elapsedDays[i], item.grades[i])
            val passed = item.grades[i] > 1
            // d/dw of -[y ln p + (1-y) ln(1-p)] = -(y/p - (1-y)/(1-p)) * dp/dw
            val p = r.v
            val dLossDp = if (passed) -1.0 / p else 1.0 / (1.0 - p)
            loss += if (passed) -kotlin.math.ln(p) else -kotlin.math.ln(1.0 - p)
            for (k in 0 until n) gradient[k] += dLossDp * r.g[k]
            state = next
        }
        return loss
    }

    /** Value-only forward pass, used to check that this model matches [Fsrs7]. */
    internal fun predictions(item: Fsrs7Optimizer.TrainingItem): List<Double> {
        var state = initialState(item.grades[0])
        val result = mutableListOf<Double>()
        for (i in 1 until item.grades.size) {
            val (next, r) = nextState(state, item.elapsedDays[i], item.grades[i])
            result.add(r.v)
            state = next
        }
        return result
    }

}
