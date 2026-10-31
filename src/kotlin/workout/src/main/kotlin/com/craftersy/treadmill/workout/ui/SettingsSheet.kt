@file:OptIn(ExperimentalMaterial3Api::class)

package com.craftersy.treadmill.workout.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.craftersy.treadmill.protocol.Ble
import com.craftersy.treadmill.workout.Language
import com.craftersy.treadmill.workout.R
import com.craftersy.treadmill.workout.RunSettings

/** Settings that rarely change: the treadmill's Bluetooth name and the simulator. */
@Composable
fun SettingsSheet(
    settings: RunSettings,
    onChange: (RunSettings) -> Unit,
    language: Language,
    onLanguage: (Language) -> Unit,
    onDismiss: () -> Unit,
) {
    var scan by rememberSaveable { mutableStateOf(false) }
    if (scan) {
        ScanDialog(onPick = { onChange(settings.copy(treadmillName = it)); scan = false }, onDismiss = { scan = false })
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink.surface) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall, color = Ink.text)

            SectionLabel(stringResource(R.string.language), Modifier.padding(top = 6.dp))
            Segmented(
                // Language names stay in their own language, so they can be found whatever the current one is.
                listOf(Language.SYSTEM to stringResource(R.string.language_system), Language.ENGLISH to "English", Language.POLISH to "Polski"),
                language, onLanguage, Modifier.fillMaxWidth(),
            )

            SectionLabel(stringResource(R.string.treadmill), Modifier.padding(top = 6.dp))
            OutlinedTextField(
                settings.treadmillName, { onChange(settings.copy(treadmillName = it)) },
                label = { Text(stringResource(R.string.bluetooth_name)) },
                leadingIcon = { Icon(AppIcons.Bluetooth, null, Modifier.size(20.dp)) },
                supportingText = { Text(stringResource(R.string.bluetooth_name_hint, DEFAULT)) },
                trailingIcon = {
                    if (settings.treadmillName != DEFAULT) {
                        TextButton({ onChange(settings.copy(treadmillName = DEFAULT)) }) { Text(stringResource(R.string.reset), color = Ink.accent) }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
                shape = RoundedCornerShape(16.dp),
            )
            SecondaryButton(
                stringResource(R.string.scan_action), { scan = true }, Modifier.fillMaxWidth(), icon = AppIcons.Search,
            )

            SectionLabel(stringResource(R.string.testing))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onChange(settings.copy(simulator = !settings.simulator)) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Chip, null, tint = Ink.textMuted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.simulator), style = MaterialTheme.typography.titleMedium, color = Ink.text)
                    Text(
                        stringResource(R.string.simulator_hint),
                        style = MaterialTheme.typography.bodySmall, color = Ink.textMuted,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = settings.simulator,
                    onCheckedChange = { onChange(settings.copy(simulator = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Ink.accent, checkedThumbColor = Ink.onAccent),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

private const val DEFAULT = Ble.DEVICE_NAME
