package com.craftersy.treadmill.workout.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craftersy.treadmill.protocol.Speed
import com.craftersy.treadmill.workout.AppViewModel
import com.craftersy.treadmill.workout.R
import com.craftersy.treadmill.workout.Screen
import com.craftersy.treadmill.workout.format.Entry
import com.craftersy.treadmill.workout.format.Length
import com.craftersy.treadmill.workout.format.PACE_RANGE
import com.craftersy.treadmill.workout.format.Step
import com.craftersy.treadmill.workout.format.Target
import com.craftersy.treadmill.workout.format.Workout
import com.craftersy.treadmill.workout.format.WorkoutWriter
import com.craftersy.treadmill.workout.format.estimate
import com.craftersy.treadmill.workout.format.expand
import com.craftersy.treadmill.workout.format.formatClock
import com.craftersy.treadmill.workout.format.formatDistance
import com.craftersy.treadmill.workout.format.formatPace
import com.craftersy.treadmill.workout.format.paceOf
import com.craftersy.treadmill.workout.format.paceToTenths
import com.craftersy.treadmill.workout.format.validate

/** Position of a step: the top-level entry and, inside a repeat block, the step within it. */
private data class StepPath(val entry: Int, val inner: Int = -1) {
    val key: String get() = "$entry.$inner"
}

/** Designs a workout: every option of the format, with a live profile, totals and the resulting YAML. */
@Composable
fun EditorScreen(model: AppViewModel) {
    val workout by model.draft.collectAsStateWithLifecycle()
    val screen by model.screen.collectAsStateWithLifecycle()
    val isNew = (screen as? Screen.Editor)?.id == null
    var yaml by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val problems = remember(workout) { workout.validate() }
    val update: (Workout) -> Unit = model::updateDraft
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(AppIcons.Back, stringResource(R.string.back), model::closeEditor, background = Color.Transparent)
            Text(
                if (isNew) stringResource(R.string.new_workout) else stringResource(R.string.edit_workout),
                style = MaterialTheme.typography.titleLarge, color = Ink.text,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            PrimaryButton(stringResource(R.string.save), model::save, Modifier.height(44.dp), icon = AppIcons.Check, enabled = problems.isEmpty())
        }
        Segmented(
            listOf(false to stringResource(R.string.tab_design), true to stringResource(R.string.tab_yaml)), yaml, { yaml = it },
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth(),
        )

        if (yaml) {
            val text = remember(workout) { WorkoutWriter.write(workout) }
            Column(Modifier.weight(1f).padding(20.dp)) {
                YamlText(text, Modifier.weight(1f))
                Spacer(Modifier.height(12.dp))
                SecondaryButton(stringResource(R.string.copy_yaml), { copyToClipboard(context, workout.name, text) }, Modifier.fillMaxWidth(), icon = AppIcons.Copy)
            }
            return@Column
        }

        // The incline each step runs at the first time, for "keeps n" hints.
        val inclines = remember(workout) { firstInclines(workout) }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") { Header(workout, update) }
            item(key = "summary") { Summary(workout) }
            if (problems.isNotEmpty()) item(key = "problems") { Problems(problems.map { context.text(it) }) }
            item(key = "steps-label") { SectionLabel(stringResource(R.string.steps), Modifier.padding(top = 8.dp)) }
            // Keyed by position, so typing into a step does not recreate its card.
            itemsIndexed(workout.entries, key = { i, _ -> "entry-$i" }) { i, entry ->
                val last = workout.entries.lastIndex
                when (entry) {
                    is Entry.Single -> StepCard(
                        step = entry.step,
                        incline = inclines[StepPath(i)] ?: 0,
                        expanded = expanded == StepPath(i).key,
                        onToggle = { expanded = if (expanded == StepPath(i).key) null else StepPath(i).key },
                        onChange = { update(workout.replaceEntry(i, Entry.Single(it))) },
                        actions = listOf(
                            Action(stringResource(R.string.move_up), AppIcons.Up, i > 0) { update(workout.moveEntry(i, -1)); expanded = StepPath(i - 1).key },
                            Action(stringResource(R.string.move_down), AppIcons.Down, i < last) { update(workout.moveEntry(i, 1)); expanded = StepPath(i + 1).key },
                            Action(stringResource(R.string.duplicate), AppIcons.Duplicate) { update(workout.insertEntry(i + 1, entry)) },
                            Action(stringResource(R.string.repeat), AppIcons.Repeat) { update(workout.replaceEntry(i, Entry.Repeat(2, listOf(entry.step)))) },
                            Action(stringResource(R.string.delete), AppIcons.Delete, tint = Ink.danger) { update(workout.removeEntry(i)); expanded = null },
                        ),
                        modifier = Modifier.animateItem(),
                    )
                    is Entry.Repeat -> RepeatCard(
                        block = entry,
                        index = i,
                        inclines = inclines,
                        expanded = expanded,
                        onExpand = { expanded = it },
                        onChange = { update(workout.replaceEntry(i, it)) },
                        actions = listOf(
                            Action(stringResource(R.string.move_up), AppIcons.Up, i > 0) { update(workout.moveEntry(i, -1)) },
                            Action(stringResource(R.string.move_down), AppIcons.Down, i < last) { update(workout.moveEntry(i, 1)) },
                            Action(stringResource(R.string.duplicate), AppIcons.Duplicate) { update(workout.insertEntry(i + 1, entry)) },
                            Action(stringResource(R.string.unwrap), AppIcons.Close) { update(workout.unwrap(i)) },
                            Action(stringResource(R.string.delete), AppIcons.Delete, tint = Ink.danger) { update(workout.removeEntry(i)); expanded = null },
                        ),
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            item(key = "add") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    SecondaryButton(stringResource(R.string.add_step), {
                        update(workout.insertEntry(workout.entries.size, Entry.Single(nextStep(workout))))
                        expanded = StepPath(workout.entries.size).key
                    }, Modifier.weight(1f), icon = AppIcons.Add)
                    val fast = stringResource(R.string.default_fast)
                    val recover = stringResource(R.string.default_recover)
                    SecondaryButton(stringResource(R.string.add_repeat), {
                        update(
                            workout.insertEntry(
                                workout.entries.size,
                                Entry.Repeat(4, listOf(Step(fast, Length.Time(60), Target.Pace(270)), Step(recover, Length.Time(90), Target.Pace(420)))),
                            ),
                        )
                    }, Modifier.weight(1f), icon = AppIcons.Repeat)
                }
            }
        }
    }
}

