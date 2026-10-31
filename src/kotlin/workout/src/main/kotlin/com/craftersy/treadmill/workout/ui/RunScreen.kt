package com.craftersy.treadmill.workout.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craftersy.treadmill.workout.R
import com.craftersy.treadmill.workout.format.Length
import com.craftersy.treadmill.workout.format.PlannedStep
import com.craftersy.treadmill.workout.format.formatClock
import com.craftersy.treadmill.workout.format.formatDistance
import com.craftersy.treadmill.workout.format.formatPace
import com.craftersy.treadmill.workout.format.paceOf
import com.craftersy.treadmill.workout.run.ActiveRun
import com.craftersy.treadmill.workout.run.Device
import com.craftersy.treadmill.workout.run.Phase
import com.craftersy.treadmill.workout.run.RunState
import com.craftersy.treadmill.workout.run.WorkoutRunner

/** The running workout: the step with its countdown ring, what comes next, live readings and Skip / Stop. */
@Composable
fun RunScreen(runner: WorkoutRunner, onClose: () -> Unit) {
    val st by runner.state.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }

    // Keep the screen on while the run screen is visible; the workout itself also runs with it off.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val glow = Ink.intensity(st.step?.speed ?: st.steps.firstOrNull()?.speed ?: com.craftersy.treadmill.protocol.Speed(30))
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.background)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(glow.copy(alpha = 0.22f), Color.Transparent), center = Offset(size.width / 2, size.height * 0.28f), radius = size.width * 0.9f),
                    radius = size.width * 0.9f, center = Offset(size.width / 2, size.height * 0.28f),
                )
            },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            TopBar(st)
            // Everything scrolls except Skip and Stop, which stay in reach.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(
                targetState = when {
                    st.finished -> 2
                    st.phase == Phase.RUNNING || (st.phase == Phase.STOPPING && st.step != null) -> 1
                    else -> 0
                },
                transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) },
                label = "phase",
            ) { phase ->
                when (phase) {
                    0 -> Waiting(st)
                    1 -> Running(st)
                    else -> Finished(st, onClose)
                }
            }
            if (!st.finished) {
                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth(), corner = 24.dp) {
                    Column(Modifier.padding(16.dp)) {
                        ProfileChart(st.steps, Modifier.fillMaxWidth().height(56.dp), current = st.index, progress = st.stepProgress)
                        Spacer(Modifier.height(10.dp))
                        Row {
                            Text(if (st.index < 0) pluralStringResource(R.plurals.steps_count, st.steps.size, st.steps.size) else stringResource(R.string.step_of, st.index + 1, st.steps.size), style = MaterialTheme.typography.labelMedium, color = Ink.textMuted, modifier = Modifier.weight(1f))
                            Text(formatClock(st.totalSeconds.toInt()), style = MaterialTheme.typography.labelMedium, color = Ink.textMuted)
                        }
                    }
                }
            }
            }
            if (!st.finished) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(stringResource(R.string.skip_step), runner::skip, Modifier.weight(1f), icon = AppIcons.SkipNext)
                    PrimaryButton(
                        if (st.phase == Phase.STOPPING) stringResource(R.string.stopping) else stringResource(R.string.stop),
                        { confirmStop = true },
                        Modifier.weight(1f),
                        icon = AppIcons.Stop,
                        color = Ink.danger,
                        contentColor = Color.White,
                        enabled = st.phase != Phase.STOPPING,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            containerColor = Ink.surfaceHigh,
            title = { Text(stringResource(R.string.end_title)) },
            text = { MutedText(stringResource(R.string.end_text)) },
            confirmButton = { TextButton({ confirmStop = false; runner.stop() }) { Text(stringResource(R.string.stop_belt), color = Ink.danger) } },
            dismissButton = { TextButton({ confirmStop = false }) { Text(stringResource(R.string.keep_running), color = Ink.text) } },
        )
    }
}

