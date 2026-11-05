package ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings

import org.koin.core.qualifier.qualifier
import org.koin.dsl.module
import ua.syt0r.kanji.presentation.multiplatformViewModel
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.items.DefaultHomeTabSettingItem
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.items.ThemeSettingItem
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.items.DailyResetTimeSettingItem
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.items.DesiredRetentionSettingItem
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.items.FsrsParametersSettingItem
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.items.FsrsStepsSettingItem
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.items.LearnAheadSettingItem
import ua.syt0r.kanji.Res
import ua.syt0r.kanji.core.user_data.preferences.PreferencesContract
import ua.syt0r.kanji.setting_fsrs_learning_steps_item
import ua.syt0r.kanji.setting_fsrs_relearning_steps_item
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.items.MaxIntervalSettingItem
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.items.PassFailSettingItem

val defaultSettingItemsQualifier = qualifier("default_setting_items")
val settingItemsQualifier = qualifier("setting_items")

val settingsScreenModule = module {

    multiplatformViewModel<SettingsScreenContract.ViewModel> {
        SettingsScreenViewModel(
            coroutineScope = it.component1(),
            defaultSettingItems = get(defaultSettingItemsQualifier),
            customSettingItems = get(settingItemsQualifier)
        )
    }

    factory(defaultSettingItemsQualifier) {
        listOf(
            ThemeSettingItem(themeManager = get()),
            DefaultHomeTabSettingItem(appPreferences = get()),
            DailyResetTimeSettingItem(appPreferences = get()),
            DesiredRetentionSettingItem(appPreferences = get()),
            FsrsParametersSettingItem(
                appPreferences = get(),
                fsrsOptimizer = get(),
                applyFsrsParametersUseCase = get()
            ),
            FsrsStepsSettingItem(
                property = get<PreferencesContract.AppPreferences>().fsrsLearningSteps,
                title = Res.string.setting_fsrs_learning_steps_item,
                placeholder = "1m 10m"
            ),
            FsrsStepsSettingItem(
                property = get<PreferencesContract.AppPreferences>().fsrsRelearningSteps,
                title = Res.string.setting_fsrs_relearning_steps_item,
                placeholder = "10m"
            ),
            LearnAheadSettingItem(appPreferences = get()),
            MaxIntervalSettingItem(appPreferences = get()),
            PassFailSettingItem(appPreferences = get())
        )
    }

    factory(settingItemsQualifier) { listOf<SettingsScreenContract.ListItem>() }

}