@Composable
private fun Header(workout: Workout, update: (Workout) -> Unit) {
    Column {
        BareField(
            value = workout.name,
            onValueChange = { update(workout.copy(name = it)) },
            placeholder = stringResource(R.string.workout_name),
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(6.dp))
        BareField(
            value = workout.description.orEmpty(),
            onValueChange = { update(workout.copy(description = it.ifEmpty { null })) },
            placeholder = stringResource(R.string.add_description),
            style = MaterialTheme.typography.bodyLarge.copy(color = Ink.textMuted),
            singleLine = false,
        )
    }
}

@Composable
private fun Summary(workout: Workout) {
    val steps = remember(workout) { workout.expand() }
    val total = remember(workout) { workout.estimate() }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            if (steps.isEmpty()) {
                MutedText(stringResource(R.string.profile_empty))
            } else {
                ProfileChart(steps, Modifier.fillMaxWidth().height(96.dp))
            }
            Spacer(Modifier.height(18.dp))
            Row {
                Stat(stringResource(R.string.time), formatClock(total.seconds.toInt()), Modifier.weight(1f), icon = AppIcons.Timer)
                Stat(stringResource(R.string.distance), formatDistance(((total.metres / 10).toInt()) * 10), Modifier.weight(1f), icon = AppIcons.Route)
                Stat(stringResource(R.string.steps), "${steps.size}", Modifier.weight(0.7f), icon = AppIcons.Repeat)
            }
        }
    }
}

