package ua.syt0r.kanji.core.srs.use_case

import ua.syt0r.kanji.core.srs.SrsCardKey
import ua.syt0r.kanji.core.srs.SrsCardRepository
import ua.syt0r.kanji.core.time.TimeUtils

/**
 * Like Anki's "Forget": cards become new again. Review history is kept (it still counts in
 * stats and for optimization), FSRS starts from scratch after the reset.
 */
class ResetSrsProgressUseCase(
    private val srsCardRepository: SrsCardRepository,
    private val timeUtils: TimeUtils
) {

    suspend operator fun invoke(keys: Collection<SrsCardKey>) {
        if (keys.isEmpty()) return
        srsCardRepository.reset(keys.toSet(), timeUtils.now())
    }

}