@Composable
private fun TopBar(st: RunState) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (st.finished) stringResource(R.string.summary) else stringResource(R.string.now_running), style = MaterialTheme.typography.labelSmall.copy(brush = Ink.heroGradient))
            Text(st.workout.name, style = MaterialTheme.typography.titleLarge, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (st.finished) return@Row
        val simulated = ActiveRun.device is Device.Simulator
        val (text, color) = when {
            simulated -> stringResource(R.string.simulator) to Color(0xFF8B7CFF)
            st.connected -> stringResource(R.string.connected) to Color(0xFF2DE2C4)
            st.phase == Phase.CONNECTING -> stringResource(R.string.connecting) to Ink.warning
            else -> stringResource(R.string.disconnected) to Ink.danger
        }
        Pill(text, icon = if (simulated) AppIcons.Chip else AppIcons.Bluetooth, color = color)
    }
}

@Composable
private fun Waiting(st: RunState) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max = 250.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            ProgressRing(if (st.phase == Phase.COUNTDOWN) 1f else 0f, Ink.accent, Modifier.fillMaxSize().alpha(pulse))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (st.phase == Phase.COUNTDOWN) stringResource(R.string.get_ready) else stringResource(R.string.connecting), style = MaterialTheme.typography.headlineMedium, color = Ink.text)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (st.phase == Phase.COUNTDOWN) stringResource(R.string.belt_starts) else messageText(st.message),
                    style = MaterialTheme.typography.bodyMedium, color = Ink.textMuted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 36.dp),
                )
            }
        }
        st.steps.firstOrNull()?.let {
            Spacer(Modifier.height(24.dp))
            UpNext(it, stringResource(R.string.first_up))
        }
    }
}

@Composable
private fun Running(st: RunState) {
    val step = st.step ?: return
    val color = Ink.intensity(step.speed)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(4.dp))
        Box(Modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            ProgressRing(st.stepProgress, color, Modifier.fillMaxSize(), stroke = 16.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 40.dp)) {
                Text(stepName(step), style = MaterialTheme.typography.titleLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Countdown(
                    text = when (step.step.length) {
                        is Length.Time -> formatClock(st.remaining)
                        is Length.Distance -> "${st.remaining}"
                    },
                    // Sized for the step's full length, so the digits do not jump as it counts down.
                    widest = when (val l = step.step.length) {
                        is Length.Time -> formatClock(l.seconds)
                        is Length.Distance -> "${l.metres}"
                    },
                )
                Text(
                    when (step.step.length) {
                        is Length.Time -> stringResource(R.string.remaining)
                        is Length.Distance -> stringResource(R.string.metres_to_go)
                    } + if (step.rounds > 1) " · " + stringResource(R.string.round_n, step.round, step.rounds) else "",
                    style = MaterialTheme.typography.bodyMedium, color = Ink.textMuted,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // The note comes first: if the user wrote one, it matters.
        step.step.note?.let { note ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.12f)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Note, null, tint = color, modifier = Modifier.size(20.dp))
                Text(note, style = MaterialTheme.typography.titleMedium, color = Ink.text, modifier = Modifier.padding(start = 12.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        Readings(st, step, color)
        if (st.message.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(messageText(st.message), style = MaterialTheme.typography.bodyMedium, color = if (st.messageIsError) Ink.danger else Ink.textMuted, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        st.next?.let { UpNext(it, stringResource(R.string.up_next)) } ?: UpNextLast()
    }
}

/** One card for the step's targets and the treadmill's live readings, so the step map fits on screen. */
/**
 * The big countdown inside the ring, on one line. Its size fits [widest], the longest value the step shows
 * ("10:00" needs less than "9:58", "1:00:00" less still), and shrinks further if a font is wider than expected.
 */
@Composable
private fun Countdown(text: String, widest: String) {
    val style = MaterialTheme.typography.displayLarge
    // Tabular digits are about 0.58 em wide and a colon about 0.3 em; 150 dp fit inside the ring.
    val ems = widest.sumOf { if (it == ':') 0.3 else 0.58 }
    val max = minOf(style.fontSize.value, (150 / ems).toFloat()).sp
    BasicText(
        text,
        style = style.copy(color = Ink.text, textAlign = TextAlign.Center),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = max, stepSize = 2.sp),
    )
}

@Composable
private fun Readings(st: RunState, step: PlannedStep, color: Color) {
    val s = st.status
    GlassCard(Modifier.fillMaxWidth(), corner = 24.dp) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row {
                Stat(stringResource(R.string.pace), "${formatPace(paceOf(step.speed))}/km", Modifier.weight(1.2f), icon = AppIcons.Bolt, tint = color)
                Stat(stringResource(R.string.speed), step.speed.toString(), Modifier.weight(1.1f), icon = AppIcons.Timer)
                Stat(stringResource(R.string.incline), "${step.incline}", Modifier.weight(0.8f), icon = AppIcons.Incline, tint = Ink.warning)
            }
            Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(Ink.outline))
            Row {
                Stat(stringResource(R.string.belt), s?.currentSpeed?.toString() ?: "–", Modifier.weight(1.2f))
                Stat(stringResource(R.string.distance), s?.let { formatDistance(it.distance) } ?: "–", Modifier.weight(1.1f))
                Stat(stringResource(R.string.heart), s?.heartRate?.takeIf { it > 0 }?.toString() ?: "–", Modifier.weight(0.8f), icon = AppIcons.Heart, tint = Ink.danger)
            }
        }
    }
}

