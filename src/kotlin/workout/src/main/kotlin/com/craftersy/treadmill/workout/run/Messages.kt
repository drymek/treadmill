package com.craftersy.treadmill.workout.run

/**
 * What the runner tells the user, as keys rather than sentences, so the
 * screen and the notification can show them in the app's language.
 */
sealed interface Msg {
    data object Searching : Msg
    data object Connected : Msg
    data object GetReady : Msg
    data object CoolingDown : Msg
    data object Complete : Msg
    data object StoppingBelt : Msg
    data object WorkoutStopped : Msg
    data object BeltStopped : Msg
    data object CouldNotStop : Msg
    data object BeltStoppedOnConsole : Msg
    data object ConnectionClosed : Msg
    data object ConnectionLost : Msg
    data object Reconnected : Msg
    data object NoAnswer : Msg
    data object NoStatus : Msg
    data object NotRunning : Msg
    data object NotConnected : Msg
    data object NotConfirmed : Msg
    data object CouldNotConnect : Msg

    /** No treadmill advertising [name] was found. */
    data class NotFound(val name: String) : Msg

    /** A message from below, e.g. the Bluetooth stack, shown as it is. */
    data class Text(val text: String) : Msg
}
