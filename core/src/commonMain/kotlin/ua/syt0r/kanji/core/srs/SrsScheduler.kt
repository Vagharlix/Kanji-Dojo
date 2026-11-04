package ua.syt0r.kanji.core.srs

import kotlinx.datetime.Instant
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewRating
import ua.syt0r.kanji.core.srs.fsrs.FsrsScheduler
import ua.syt0r.kanji.core.srs.fsrs.MAX_DESIRED_RETENTION
import ua.syt0r.kanji.core.srs.fsrs.MIN_DESIRED_RETENTION
import ua.syt0r.kanji.core.user_data.preferences.PreferencesContract

interface SrsScheduler {
    fun newCard(): SrsCard
    suspend fun answers(data: SrsCard, reviewTime: Instant): SrsAnswers
}

class DefaultSrsScheduler(
    private val fsrsScheduler: FsrsScheduler,
    private val appPreferences: PreferencesContract.AppPreferences
) : SrsScheduler {

    override fun newCard(): SrsCard = SrsCard(fsrsScheduler.newCard())

    override suspend fun answers(
        data: SrsCard,
        reviewTime: Instant
    ): SrsAnswers {
        val desiredRetention = appPreferences.desiredRetention()
        return fsrsScheduler.schedule(data.fsrsCard, reviewTime, desiredRetention).let {
            SrsAnswers(
                again = SrsAnswer(FsrsReviewRating.Again.grade, SrsCard(it.again)),
                hard = SrsAnswer(FsrsReviewRating.Hard.grade, SrsCard(it.hard)),
                good = SrsAnswer(FsrsReviewRating.Good.grade, SrsCard(it.good)),
                easy = SrsAnswer(FsrsReviewRating.Easy.grade, SrsCard(it.easy))
            )
        }
    }

}

suspend fun PreferencesContract.AppPreferences.desiredRetention(): Double {
    return (desiredRetentionPercent.get() / 100.0)
        .coerceIn(MIN_DESIRED_RETENTION, MAX_DESIRED_RETENTION)
}