@Composable
private fun Problems(problems: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Ink.danger.copy(alpha = 0.08f))
            .border(1.dp, Ink.danger.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.fix_before_saving), style = MaterialTheme.typography.titleSmall, color = Ink.danger)
        problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.text) }
    }
}

private data class Action(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val enabled: Boolean = true,
    val tint: Color = Ink.text,
    val run: () -> Unit,
)

@Composable
private fun ActionRow(actions: List<Action>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (a in actions) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = a.enabled, onClick = a.run)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val color = if (a.enabled) a.tint else Ink.textFaint
                Icon(a.icon, a.label, Modifier.size(20.dp), tint = color)
                Spacer(Modifier.height(2.dp))
                Text(a.label, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }
}

/** A step: a one-line summary that opens into every field of the step. */
@Composable
private fun StepCard(
    step: Step,
    incline: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (Step) -> Unit,
    actions: List<Action>,
    modifier: Modifier = Modifier,
    nested: Boolean = false,
) {
    val color = Ink.intensity(step.target.speed)
    GlassCard(
        modifier.fillMaxWidth(),
        corner = if (nested) 20.dp else 24.dp,
        color = if (nested) Ink.surfaceHigh else Ink.surface,
        border = if (expanded) color.copy(alpha = 0.5f) else Ink.outline,
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onToggle).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(5.dp).fillMaxHeight().clip(CircleShape).background(color))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        step.name?.ifEmpty { null } ?: stringResource(R.string.unnamed_step),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (step.name.isNullOrEmpty()) Ink.textMuted else Ink.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOf(lengthText(step.length), targetText(step.target), inclineText(step.incline, incline)).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium, color = Ink.textMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(if (expanded) AppIcons.Up else AppIcons.Down, null, tint = Ink.textMuted)
            }
            if (expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    OutlinedTextField(
                        step.name.orEmpty(), { onChange(step.copy(name = it.ifEmpty { null })) },
                        label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(), shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    LengthEditor(step.length) { onChange(step.copy(length = it)) }
                    TargetEditor(step.target) { onChange(step.copy(target = it)) }
                    InclineEditor(step.incline, incline) { onChange(step.copy(incline = it)) }
                    OutlinedTextField(
                        step.note.orEmpty(), { onChange(step.copy(note = it.ifEmpty { null })) },
                        label = { Text(stringResource(R.string.note_on_screen)) }, modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(AppIcons.Note, null, Modifier.size(18.dp)) },
                        colors = fieldColors(), shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    ActionRow(actions)
                }
            }
        }
    }
}

