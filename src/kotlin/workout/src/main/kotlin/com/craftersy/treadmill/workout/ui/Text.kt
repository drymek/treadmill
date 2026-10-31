package com.craftersy.treadmill.workout.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.craftersy.treadmill.protocol.Speed
import com.craftersy.treadmill.workout.R
import com.craftersy.treadmill.workout.format.Issue
import com.craftersy.treadmill.workout.format.PlannedStep
import com.craftersy.treadmill.workout.format.Problem
import com.craftersy.treadmill.workout.format.formatPace
import com.craftersy.treadmill.workout.run.Msg

/** Run messages in the app's language, joined into one text. */
fun Context.text(messages: List<Msg>): String = messages.joinToString(" ") { text(it) }

fun Context.text(m: Msg): String = when (m) {
    Msg.Searching -> getString(R.string.m_searching)
    Msg.Connected -> getString(R.string.m_connected)
    Msg.GetReady -> getString(R.string.m_get_ready)
    Msg.CoolingDown -> getString(R.string.m_cooling_down)
    Msg.Complete -> getString(R.string.m_complete)
    Msg.StoppingBelt -> getString(R.string.m_stopping_belt)
    Msg.WorkoutStopped -> getString(R.string.m_workout_stopped)
    Msg.BeltStopped -> getString(R.string.m_belt_stopped)
    Msg.CouldNotStop -> getString(R.string.m_could_not_stop)
    Msg.BeltStoppedOnConsole -> getString(R.string.m_belt_stopped_console)
    Msg.ConnectionClosed -> getString(R.string.m_connection_closed)
    Msg.ConnectionLost -> getString(R.string.m_connection_lost)
    Msg.Reconnected -> getString(R.string.m_reconnected)
    Msg.NoAnswer -> getString(R.string.m_no_answer)
    Msg.NoStatus -> getString(R.string.m_no_status)
    Msg.NotRunning -> getString(R.string.m_not_running)
    Msg.NotConnected -> getString(R.string.m_not_connected)
    Msg.NotConfirmed -> getString(R.string.m_not_confirmed)
    Msg.CouldNotConnect -> getString(R.string.m_could_not_connect)
    is Msg.NotFound -> getString(R.string.m_not_found, m.name)
    is Msg.Text -> m.text
}

/** A validation problem in the app's language; parser problems stay in English, like the file's keys. */
fun Context.text(p: Problem): String {
    val message = when (val i = p.issue) {
        null -> p.message
        Issue.NameMissing -> getString(R.string.issue_name_missing)
        Issue.NoSteps -> getString(R.string.issue_no_steps)
        is Issue.RepeatTooFew -> getString(R.string.issue_repeat_too_few, i.times)
        Issue.RepeatEmpty -> getString(R.string.issue_repeat_empty)
        Issue.DurationZero -> getString(R.string.issue_duration_zero)
        Issue.DistanceZero -> getString(R.string.issue_distance_zero)
        Issue.DistanceNot10 -> getString(R.string.issue_distance_not_10)
        is Issue.PaceRange -> getString(R.string.issue_pace_range, formatPace(i.secondsPerKm), i.speed.toString(), Speed.MIN.toString(), Speed.MAX.toString())
        is Issue.SpeedRange -> getString(R.string.issue_speed_range, i.speed.toString(), Speed.MIN.toString(), Speed.MAX.toString())
        is Issue.InclineRange -> getString(R.string.issue_incline_range, i.level)
    }
    return if (p.path.isEmpty()) message else "${p.path}: $message"
}

/** A step's name, or "Step n" in the app's language. */
fun Context.name(step: PlannedStep): String = if (step.named) step.name else getString(R.string.step_n, step.number)

@Composable
fun messageText(messages: List<Msg>): String = LocalContext.current.text(messages)

@Composable
fun stepName(step: PlannedStep): String = LocalContext.current.name(step)
