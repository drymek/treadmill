@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.craftersy.treadmill.workout.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craftersy.treadmill.workout.AppLocale
import com.craftersy.treadmill.workout.AppViewModel
import com.craftersy.treadmill.workout.Language
import com.craftersy.treadmill.workout.ImportFailure
import com.craftersy.treadmill.workout.R
import com.craftersy.treadmill.workout.RunSettings
import com.craftersy.treadmill.workout.data.StoredWorkout
import com.craftersy.treadmill.workout.format.estimate
import com.craftersy.treadmill.workout.format.expand
import com.craftersy.treadmill.workout.format.formatClock
import com.craftersy.treadmill.workout.format.formatDistance
import com.craftersy.treadmill.workout.run.Device

/** The workout library: every saved workout with Run and Share, plus creating and importing new ones. */
@Composable
fun LibraryScreen(model: AppViewModel, onRun: (String, Device) -> Unit, onShare: (String) -> Unit, onLanguage: (Language) -> Unit) {
    val workouts by model.workouts.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val loaded by model.repository.loaded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var addSheet by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    val settings by model.settings.collectAsStateWithLifecycle()
    var settingsSheet by remember { mutableStateOf(false) }
    var yamlFor by remember { mutableStateOf<StoredWorkout?>(null) }
    var deleteFor by remember { mutableStateOf<StoredWorkout?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importUri) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Header(workouts, settings, onSettings = { settingsSheet = true }) }
            if (loaded && workouts.isEmpty()) {
                item { EmptyLibrary(onCreate = { model.edit(null) }, onSamples = model::addSamples, onImport = { addSheet = true }) }
            }
            items(workouts, key = { it.id }) { w ->
                WorkoutCard(
                    stored = w,
                    onOpen = { model.edit(w.id) },
                    onRun = { onRun(w.id, settings.device) },
                    simulator = settings.simulator,
                    onShare = { onShare(w.id) },
                    onDuplicate = { model.duplicate(w.id) },
                    onYaml = { yamlFor = w },
                    onCopy = { copyToClipboard(context, w.workout.name, w.text) },
                    onDelete = { deleteFor = w },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (workouts.isNotEmpty()) Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.background.copy(alpha = 0.92f), Ink.background)))
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Lime stays reserved for Run; creating is the quieter action here.
            PrimaryButton(stringResource(R.string.new_workout), { model.edit(null) }, Modifier.weight(1f), icon = AppIcons.Add, color = Ink.text, contentColor = Ink.background)
            if (busy) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = Ink.accent, strokeWidth = 2.5.dp) }
            } else {
                RoundIconButton(AppIcons.Paste, stringResource(R.string.import_action), { addSheet = true }, size = 52.dp)
            }
        }
    }

    if (addSheet) {
        ImportSheet(
            onDismiss = { addSheet = false },
            onClipboard = {
                addSheet = false
                model.importText(readClipboard(context), R.string.source_clipboard)
            },
            onUrl = {
                addSheet = false
                urlDialog = true
            },
            onFile = {
                addSheet = false
                pickFile.launch(arrayOf("*/*"))
            },
        )
    }
    if (urlDialog) UrlDialog(onDismiss = { urlDialog = false }, onImport = { urlDialog = false; model.importUrl(it) })
    if (settingsSheet) {
        SettingsSheet(settings, model::updateSettings, AppLocale.get(context), onLanguage, onDismiss = { settingsSheet = false })
    }
    yamlFor?.let { w -> YamlDialog(w, onDismiss = { yamlFor = null }, onCopy = { copyToClipboard(context, w.workout.name, w.text) }) }
    deleteFor?.let { w ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            containerColor = Ink.surfaceHigh,
            title = { Text(stringResource(R.string.delete_title, w.workout.name)) },
            text = { MutedText(stringResource(R.string.delete_text)) },
            confirmButton = { TextButton({ deleteFor = null; model.delete(w.id) }) { Text(stringResource(R.string.delete), color = Ink.danger) } },
            dismissButton = { TextButton({ deleteFor = null }) { Text(stringResource(R.string.keep), color = Ink.text) } },
        )
    }
}

