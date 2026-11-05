package ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ua.syt0r.kanji.Res
import ua.syt0r.kanji.core.srs.fsrs.Fsrs7
import ua.syt0r.kanji.core.srs.fsrs.Fsrs7Optimizer
import ua.syt0r.kanji.core.srs.fsrs.FsrsParameters
import ua.syt0r.kanji.core.srs.fsrs.FsrsSteps
import ua.syt0r.kanji.core.srs.fsrs.MAX_LEARN_AHEAD_MINUTES
import ua.syt0r.kanji.core.srs.fsrs.MAX_MAX_INTERVAL_DAYS
import ua.syt0r.kanji.core.srs.fsrs.MIN_MAX_INTERVAL_DAYS
import ua.syt0r.kanji.core.srs.use_case.ApplyFsrsParametersUseCase
import ua.syt0r.kanji.core.srs.use_case.FsrsOptimizationResult
import ua.syt0r.kanji.core.srs.use_case.FsrsOptimizer
import ua.syt0r.kanji.core.user_data.preferences.PreferencesContract
import ua.syt0r.kanji.presentation.common.MultiplatformDialog
import ua.syt0r.kanji.presentation.common.resources.string.formatDecimal
import ua.syt0r.kanji.presentation.common.resources.string.resolveString
import ua.syt0r.kanji.presentation.screen.main.MainNavigationState
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.SettingsScreenContract
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.SettingsSwitchRow
import ua.syt0r.kanji.setting_fsrs_max_interval_description
import ua.syt0r.kanji.setting_fsrs_max_interval_invalid
import ua.syt0r.kanji.setting_fsrs_max_interval_item
import ua.syt0r.kanji.setting_fsrs_max_interval_value
import ua.syt0r.kanji.setting_fsrs_parameters_applying
import ua.syt0r.kanji.setting_fsrs_parameters_custom
import ua.syt0r.kanji.setting_fsrs_parameters_default
import ua.syt0r.kanji.setting_fsrs_parameters_description
import ua.syt0r.kanji.setting_fsrs_parameters_invalid
import ua.syt0r.kanji.setting_fsrs_parameters_item
import ua.syt0r.kanji.setting_fsrs_parameters_metrics
import ua.syt0r.kanji.setting_fsrs_parameters_not_enough_data
import ua.syt0r.kanji.setting_fsrs_parameters_not_improved
import ua.syt0r.kanji.setting_fsrs_parameters_optimize
import ua.syt0r.kanji.setting_fsrs_parameters_optimized
import ua.syt0r.kanji.setting_fsrs_parameters_optimizing
import ua.syt0r.kanji.setting_fsrs_parameters_reschedule
import ua.syt0r.kanji.setting_fsrs_parameters_reset
import ua.syt0r.kanji.setting_fsrs_parameters_save
import ua.syt0r.kanji.setting_fsrs_steps_description
import ua.syt0r.kanji.setting_fsrs_steps_invalid
import ua.syt0r.kanji.setting_fsrs_steps_none
import ua.syt0r.kanji.setting_learn_ahead_description
import ua.syt0r.kanji.setting_learn_ahead_item
import ua.syt0r.kanji.setting_learn_ahead_value
import ua.syt0r.kanji.setting_pass_fail_description
import ua.syt0r.kanji.setting_pass_fail_item

/** Only Again / Good buttons, like Anki's "Pass Fail" add-on. */
class PassFailSettingItem(
    private val appPreferences: PreferencesContract.AppPreferences
) : SettingsScreenContract.ConfigurableListItem {

    private lateinit var enabled: MutableState<Boolean>
    private lateinit var scope: CoroutineScope

    override suspend fun prepare(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        enabled = mutableStateOf(appPreferences.passFailMode.get())
        appPreferences.passFailMode.onModified
            .onEach { enabled.value = it }
            .launchIn(coroutineScope)
    }

    @Composable
    override fun content(mainNavigationState: MainNavigationState) {
        SettingsSwitchRow(
            title = stringResource(Res.string.setting_pass_fail_item),
            message = stringResource(Res.string.setting_pass_fail_description),
            isEnabled = enabled.value,
            onToggled = {
                val newValue = !enabled.value
                enabled.value = newValue
                scope.launch { appPreferences.passFailMode.set(newValue) }
            }
        )
    }

}

