package ua.syt0r.kanji.core.srs.fsrs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import ua.syt0r.kanji.core.user_data.preferences.PreferencesContract
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Everything the scheduler and stats need to know about the user's FSRS setup. */
class FsrsConfiguration(
    val fsrs: Fsrs7,
    val parameters: List<Double>,
    val desiredRetention: Double,
    val maxInterval: Duration,
    val passFailMode: Boolean,
    val learningSteps: List<Duration> = emptyList(),
    val relearningSteps: List<Duration> = emptyList()
) {
    fun scheduler(): FsrsScheduler = DefaultFsrsScheduler(
        fsrs = fsrs,
        maxInterval = maxInterval,
        learningSteps = learningSteps,
        relearningSteps = relearningSteps
    )
}

interface FsrsConfigurationRepository {
    val changes: Flow<Unit>
    suspend fun get(): FsrsConfiguration
}

class DefaultFsrsConfigurationRepository(
    private val appPreferences: PreferencesContract.AppPreferences
) : FsrsConfigurationRepository {

    private var cached: Pair<List<Double>, Fsrs7>? = null

    override val changes: Flow<Unit> = merge(
        appPreferences.fsrsParameters.onModified.map { },
        appPreferences.desiredRetentionPercent.onModified.map { },
        appPreferences.fsrsMaxIntervalDays.onModified.map { },
        appPreferences.passFailMode.onModified.map { },
        appPreferences.fsrsLearningSteps.onModified.map { },
        appPreferences.fsrsRelearningSteps.onModified.map { }
    )

    override suspend fun get(): FsrsConfiguration {
        val parsed = FsrsParameters.parse(appPreferences.fsrsParameters.get())
        val parameters = parsed ?: Fsrs7.DEFAULT_PARAMETERS
        val fsrs = cached?.takeIf { it.first == parameters }?.second
            ?: Fsrs7(parameters).also { cached = parameters to it }
        return FsrsConfiguration(
            fsrs = fsrs,
            parameters = parameters,
            desiredRetention = (appPreferences.desiredRetentionPercent.get() / 100.0)
                .coerceIn(MIN_DESIRED_RETENTION, MAX_DESIRED_RETENTION),
            maxInterval = appPreferences.fsrsMaxIntervalDays.get()
                .coerceIn(MIN_MAX_INTERVAL_DAYS, MAX_MAX_INTERVAL_DAYS)
                .days,
            passFailMode = appPreferences.passFailMode.get(),
            learningSteps = FsrsSteps.parse(appPreferences.fsrsLearningSteps.get()).orEmpty(),
            relearningSteps = FsrsSteps.parse(appPreferences.fsrsRelearningSteps.get()).orEmpty()
        )
    }

}

const val MIN_MAX_INTERVAL_DAYS = 1
const val MAX_MAX_INTERVAL_DAYS = 36500

object FsrsParameters {

    /** Parses comma/space separated parameters, null if invalid or empty. */
    fun parse(text: String): List<Double>? {
        val values = text.split(',', ' ', '\n', '\t', ';')
            .filter { it.isNotBlank() }
            .map { it.trim().toDoubleOrNull() ?: return null }
        if (values.size != Fsrs7.PARAMETERS_COUNT) return null
        if (values.any { it.isNaN() || it.isInfinite() }) return null
        // Store the clipped values so they are exactly what the model uses
        return Fsrs7.clip(values).toList()
    }

    /** Fixed-point with up to 4 decimals (never scientific notation, e.g. 0.0006). */
    fun format(parameters: List<Double>): String {
        return parameters.joinToString(", ") { value ->
            val scaled = (kotlin.math.abs(value) * 10000).roundToLong()
            val sign = if (value < 0 && scaled != 0L) "-" else ""
            val fraction = (scaled % 10000).toString().padStart(4, '0').trimEnd('0')
            "$sign${scaled / 10000}.${fraction.ifEmpty { "0" }}"
        }
    }

}

/** Anki-style step lists, e.g. "1m 10m 1h 1d". A number without unit means minutes. */
object FsrsSteps {

    private val stepRegex = Regex("""^(\d+(?:\.\d+)?)([smhd]?)$""")

    /** Empty list for blank text, null if any step is invalid. */
    fun parse(text: String): List<Duration>? {
        return text.split(' ', ',', '\n', '\t')
            .filter { it.isNotBlank() }
            .map { token ->
                val match = stepRegex.matchEntire(token.trim().lowercase()) ?: return null
                val value = match.groupValues[1].toDouble()
                val duration = when (match.groupValues[2]) {
                    "s" -> value.seconds
                    "h" -> value.hours
                    "d" -> value.days
                    else -> value.minutes
                }
                if (duration < 1.seconds || duration > 365.days) return null
                duration
            }
    }

    fun format(steps: List<Duration>): String = steps.joinToString(" ") { step ->
        val seconds = step.inWholeSeconds
        when {
            seconds % 86400 == 0L -> "${seconds / 86400}d"
            seconds % 3600 == 0L -> "${seconds / 3600}h"
            seconds % 60 == 0L -> "${seconds / 60}m"
            else -> "${seconds}s"
        }
    }

}
