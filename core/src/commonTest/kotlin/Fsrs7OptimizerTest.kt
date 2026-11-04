import ua.syt0r.kanji.core.srs.fsrs.DualModel
import ua.syt0r.kanji.core.srs.fsrs.Fsrs7
import ua.syt0r.kanji.core.srs.fsrs.Fsrs7Optimizer
import ua.syt0r.kanji.core.srs.fsrs.FsrsParameters
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewRating
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class Fsrs7OptimizerTest {

    private val defaults = Fsrs7.DEFAULT_PARAMETERS

    /** Parameters different from the defaults, used as "ground truth" for simulations. */
    private val trueParameters = defaults.toMutableList().apply {
        this[0] = 0.5; this[1] = 3.0; this[2] = 6.0; this[3] = 15.0
        this[6] = 2.5
        this[24] = 0.2
        this[26] = 0.9
    }.let { Fsrs7.clip(it).toList() }

    private fun item(vararg reviews: Pair<Double, Int>) = Fsrs7Optimizer.TrainingItem(
        elapsedDays = reviews.map { it.first }.toDoubleArray(),
        grades = reviews.map { it.second }.toIntArray()
    )

    private val sampleItem = item(
        0.0 to 3, 0.007 to 3, 1.2 to 3, 4.0 to 1, 0.004 to 3, 2.0 to 4, 9.0 to 2, 20.0 to 3
    )

    @Test
    fun dualModelMatchesFsrs7() {
        listOf(defaults, trueParameters).forEach { parameters ->
            val fsrs = Fsrs7(parameters)
            var state = fsrs.initialState(FsrsReviewRating.fromGrade(sampleItem.grades[0]))
            val expected = (1 until sampleItem.grades.size).map { i ->
                fsrs.retrievability(state, sampleItem.elapsedDays[i]).also {
                    state = fsrs.nextState(
                        state,
                        sampleItem.elapsedDays[i],
                        FsrsReviewRating.fromGrade(sampleItem.grades[i])
                    )
                }
            }
            val actual = DualModel(Fsrs7.clip(parameters)).predictions(sampleItem)
            expected.zip(actual).forEach { (e, a) -> assertTrue(abs(e - a) < 1e-12, "$e != $a") }
        }
    }

    @Test
    fun gradientsMatchFiniteDifferences() {
        val w = Fsrs7.clip(trueParameters)
        fun loss(parameters: DoubleArray): Double {
            val predictions = DualModel(parameters).predictions(sampleItem)
            return predictions.indices.sumOf { i ->
                val p = predictions[i]
                if (sampleItem.grades[i + 1] > 1) -ln(p) else -ln(1 - p)
            }
        }

        val gradient = DoubleArray(Fsrs7.PARAMETERS_COUNT)
        DualModel(w).accumulateLossGradient(sampleItem, gradient)

        // Skip parameters sitting on a clamp boundary, where the derivative is one-sided
        (0 until Fsrs7.PARAMETERS_COUNT).filter { it !in listOf(8, 29) }.forEach { i ->
            val h = 1e-6 * maxOf(1.0, abs(w[i]))
            val plus = w.copyOf().also { it[i] += h }
            val minus = w.copyOf().also { it[i] -= h }
            val numeric = (loss(plus) - loss(minus)) / (2 * h)
            val tolerance = 1e-4 * maxOf(1.0, abs(numeric))
            assertTrue(
                abs(numeric - gradient[i]) < tolerance,
                "w[$i]: analytic ${gradient[i]} vs numeric $numeric"
            )
        }
    }

    /** Simulates reviews where recall follows FSRS-7 with [trueParameters]. */
    private fun simulate(cards: Int, random: Random): List<Fsrs7Optimizer.TrainingItem> {
        val truth = Fsrs7(trueParameters)
        val scheduler = Fsrs7()
        return List(cards) {
            val elapsed = mutableListOf(0.0)
            val grades = mutableListOf(random.nextInt(1, 5))
            var trueState = truth.initialState(FsrsReviewRating.fromGrade(grades[0]))
            var schedulerState = scheduler.initialState(FsrsReviewRating.fromGrade(grades[0]))
            repeat(random.nextInt(3, 10)) {
                // Review roughly when the default model would schedule it, with some noise
                val t = scheduler.interval(schedulerState, 0.9) * random.nextDouble(0.5, 2.0)
                val recalled = random.nextDouble() < truth.retrievability(trueState, t)
                val grade = if (!recalled) 1 else random.nextInt(2, 5)
                elapsed.add(t)
                grades.add(grade)
                val rating = FsrsReviewRating.fromGrade(grade)
                trueState = truth.nextState(trueState, t, rating)
                schedulerState = scheduler.nextState(schedulerState, t, rating)
            }
            Fsrs7Optimizer.TrainingItem(elapsed.toDoubleArray(), grades.toIntArray())
        }
    }

    @Test
    fun optimizationImprovesFitOnSimulatedHistory() {
        val random = Random(1)
        val train = simulate(800, random)
        val test = simulate(400, random)
        val optimizer = Fsrs7Optimizer()

        val start = TimeSource.Monotonic.markNow()
        var lastProgress = 0f
        val parameters = optimizer.optimize(train, onProgress = { lastProgress = it })
        println("Optimized ${train.sumOf { it.predictedReviews }} reviews in ${start.elapsedNow()}")
        assertEquals(1f, lastProgress, 1e-6f)

        val before = optimizer.evaluate(test, defaults)
        val after = optimizer.evaluate(test, parameters)
        val truth = optimizer.evaluate(test, trueParameters)
        println("Log loss on held-out reviews: defaults=${before.logLoss} optimized=${after.logLoss} truth=${truth.logLoss}")
        println("RMSE (bins): defaults=${before.rmseBins} optimized=${after.rmseBins}")
        println("Optimized parameters: ${FsrsParameters.format(parameters)}")
        assertTrue(after.logLoss < before.logLoss)
        assertEquals(parameters, Fsrs7.clip(parameters).toList())
    }

    @Test
    fun requiresEnoughReviews() {
        assertFailsWith<Fsrs7Optimizer.NotEnoughDataException> {
            Fsrs7Optimizer().optimize(listOf(sampleItem))
        }
    }

    @Test
    fun parametersRoundTripThroughText() {
        val text = FsrsParameters.format(trueParameters)
        val parsed = FsrsParameters.parse(text)!!
        parsed.zip(trueParameters).forEach { (a, b) -> assertTrue(abs(a - b) < 1e-4) }
        assertEquals("0.0006, 1.5, -2.0", FsrsParameters.format(listOf(0.0006, 1.5, -2.0)))
        assertEquals(null, FsrsParameters.parse("1, 2, 3"))
        assertEquals(null, FsrsParameters.parse(""))
        assertEquals(null, FsrsParameters.parse(text.replaceFirst("0", "x")))
    }

}