class MaxIntervalSettingItem(
    private val appPreferences: PreferencesContract.AppPreferences
) : SettingsScreenContract.ConfigurableListItem {

    private lateinit var days: MutableState<Int>
    private lateinit var scope: CoroutineScope

    override suspend fun prepare(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        days = mutableStateOf(appPreferences.fsrsMaxIntervalDays.get())
        appPreferences.fsrsMaxIntervalDays.onModified
            .onEach { days.value = it }
            .launchIn(coroutineScope)
    }

    @Composable
    override fun content(mainNavigationState: MainNavigationState) {
        var showDialog by remember { mutableStateOf(false) }

        ListItem(
            headlineContent = { Text(stringResource(Res.string.setting_fsrs_max_interval_item)) },
            supportingContent = {
                Text(stringResource(Res.string.setting_fsrs_max_interval_value, days.value))
            },
            modifier = Modifier.clip(MaterialTheme.shapes.medium)
                .fillMaxWidth()
                .clickable { showDialog = true }
        )

        if (!showDialog) return

        var text by remember { mutableStateOf(days.value.toString()) }
        val parsed = text.trim().toIntOrNull()
            ?.takeIf { it in MIN_MAX_INTERVAL_DAYS..MAX_MAX_INTERVAL_DAYS }

        MultiplatformDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(Res.string.setting_fsrs_max_interval_item)) },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() }.take(5) },
                        singleLine = true,
                        isError = parsed == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = if (parsed == null) {
                            stringResource(
                                Res.string.setting_fsrs_max_interval_invalid,
                                MIN_MAX_INTERVAL_DAYS,
                                MAX_MAX_INTERVAL_DAYS
                            )
                        } else {
                            stringResource(Res.string.setting_fsrs_max_interval_description)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (parsed == null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            buttons = {
                TextButton(onClick = { showDialog = false }) {
                    Text(resolveString { settings.pickerDialogCancel })
                }
                TextButton(
                    enabled = parsed != null,
                    onClick = {
                        val value = parsed ?: return@TextButton
                        days.value = value
                        scope.launch { appPreferences.fsrsMaxIntervalDays.set(value) }
                        showDialog = false
                    }
                ) {
                    Text(resolveString { settings.pickerDialogApply })
                }
            }
        )
    }

}