@Composable
private fun UpNext(step: PlannedStep, label: String) {
    val color = Ink.intensity(step.speed)
    GlassCard(Modifier.fillMaxWidth(), corner = 22.dp) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(color, size = 10.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.textMuted)
                Text(stepName(step), style = MaterialTheme.typography.titleMedium, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    when (val l = step.step.length) {
                        is Length.Time -> formatClock(l.seconds)
                        is Length.Distance -> formatDistance(l.metres)
                    },
                    style = MaterialTheme.typography.titleMedium, color = Ink.text,
                )
                Text("${formatPace(paceOf(step.speed))}/km · ${step.incline}", style = MaterialTheme.typography.labelMedium, color = Ink.textMuted)
            }
        }
    }
}

@Composable
private fun UpNextLast() {
    GlassCard(Modifier.fillMaxWidth(), corner = 22.dp) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Check, null, tint = Ink.accent)
            Text(stringResource(R.string.last_step), style = MaterialTheme.typography.titleSmall, color = Ink.text, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun Finished(st: RunState, onClose: () -> Unit) {
    val complete = st.phase == Phase.COMPLETED
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(120.dp).clip(RoundedCornerShape(40.dp)).background(if (complete) Ink.accent else Ink.surfaceHigher),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (complete) AppIcons.Check else AppIcons.Stop, null, tint = if (complete) Ink.onAccent else Ink.text, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text(if (complete) stringResource(R.string.workout_complete) else stringResource(R.string.workout_ended), style = MaterialTheme.typography.headlineLarge, color = Ink.text)
        Spacer(Modifier.height(8.dp))
        Text(messageText(st.message), style = MaterialTheme.typography.bodyLarge, color = if (st.messageIsError) Ink.danger else Ink.textMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        GlassCard(Modifier.fillMaxWidth(), corner = 24.dp) {
            Column(Modifier.padding(20.dp)) {
                ProfileChart(st.steps, Modifier.fillMaxWidth().height(64.dp), current = if (complete) st.steps.size else st.index, progress = 0f)
                Spacer(Modifier.height(18.dp))
                Row {
                    Stat(stringResource(R.string.time), formatClock(st.totalSeconds.toInt()), Modifier.weight(1f), icon = AppIcons.Timer)
                    Stat(stringResource(R.string.distance), formatDistance(st.status?.distance ?: 0), Modifier.weight(1f), icon = AppIcons.Route)
                    Stat(stringResource(R.string.kcal), st.status?.calories?.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "–", Modifier.weight(0.8f), icon = AppIcons.Bolt)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(stringResource(R.string.done), onClose, Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
    }
}
