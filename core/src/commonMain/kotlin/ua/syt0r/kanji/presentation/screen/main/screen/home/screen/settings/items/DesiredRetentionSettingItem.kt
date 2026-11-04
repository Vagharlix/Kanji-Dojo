package ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jetbrains.compose.resources.stringResource
import ua.syt0r.kanji.Res
import ua.syt0r.kanji.core.srs.fsrs.MAX_DESIRED_RETENTION
import ua.syt0r.kanji.core.srs.fsrs.MIN_DESIRED_RETENTION
import ua.syt0r.kanji.core.srs.fsrs.RECOMMENDED_MAX_DESIRED_RETENTION
import ua.syt0r.kanji.core.srs.fsrs.RECOMMENDED_MIN_DESIRED_RETENTION
import ua.syt0r.kanji.core.user_data.preferences.PreferencesContract
import ua.syt0r.kanji.presentation.common.MultiplatformDialog
import ua.syt0r.kanji.presentation.common.resources.string.resolveString
import ua.syt0r.kanji.presentation.screen.main.MainNavigationState
import ua.syt0r.kanji.presentation.screen.main.screen.home.screen.settings.SettingsScreenContract
import ua.syt0r.kanji.setting_desired_retention_description
import ua.syt0r.kanji.setting_desired_retention_high_warning
import ua.syt0r.kanji.setting_desired_retention_low_warning
import ua.syt0r.kanji.setting_desired_retention_item
import ua.syt0r.kanji.setting_desired_retention_value
import kotlin.math.roundToInt

class DesiredRetentionSettingItem(
    private val appPreferences: PreferencesContract.AppPreferences
) : SettingsScreenContract.ConfigurableListItem {

    private lateinit var retentionPercent: MutableState<Int>

    private val minPercent = (MIN_DESIRED_RETENTION * 100).roundToInt()
    private val maxPercent = (MAX_DESIRED_RETENTION * 100).roundToInt()

    override suspend fun prepare(coroutineScope: CoroutineScope) {
        retentionPercent = mutableStateOf(
            value = appPreferences.desiredRetentionPercent.get().coerceIn(minPercent, maxPercent)
        )
        appPreferences.desiredRetentionPercent.onModified
            .onEach { if (retentionPercent.value != it) retentionPercent.value = it }
            .launchIn(coroutineScope)
        snapshotFlow { retentionPercent.value }
            .drop(1)
            .onEach { appPreferences.desiredRetentionPercent.set(it) }
            .launchIn(coroutineScope)
    }

    @Composable
    override fun content(mainNavigationState: MainNavigationState) {

        var showDialog by remember { mutableStateOf(false) }
        val value = retentionPercent.value

        ListItem(
            headlineContent = {
                Text(text = stringResource(Res.string.setting_desired_retention_item))
            },
            supportingContent = {
                Text(text = stringResource(Res.string.setting_desired_retention_value, value))
            },
            modifier = Modifier.clip(MaterialTheme.shapes.medium)
                .fillMaxWidth()
                .clickable { showDialog = true },
        )

        if (showDialog) {
            var sliderValue by remember { mutableFloatStateOf(value.toFloat()) }

            MultiplatformDialog(
                onDismissRequest = { showDialog = false },
                title = {
                    Text(text = stringResource(Res.string.setting_desired_retention_item))
                },
                content = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "${sliderValue.roundToInt()}%",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            valueRange = minPercent.toFloat()..maxPercent.toFloat(),
                            steps = maxPercent - minPercent - 1
                        )
                        Text(
                            text = stringResource(Res.string.setting_desired_retention_description),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        val selected = sliderValue.roundToInt() / 100.0
                        val warning = when {
                            selected < RECOMMENDED_MIN_DESIRED_RETENTION ->
                                stringResource(Res.string.setting_desired_retention_low_warning)

                            selected > RECOMMENDED_MAX_DESIRED_RETENTION ->
                                stringResource(Res.string.setting_desired_retention_high_warning)

                            else -> null
                        }
                        if (warning != null) {
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                buttons = {
                    TextButton(onClick = { showDialog = false }) {
                        Text(resolveString { settings.pickerDialogCancel })
                    }
                    TextButton(
                        onClick = {
                            retentionPercent.value = sliderValue.roundToInt()
                            showDialog = false
                        }
                    ) {
                        Text(resolveString { settings.pickerDialogApply })
                    }
                }
            )
        }

    }

}