@Composable
private fun RepeatCard(
    block: Entry.Repeat,
    index: Int,
    inclines: Map<StepPath, Int>,
    expanded: String?,
    onExpand: (String?) -> Unit,
    onChange: (Entry.Repeat) -> Unit,
    actions: List<Action>,
    modifier: Modifier = Modifier,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Ink.surface)
            .border(1.dp, Ink.heroGradient, RoundedCornerShape(28.dp)),
    ) {
        Column(Modifier.padding(12.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Repeat, null, tint = Ink.text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.repeat), style = MaterialTheme.typography.titleMedium, color = Ink.text)
                    Text(pluralStringResource(R.plurals.steps_per_round, block.steps.size, block.steps.size), style = MaterialTheme.typography.bodySmall, color = Ink.textMuted)
                }
                Stepper(
                    value = block.times,
                    format = { "× $it" },
                    parse = { it.removePrefix("×").trim().toIntOrNull() },
                    onChange = { onChange(block.copy(times = it.coerceIn(2, 99))) },
                    minus = { (it - 1).coerceAtLeast(2) },
                    plus = { (it + 1).coerceAtMost(99) },
                    width = 64.dp,
                )
                RoundIconButton(AppIcons.More, stringResource(R.string.block_actions), { menu = !menu }, size = 36.dp, background = Color.Transparent, tint = Ink.textMuted)
            }
            AnimatedVisibility(menu) { ActionRow(actions.map { a -> a.copy(run = { menu = false; a.run() }) }) }
            block.steps.forEachIndexed { j, step ->
                val path = StepPath(index, j)
                val last = block.steps.lastIndex
                StepCard(
                    step = step,
                    incline = inclines[path] ?: 0,
                    expanded = expanded == path.key,
                    onToggle = { onExpand(if (expanded == path.key) null else path.key) },
                    onChange = { onChange(block.copy(steps = block.steps.toMutableList().also { l -> l[j] = it })) },
                    actions = listOf(
                        Action(stringResource(R.string.up), AppIcons.Up, j > 0) { onChange(block.copy(steps = block.steps.move(j, -1))); onExpand(StepPath(index, j - 1).key) },
                        Action(stringResource(R.string.down), AppIcons.Down, j < last) { onChange(block.copy(steps = block.steps.move(j, 1))); onExpand(StepPath(index, j + 1).key) },
                        Action(stringResource(R.string.duplicate), AppIcons.Duplicate) { onChange(block.copy(steps = block.steps.toMutableList().also { it.add(j + 1, step) })) },
                        Action(stringResource(R.string.delete), AppIcons.Delete, block.steps.size > 1, tint = Ink.danger) {
                            onChange(block.copy(steps = block.steps.toMutableList().also { it.removeAt(j) }))
                            onExpand(null)
                        },
                    ),
                    nested = true,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        onChange(block.copy(steps = block.steps + (block.steps.lastOrNull()?.copy(name = null, note = null) ?: Step())))
                        onExpand(StepPath(index, block.steps.size).key)
                    }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Add, null, tint = Ink.textMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_step_to_block), style = MaterialTheme.typography.titleSmall, color = Ink.textMuted)
            }
        }
    }
}

@Composable
private fun LengthEditor(length: Length, onChange: (Length) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel(stringResource(R.string.length))
        Segmented(
            listOf(true to stringResource(R.string.time), false to stringResource(R.string.distance)), length is Length.Time,
            {
                onChange(
                    when {
                        it && length is Length.Distance -> Length.Time(300)
                        !it && length is Length.Time -> Length.Distance(1000)
                        else -> length
                    },
                )
            },
            Modifier.fillMaxWidth(),
        )
        when (length) {
            is Length.Time -> Stepper(
                value = length.seconds,
                format = ::formatClock,
                parse = ::parseClock,
                onChange = { onChange(Length.Time(it)) },
                minus = { (it - if (it > 120) 30 else 10).coerceAtLeast(10) },
                plus = { it + if (it >= 120) 30 else 10 },
                unit = if (length.seconds >= 3600) stringResource(R.string.unit_hms) else stringResource(R.string.unit_ms),
                keyboard = KeyboardType.Number,
            )
            is Length.Distance -> Stepper(
                value = length.metres,
                format = { it.toString() },
                parse = { it.trim().toIntOrNull()?.takeIf { m -> m > 0 } },
                onChange = { onChange(Length.Distance(it)) },
                minus = { (it - if (it > 400) 100 else 50).coerceAtLeast(50) },
                plus = { it + if (it >= 400) 100 else 50 },
                unit = stringResource(R.string.unit_metres),
                keyboard = KeyboardType.Number,
                hint = if (length.metres % 10 != 0) stringResource(R.string.multiple_of_10) else formatDistance(length.metres),
            )
        }
    }
}

