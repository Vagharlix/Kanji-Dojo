package ua.syt0r.kanji.core.srs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.module.Module
import ua.syt0r.kanji.core.srs.fsrs.DefaultFsrsConfigurationRepository
import ua.syt0r.kanji.core.srs.fsrs.DefaultFsrsScheduler
import ua.syt0r.kanji.core.srs.fsrs.Fsrs7
import ua.syt0r.kanji.core.srs.fsrs.FsrsConfigurationRepository
import ua.syt0r.kanji.core.srs.fsrs.FsrsScheduler
import ua.syt0r.kanji.core.srs.use_case.ApplyFsrsParametersUseCase
import ua.syt0r.kanji.core.srs.use_case.DefaultGetSrsStatusUseCase
import ua.syt0r.kanji.core.srs.use_case.FsrsOptimizer
import ua.syt0r.kanji.core.srs.use_case.GetSrsStatusUseCase

fun Module.applySrsDefinitions() {

    single<DailyLimitManager> {
        DefaultDailyLimitManager(
            appPreferences = get()
        )
    }

    single<LetterSrsManager> {
        DefaultLetterSrsManager(
            dailyLimitManager = get(),
            practiceRepository = get(),
            srsCardRepository = get(),
            reviewHistoryRepository = get(),
            timeUtils = get(),
            appPreferences = get(),
            coroutineScope = CoroutineScope(Dispatchers.IO)
        )
    }

    factory {
        FsrsOptimizer(reviewHistoryRepository = get(), fsrsConfigurationRepository = get())
    }

    factory {
        ApplyFsrsParametersUseCase(
            appPreferences = get(),
            fsrsConfigurationRepository = get(),
            reviewHistoryRepository = get(),
            srsCardRepository = get()
        )
    }

    factory<GetSrsStatusUseCase> {
        DefaultGetSrsStatusUseCase(timeUtils = get())
    }

    single<SrsCardRepository> {
        DefaultSrsCardRepository(fsrsCardRepository = get())
    }

    factory<SrsScheduler> {
        DefaultSrsScheduler(fsrsScheduler = get(), fsrsConfigurationRepository = get())
    }
    single<FsrsConfigurationRepository> {
        DefaultFsrsConfigurationRepository(appPreferences = get())
    }
    single<Fsrs7> { Fsrs7() }
    factory<FsrsScheduler> { DefaultFsrsScheduler(fsrs = get()) }

    single<VocabSrsManager> {
        DefaultVocabSrsManager(
            practiceRepository = get(),
            srsCardRepository = get(),
            dailyLimitManager = get(),
            timeUtils = get(),
            appPreferences = get(),
            reviewHistoryRepository = get(),
            coroutineScope = CoroutineScope(Dispatchers.IO)
        )
    }

}