@Composable
private fun Header(workouts: List<StoredWorkout>, settings: RunSettings, onSettings: () -> Unit) {
    Column(Modifier.statusBarsPadding().padding(top = 16.dp, bottom = 10.dp, start = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.library_kicker),
                style = MaterialTheme.typography.labelSmall.copy(brush = Ink.heroGradient),
                modifier = Modifier.weight(1f),
            )
            // While the simulator is on, say so where Run is pressed; tapping it opens the settings.
            if (settings.simulator) {
                Pill(stringResource(R.string.simulator), Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onSettings), icon = AppIcons.Chip, color = Color(0xFF8B7CFF))
                Spacer(Modifier.width(8.dp))
            }
            RoundIconButton(AppIcons.Settings, stringResource(R.string.settings), onSettings, size = 40.dp, background = Color.Transparent, tint = Ink.textMuted)
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineLarge, color = Ink.text)
        Spacer(Modifier.height(4.dp))
        val minutes = workouts.sumOf { it.workout.estimate().seconds } / 60
        MutedText(
            if (workouts.isEmpty()) {
                stringResource(R.string.library_empty_subtitle)
            } else {
                pluralStringResource(R.plurals.library_summary, workouts.size, workouts.size, minutes.toInt())
            },
        )
    }
}

@Composable
private fun WorkoutCard(
    stored: StoredWorkout,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    simulator: Boolean,
    onShare: () -> Unit,
    onDuplicate: () -> Unit,
    onYaml: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val w = stored.workout
    val steps = remember(w) { w.expand() }
    val total = remember(w) { w.estimate() }
    var menu by remember { mutableStateOf(false) }
    GlassCard(modifier.fillMaxWidth(), onClick = onOpen) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(w.name, style = MaterialTheme.typography.headlineSmall, color = Ink.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    w.description?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    RoundIconButton(AppIcons.More, stringResource(R.string.more), { menu = true }, size = 36.dp, background = Color.Transparent, tint = Ink.textMuted)
                    DropdownMenu(menu, { menu = false }, containerColor = Ink.surfaceHigher, shape = RoundedCornerShape(18.dp)) {
                        MenuItem(stringResource(R.string.edit), AppIcons.Edit) { menu = false; onOpen() }
                        MenuItem(stringResource(R.string.duplicate), AppIcons.Duplicate) { menu = false; onDuplicate() }
                        MenuItem(stringResource(R.string.view_yaml), AppIcons.Code) { menu = false; onYaml() }
                        MenuItem(stringResource(R.string.copy_yaml), AppIcons.Copy) { menu = false; onCopy() }
                        MenuItem(stringResource(R.string.delete), AppIcons.Delete, Ink.danger) { menu = false; onDelete() }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            ProfileChart(steps, Modifier.fillMaxWidth().height(64.dp))
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(formatClock(total.seconds.toInt()), icon = AppIcons.Timer)
                Pill(formatDistance(((total.metres / 10).toInt()) * 10), icon = AppIcons.Route)
                Pill(pluralStringResource(R.plurals.steps_count, steps.size, steps.size), icon = AppIcons.Repeat)
                val maxIncline = steps.maxOf { it.incline.level }
                if (maxIncline > 0) Pill("$maxIncline", icon = AppIcons.Incline, color = Ink.warning)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(if (simulator) stringResource(R.string.simulate) else stringResource(R.string.run), onRun, Modifier.weight(1f), icon = AppIcons.Play)
                RoundIconButton(AppIcons.Share, stringResource(R.string.share), onShare, size = 52.dp)
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, color: Color = Ink.text, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = color, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(icon, null, tint = color, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun EmptyLibrary(onCreate: () -> Unit, onSamples: () -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current
    val preview = remember {
        com.craftersy.treadmill.workout.format.WorkoutParser.parse(com.craftersy.treadmill.workout.data.samples(context).first())
            .let { (it as? com.craftersy.treadmill.workout.format.ParseResult.Success)?.workout?.expand().orEmpty() }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp)) {
            ProfileChart(preview, Modifier.fillMaxWidth().height(110.dp))
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineMedium, color = Ink.text)
            Spacer(Modifier.height(8.dp))
            MutedText(stringResource(R.string.empty_text))
            Spacer(Modifier.height(22.dp))
            PrimaryButton(stringResource(R.string.design_workout), onCreate, Modifier.fillMaxWidth(), icon = AppIcons.Add)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.samples), onSamples, Modifier.weight(1f), icon = AppIcons.Bolt)
                SecondaryButton(stringResource(R.string.import_action), onImport, Modifier.weight(1f), icon = AppIcons.Paste)
            }
        }
    }
}