@Composable
private fun TargetEditor(target: Target, onChange: (Target) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel(stringResource(R.string.target))
        Segmented(
            listOf(true to stringResource(R.string.pace), false to stringResource(R.string.speed)), target is Target.Pace,
            { pace ->
                onChange(
                    when {
                        pace && target is Target.Velocity -> Target.Pace(paceOf(target.speed).coerceIn(PACE_RANGE))
                        !pace && target is Target.Pace -> Target.Velocity(target.speed.tenths.coerceIn(10, 220))
                        else -> target
                    },
                )
            },
            Modifier.fillMaxWidth(),
        )
        when (target) {
            is Target.Pace -> {
                val speed = Speed(paceToTenths(target.secondsPerKm.coerceAtLeast(1)))
                val actual = paceOf(speed)
                Stepper(
                    value = target.secondsPerKm,
                    format = ::formatPace,
                    parse = { s -> parseClock(s)?.takeIf { it > 0 } },
                    onChange = { onChange(Target.Pace(it)) },
                    minus = { (it - 5).coerceAtLeast(PACE_RANGE.first) },
                    plus = { (it + 5).coerceAtMost(PACE_RANGE.last) },
                    unit = "min/km",
                    keyboard = KeyboardType.Number,
                    accent = Ink.intensity(speed),
                    hint = if (speed in Speed.MIN..Speed.MAX) {
                        if (actual != target.secondsPerKm) stringResource(R.string.runs_at_actually, speed.toString(), formatPace(actual)) else stringResource(R.string.runs_at, speed.toString())
                    } else {
                        stringResource(R.string.outside_range, Speed.MIN.toString(), Speed.MAX.toString())
                    },
                )
            }
            is Target.Velocity -> Stepper(
                value = target.tenths,
                format = { "${it / 10}.${it % 10}" },
                parse = { s -> s.replace(',', '.').toBigDecimalOrNull()?.let { (it * 10.toBigDecimal()).toInt() } },
                onChange = { onChange(Target.Velocity(it)) },
                minus = { (it - 1).coerceAtLeast(10) },
                plus = { (it + 1).coerceAtMost(220) },
                unit = "km/h",
                keyboard = KeyboardType.Decimal,
                accent = Ink.intensity(target.speed),
                hint = if (target.speed in Speed.MIN..Speed.MAX) stringResource(R.string.pace_is, formatPace(paceOf(target.speed))) else stringResource(R.string.outside_range, Speed.MIN.toString(), Speed.MAX.toString()),
            )
        }
    }
}

@Composable
private fun InclineEditor(incline: Int?, inherited: Int, onChange: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FieldLabel(stringResource(R.string.incline))
                Text(
                    if (incline == null) stringResource(R.string.incline_keeps, inherited) else stringResource(R.string.incline_level, incline),
                    style = MaterialTheme.typography.bodyMedium, color = Ink.textMuted,
                )
            }
            Text(stringResource(R.string.incline_set), style = MaterialTheme.typography.bodySmall, color = Ink.textMuted)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = incline != null,
                onCheckedChange = { onChange(if (it) inherited else null) },
                colors = SwitchDefaults.colors(checkedTrackColor = Ink.accent, checkedThumbColor = Ink.onAccent),
            )
        }
        AnimatedVisibility(incline != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = (incline ?: inherited).toFloat(),
                    onValueChange = { onChange(it.toInt()) },
                    valueRange = 0f..22f,
                    steps = 21,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Ink.warning, activeTrackColor = Ink.warning, inactiveTrackColor = Ink.surfaceHigher, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                )
                Spacer(Modifier.width(12.dp))
                Text("${incline ?: inherited}", style = MaterialTheme.typography.headlineSmall, color = Ink.text, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.textMuted)
}

/** A value with − and + buttons around a field that also takes typing; typing is committed once it parses. */
@Composable
private fun Stepper(
    value: Int,
    format: (Int) -> String,
    parse: (String) -> Int?,
    onChange: (Int) -> Unit,
    minus: (Int) -> Int,
    plus: (Int) -> Int,
    modifier: Modifier = Modifier,
    unit: String? = null,
    hint: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    accent: Color = Ink.text,
    width: androidx.compose.ui.unit.Dp? = null,
) {
    var text by remember { mutableStateOf(format(value)) }
    LaunchedEffect(value) { if (parse(text) != value) text = format(value) }
    val compact = width != null
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(AppIcons.Remove, stringResource(R.string.less), { onChange(minus(value)) }, size = if (compact) 32.dp else 48.dp)
            Column(
                (if (width != null) Modifier.width(width) else Modifier.weight(1f)).padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { t -> text = t; parse(t)?.let(onChange) },
                    singleLine = true,
                    textStyle = (if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.displaySmall)
                        .copy(color = accent, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Ink.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (unit != null) Text(unit, style = MaterialTheme.typography.labelSmall, color = Ink.textFaint)
            }
            RoundIconButton(AppIcons.Add, stringResource(R.string.more), { onChange(plus(value)) }, size = if (compact) 32.dp else 48.dp)
        }
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Ink.textMuted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

/** A text field without decoration, for the workout's name and description. */
@Composable
private fun BareField(value: String, onValueChange: (String) -> Unit, placeholder: String, style: TextStyle, singleLine: Boolean = true) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = style.copy(color = if (style.color == Color.Unspecified) Ink.text else style.color),
        singleLine = singleLine,
        cursorBrush = SolidColor(Ink.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Ink.textFaint))
                inner()
            }
        },
    )
}

