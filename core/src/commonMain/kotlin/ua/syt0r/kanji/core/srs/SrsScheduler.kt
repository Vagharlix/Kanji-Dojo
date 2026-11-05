package ua.syt0r.kanji.core.srs

import kotlinx.datetime.Instant
import kotlin.time.Duration
import ua.syt0r.kanji.core.srs.fsrs.FsrsConfigurationRepository
import ua.syt0r.kanji.core.srs.fsrs.FsrsReviewRating
import ua.syt0r.kanji.core.srs.fsrs.FsrsScheduler

interface SrsScheduler {
    fun newCard(): SrsCard
    suspend fun answers(data: SrsCard, reviewTime: Instant): SrsAnswers
    suspend fun learnAheadLimit(): Duration
}

class DefaultSrsScheduler(
    private val fsrsScheduler: FsrsScheduler,
    private val fsrsConfigurationRepository: FsrsConfigurationRepository
) : SrsScheduler {

    override fun newCard(): SrsCard = SrsCard(fsrsScheduler.newCard())

    override suspend fun learnAheadLimit(): Duration {
        return fsrsConfigurationRepository.get().learnAheadLimit
    }

    override suspend fun answers(
        data: SrsCard,
        reviewTime: Instant
    ): SrsAnswers {
        val configuration = fsrsConfigurationRepository.get()
        return configuration.scheduler()
            .schedule(data.fsrsCard, reviewTime, configuration.desiredRetention)
            .let {
                SrsAnswers(
                    again = SrsAnswer(FsrsReviewRating.Again.grade, SrsCard(it.again)),
                    hard = SrsAnswer(FsrsReviewRating.Hard.grade, SrsCard(it.hard)),
                    good = SrsAnswer(FsrsReviewRating.Good.grade, SrsCard(it.good)),
                    easy = SrsAnswer(FsrsReviewRating.Easy.grade, SrsCard(it.easy)),
                    passFailMode = configuration.passFailMode
                )
            }
    }

}