@Composable
private fun ImportSheet(onDismiss: () -> Unit, onClipboard: () -> Unit, onUrl: () -> Unit, onFile: () -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink.surface) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(stringResource(R.string.add_title), style = MaterialTheme.typography.headlineSmall, color = Ink.text)
            Spacer(Modifier.height(4.dp))
            MutedText(stringResource(R.string.add_text))
            Spacer(Modifier.height(18.dp))
            SheetOption(AppIcons.Paste, stringResource(R.string.paste_clipboard), stringResource(R.string.paste_clipboard_hint), Color(0xFF2DE2C4), onClipboard)
            SheetOption(AppIcons.Link, stringResource(R.string.import_url), stringResource(R.string.import_url_hint), Color(0xFF8B7CFF), onUrl)
            SheetOption(AppIcons.File, stringResource(R.string.open_file), stringResource(R.string.open_file_hint), Color(0xFFFF5FB1), onFile)
        }
    }
}

@Composable
private fun SheetOption(icon: ImageVector, title: String, subtitle: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.textMuted)
        }
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.surfaceHigh,
        title = { Text(stringResource(R.string.import_url)) },
        text = {
            OutlinedTextField(
                url, { url = it },
                singleLine = true,
                placeholder = { Text("https://example.com/plan.workout.yaml") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                colors = fieldColors(),
                shape = RoundedCornerShape(16.dp),
            )
        },
        confirmButton = { TextButton({ onImport(url) }, enabled = url.length > 8) { Text(stringResource(R.string.import_action), color = Ink.accent) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = Ink.text) } },
    )
}

@Composable
private fun YamlDialog(stored: StoredWorkout, onDismiss: () -> Unit, onCopy: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.surfaceHigh,
        title = { Text(stored.id, style = MaterialTheme.typography.titleMedium) },
        text = { YamlText(stored.text, Modifier.height(420.dp)) },
        confirmButton = { TextButton({ onCopy(); onDismiss() }) { Text(stringResource(R.string.copy), color = Ink.accent) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.close), color = Ink.text) } },
    )
}

/** YAML in a monospaced, scrollable code panel. */
@Composable
fun YamlText(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.background)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp, color = Color(0xFFCBD3E1))
    }
}

/** Lists every problem of a failed import. */
@Composable
fun ImportFailureDialog(failure: ImportFailure, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.surfaceHigh,
        title = { Text(stringResource(R.string.import_failed, stringResource(failure.source))) },
        text = {
            val lineLabel = stringResource(R.string.line_n)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (p in failure.problems) {
                    Row {
                        Dot(Ink.danger, Modifier.padding(top = 7.dp), size = 6.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            if (p.line > 0 || p.path.isNotEmpty()) {
                                Text(
                                    listOfNotNull(p.line.takeIf { it > 0 }?.let { lineLabel.format(it) }, p.path.ifEmpty { null }).joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium, color = Ink.textMuted,
                                )
                            }
                            Text(LocalContext.current.text(p.copy(path = "")), style = MaterialTheme.typography.bodyMedium, color = Ink.text)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok), color = Ink.accent) } },
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ink.accent,
    unfocusedBorderColor = Ink.outlineStrong,
    focusedLabelColor = Ink.accent,
    cursorColor = Ink.accent,
    focusedContainerColor = Ink.background,
    unfocusedContainerColor = Ink.background,
)

private fun readClipboard(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

fun copyToClipboard(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}