private fun lengthText(l: Length) = when (l) {
    is Length.Time -> formatClock(l.seconds)
    is Length.Distance -> formatDistance(l.metres)
}

private fun targetText(t: Target) = when (t) {
    is Target.Pace -> "${formatPace(t.secondsPerKm)}/km"
    is Target.Velocity -> t.speed.toString()
}

@Composable
private fun inclineText(incline: Int?, inherited: Int) =
    if (incline == null) stringResource(R.string.incline_inherited_short, inherited) else stringResource(R.string.incline_short, incline)

/** "6:30" → 390, "1:02:05" → 3725, "12" → 720 (minutes). */
fun parseClock(s: String): Int? {
    val parts = s.trim().split(':')
    if (parts.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 3 }) return null
    val n = parts.map { it.toInt() }
    return when (n.size) {
        1 -> n[0] * 60
        2 -> if (n[1] < 60) n[0] * 60 + n[1] else null
        3 -> if (n[1] < 60 && n[2] < 60) n[0] * 3600 + n[1] * 60 + n[2] else null
        else -> null
    }?.takeIf { it > 0 }
}

/** The incline each step runs at in the first round, by its position. */
private fun firstInclines(w: Workout): Map<StepPath, Int> {
    val out = mutableMapOf<StepPath, Int>()
    var incline = 0
    w.entries.forEachIndexed { i, e ->
        when (e) {
            is Entry.Single -> {
                // A step without an incline shows the level it keeps.
                out[StepPath(i)] = e.step.incline ?: incline
                e.step.incline?.let { incline = it }
            }
            is Entry.Repeat -> e.steps.forEachIndexed { j, s ->
                out[StepPath(i, j)] = s.incline ?: incline
                s.incline?.let { incline = it }
            }
        }
    }
    return out
}

/** A sensible next step: the last step's target, five minutes long. */
private fun nextStep(w: Workout): Step {
    val last = when (val e = w.entries.lastOrNull()) {
        is Entry.Single -> e.step
        is Entry.Repeat -> e.steps.lastOrNull()
        null -> null
    }
    return Step(length = Length.Time(300), target = last?.target ?: Target.Pace(390))
}

private fun Workout.replaceEntry(i: Int, e: Entry) = copy(entries = entries.toMutableList().also { it[i] = e })

private fun Workout.insertEntry(i: Int, e: Entry) = copy(entries = entries.toMutableList().also { it.add(i, e) })

private fun Workout.removeEntry(i: Int) = copy(entries = entries.toMutableList().also { it.removeAt(i) })

private fun Workout.moveEntry(i: Int, by: Int) = copy(entries = entries.move(i, by))

private fun Workout.unwrap(i: Int): Workout {
    val block = entries[i] as? Entry.Repeat ?: return this
    return copy(entries = entries.toMutableList().also { it.removeAt(i); it.addAll(i, block.steps.map(Entry::Single)) })
}

private fun <T> List<T>.move(i: Int, by: Int): List<T> {
    val j = i + by
    if (j !in indices) return this
    return toMutableList().also { val x = it.removeAt(i); it.add(j, x) }
}