/** View, edit, optimize and reset FSRS-7 parameters, similar to Anki's FSRS section. */
class FsrsParametersSettingItem(
    private val appPreferences: PreferencesContract.AppPreferences,
    private val fsrsOptimizer: FsrsOptimizer,
    private val applyFsrsParametersUseCase: ApplyFsrsParametersUseCase
) : SettingsScreenContract.ConfigurableListItem {

    private sealed interface OptimizationState {
        data object Idle : OptimizationState
        data class Running(val progress: Float) : OptimizationState
        data class Done(val result: FsrsOptimizationResult) : OptimizationState
        data class NotEnoughData(val reviews: Int, val required: Int) : OptimizationState
    }

    private lateinit var scope: CoroutineScope
    private lateinit var storedParameters: MutableState<String>

    private val optimizationState = mutableStateOf<OptimizationState>(OptimizationState.Idle)
    private val isApplying = mutableStateOf(false)
    private var optimizationJob: Job? = null

    override suspend fun prepare(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        storedParameters = mutableStateOf(appPreferences.fsrsParameters.get())
        appPreferences.fsrsParameters.onModified
            .onEach { storedParameters.value = it }
            .launchIn(coroutineScope)
    }

    @Composable
    override fun content(mainNavigationState: MainNavigationState) {
        var showDialog by remember { mutableStateOf(false) }
        val isCustom = FsrsParameters.parse(storedParameters.value) != null

        ListItem(
            headlineContent = { Text(stringResource(Res.string.setting_fsrs_parameters_item)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (isCustom) Res.string.setting_fsrs_parameters_custom
                        else Res.string.setting_fsrs_parameters_default
                    )
                )
            },
            modifier = Modifier.clip(MaterialTheme.shapes.medium)
                .fillMaxWidth()
                .clickable {
                    optimizationState.value = OptimizationState.Idle
                    showDialog = true
                }
        )

        if (showDialog) {
            ParametersDialog(onDismiss = {
                optimizationJob?.cancel()
                showDialog = false
            })
        }
    }

    @Composable
    private fun ParametersDialog(onDismiss: () -> Unit) {
        val initialText = remember {
            FsrsParameters.format(
                FsrsParameters.parse(storedParameters.value) ?: Fsrs7.DEFAULT_PARAMETERS
            )
        }
        var text by remember { mutableStateOf(initialText) }
        var reschedule by remember { mutableStateOf(false) }
        val parsed = FsrsParameters.parse(text)
        val state = optimizationState.value
        val busy = state is OptimizationState.Running || isApplying.value

        MultiplatformDialog(
            onDismissRequest = { if (!isApplying.value) onDismiss() },
            title = { Text(stringResource(Res.string.setting_fsrs_parameters_item)) },
            content = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(Res.string.setting_fsrs_parameters_description),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        enabled = !busy,
                        isError = parsed == null,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        supportingText = if (parsed == null) {
                            {
                                Text(
                                    stringResource(
                                        Res.string.setting_fsrs_parameters_invalid,
                                        Fsrs7.PARAMETERS_COUNT
                                    )
                                )
                            }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { startOptimization { text = FsrsParameters.format(it) } }
                        ) {
                            Text(stringResource(Res.string.setting_fsrs_parameters_optimize))
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { text = FsrsParameters.format(Fsrs7.DEFAULT_PARAMETERS) }
                        ) {
                            Text(stringResource(Res.string.setting_fsrs_parameters_reset))
                        }
                    }

                    when (state) {
                        OptimizationState.Idle -> Unit
                        is OptimizationState.Running -> {
                            Text(
                                stringResource(Res.string.setting_fsrs_parameters_optimizing),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        is OptimizationState.NotEnoughData -> Text(
                            text = stringResource(
                                Res.string.setting_fsrs_parameters_not_enough_data,
                                state.reviews,
                                state.required
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        is OptimizationState.Done -> OptimizationResult(state.result)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(MaterialTheme.shapes.small)
                            .clickable(enabled = !busy) { reschedule = !reschedule }
                    ) {
                        Checkbox(
                            checked = reschedule,
                            enabled = !busy,
                            onCheckedChange = { reschedule = it }
                        )
                        Text(
                            text = stringResource(Res.string.setting_fsrs_parameters_reschedule),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (isApplying.value) {
                        Text(
                            stringResource(Res.string.setting_fsrs_parameters_applying),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            buttons = {
                TextButton(enabled = !isApplying.value, onClick = onDismiss) {
                    Text(resolveString { settings.pickerDialogCancel })
                }
                TextButton(
                    enabled = parsed != null && !busy,
                    onClick = {
                        val parameters = parsed ?: return@TextButton
                        isApplying.value = true
                        scope.launch {
                            val isDefault = parameters == Fsrs7.clip(Fsrs7.DEFAULT_PARAMETERS).toList()
                            applyFsrsParametersUseCase(
                                parameters = parameters.takeUnless { isDefault },
                                reschedule = reschedule
                            )
                            isApplying.value = false
                            onDismiss()
                        }
                    }
                ) {
                    Text(stringResource(Res.string.setting_fsrs_parameters_save))
                }
            }
        )
    }

    @Composable
    private fun OptimizationResult(result: FsrsOptimizationResult) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    if (result.isImprovement) Res.string.setting_fsrs_parameters_optimized
                    else Res.string.setting_fsrs_parameters_not_improved
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (result.isImprovement) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    Res.string.setting_fsrs_parameters_metrics,
                    result.optimized.reviewCount,
                    formatDecimal(result.current.logLoss, 4),
                    formatDecimal(result.optimized.logLoss, 4),
                    formatDecimal(result.current.rmseBins * 100, 2),
                    formatDecimal(result.optimized.rmseBins * 100, 2)
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    private fun startOptimization(onImproved: (List<Double>) -> Unit) {
        optimizationJob?.cancel()
        optimizationState.value = OptimizationState.Running(0f)
        optimizationJob = scope.launch {
            optimizationState.value = try {
                val result = fsrsOptimizer.optimize { progress ->
                    optimizationState.value = OptimizationState.Running(progress)
                }
                if (result.isImprovement) onImproved(result.parameters)
                OptimizationState.Done(result)
            } catch (e: Fsrs7Optimizer.NotEnoughDataException) {
                OptimizationState.NotEnoughData(e.reviews, e.required)
            }
        }
    }

}

/** Anki-style learning or relearning steps, e.g. "1m 10m". Empty lets FSRS-7 decide. */
class FsrsStepsSettingItem(
    private val property: ua.syt0r.kanji.core.suspended_property.SuspendedProperty<String>,
    private val title: org.jetbrains.compose.resources.StringResource,
    private val placeholder: String
) : SettingsScreenContract.ConfigurableListItem {

    private lateinit var value: MutableState<String>
    private lateinit var scope: CoroutineScope

    override suspend fun prepare(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        value = mutableStateOf(property.get())
        property.onModified
            .onEach { value.value = it }
            .launchIn(coroutineScope)
    }

    @Composable
    override fun content(mainNavigationState: MainNavigationState) {
        var showDialog by remember { mutableStateOf(false) }
        val steps = FsrsSteps.parse(value.value).orEmpty()

        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = {
                Text(
                    if (steps.isEmpty()) stringResource(Res.string.setting_fsrs_steps_none)
                    else FsrsSteps.format(steps)
                )
            },
            modifier = Modifier.clip(MaterialTheme.shapes.medium)
                .fillMaxWidth()
                .clickable { showDialog = true }
        )

        if (!showDialog) return

        var text by remember { mutableStateOf(FsrsSteps.format(steps)) }
        val parsed = FsrsSteps.parse(text)

        MultiplatformDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(title)) },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        isError = parsed == null,
                        placeholder = { Text(placeholder) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            if (parsed == null) Res.string.setting_fsrs_steps_invalid
                            else Res.string.setting_fsrs_steps_description
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (parsed == null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            buttons = {
                TextButton(onClick = { showDialog = false }) {
                    Text(resolveString { settings.pickerDialogCancel })
                }
                TextButton(
                    enabled = parsed != null,
                    onClick = {
                        val newValue = FsrsSteps.format(parsed ?: return@TextButton)
                        value.value = newValue
                        scope.launch { property.set(newValue) }
                        showDialog = false
                    }
                ) {
                    Text(resolveString { settings.pickerDialogApply })
                }
            }
        )
    }

}

/** Anki's learn ahead limit (Preferences → Review), in minutes. */
class LearnAheadSettingItem(
    private val appPreferences: PreferencesContract.AppPreferences
) : SettingsScreenContract.ConfigurableListItem {

    private lateinit var minutes: MutableState<Int>
    private lateinit var scope: CoroutineScope

    override suspend fun prepare(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        minutes = mutableStateOf(appPreferences.learnAheadMinutes.get())
        appPreferences.learnAheadMinutes.onModified
            .onEach { minutes.value = it }
            .launchIn(coroutineScope)
    }

    @Composable
    override fun content(mainNavigationState: MainNavigationState) {
        var showDialog by remember { mutableStateOf(false) }

        ListItem(
            headlineContent = { Text(stringResource(Res.string.setting_learn_ahead_item)) },
            supportingContent = {
                Text(stringResource(Res.string.setting_learn_ahead_value, minutes.value))
            },
            modifier = Modifier.clip(MaterialTheme.shapes.medium)
                .fillMaxWidth()
                .clickable { showDialog = true }
        )

        if (!showDialog) return

        var text by remember { mutableStateOf(minutes.value.toString()) }
        val parsed = text.trim().toIntOrNull()?.takeIf { it in 0..MAX_LEARN_AHEAD_MINUTES }

        MultiplatformDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(Res.string.setting_learn_ahead_item)) },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() }.take(4) },
                        singleLine = true,
                        isError = parsed == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(Res.string.setting_learn_ahead_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            buttons = {
                TextButton(onClick = { showDialog = false }) {
                    Text(resolveString { settings.pickerDialogCancel })
                }
                TextButton(
                    enabled = parsed != null,
                    onClick = {
                        val value = parsed ?: return@TextButton
                        minutes.value = value
                        scope.launch { appPreferences.learnAheadMinutes.set(value) }
                        showDialog = false
                    }
                ) {
                    Text(resolveString { settings.pickerDialogApply })
                }
            }
        )
    }

}
