package ua.syt0r.kanji.core.srs.fsrs

import kotlinx.datetime.Instant
import kotlin.time.Duration

data class FsrsCard(
    val status: FsrsCardStatus,
    val params: FsrsCardParams,
    val interval: Duration,
    val lapses: Int,
    val repeats: Int,
    /** Current (re)learning step index, used only when learning steps are configured */
    val step: Int = 0
) {

    val lastReview: Instant? = when (params) {
        FsrsCardParams.New -> null
        is FsrsCardParams.Existing -> params.reviewTime
    }

}

sealed interface FsrsCardParams {

    object New : FsrsCardParams

    /**
     * @param stability long-term stability in days
     * @param shortStability short-term stability in days (FSRS-7 dual-stability state)
     */
    data class Existing(
        val difficulty: Double,
        val stability: Double,
        val shortStability: Double,
        val reviewTime: Instant
    ) : FsrsCardParams {

        val memoryState: Fsrs7.MemoryState
            get() = Fsrs7.MemoryState(
                longStability = stability,
                shortStability = shortStability,
                difficulty = difficulty
            )

    }

}

enum class FsrsCardStatus {
    New, Learning, Review, Relearning
}

enum class FsrsReviewRating(val grade: Int) {
    Again(1), Hard(2), Good(3), Easy(4);

    companion object {
        fun fromGrade(grade: Int): FsrsReviewRating = entries.first { it.grade == grade }
    }
}

data class FsrsAnswers(
    val again: FsrsCard,
    val hard: FsrsCard,
    val good: FsrsCard,
    val easy: FsrsCard
)
