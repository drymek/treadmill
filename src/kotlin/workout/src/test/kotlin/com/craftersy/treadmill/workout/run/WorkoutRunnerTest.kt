package com.craftersy.treadmill.workout.run

import com.craftersy.treadmill.client.Client
import com.craftersy.treadmill.protocol.Command
import com.craftersy.treadmill.protocol.Commands
import com.craftersy.treadmill.protocol.State
import com.craftersy.treadmill.simulator.Simulator
import com.craftersy.treadmill.workout.format.Entry
import com.craftersy.treadmill.workout.format.Length
import com.craftersy.treadmill.workout.format.Step
import com.craftersy.treadmill.workout.format.Target
import com.craftersy.treadmill.workout.format.Workout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WorkoutRunnerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private val workout = Workout(
        name = "Test",
        entries = listOf(
            Entry.Single(Step("Warm-up", Length.Time(1), Target.Velocity(30), incline = 1)),
            Entry.Repeat(2, listOf(Step("Fast", Length.Distance(20), Target.Pace(360)))),
            Entry.Single(Step("Cool-down", Length.Time(1), Target.Velocity(40), incline = 0)),
        ),
    )

    private fun runner(sim: Simulator) =
        WorkoutRunner(scope, workout, { Client(sim, scope) }, tick = 10.milliseconds, commandTimeout = 2.seconds)

    /** Speed and incline of every set-speed command the simulator received. */
    private fun Simulator.targets(): List<Pair<Int, Int>> = writes().map { Command.decode(it) }
        .filter { it.group == Commands.GROUP_SETTINGS }
        .map { it.data[0] to it.data[1] }

    private fun Simulator.stopped(): Boolean = writes().any { it.contentEquals(Command.stop()) }

    @Test
    fun runsEveryStepAndStopsTheBelt() = runBlocking {
        val sim = Simulator(scope, tick = 5.milliseconds)
        val r = runner(sim)
        r.start()
        val end = withTimeout(20.seconds) { r.state.first { it.finished } }
        assertEquals(Phase.COMPLETED, end.phase, end.message.toString())
        // 6:00/km is 10.0 km/h; the repeat block keeps incline 1 from the warm-up.
        assertEquals(listOf(30 to 1, 100 to 1, 100 to 1, 40 to 0), sim.targets())
        assertTrue(sim.stopped())
    }

    @Test
    fun stopEndsTheWorkoutAndStopsTheBelt() = runBlocking {
        val sim = Simulator(scope, tick = 5.milliseconds)
        val r = runner(sim)
        r.start()
        withTimeout(10.seconds) { r.state.first { it.phase == Phase.RUNNING } }
        r.stop()
        val end = withTimeout(10.seconds) { r.state.first { it.finished } }
        assertEquals(Phase.ENDED, end.phase)
        assertEquals(listOf(Msg.BeltStopped), end.message)
        assertTrue(sim.stopped())
    }

    @Test
    fun beltStoppedOnTheConsoleEndsTheWorkout() = runBlocking {
        val sim = Simulator(scope, tick = 5.milliseconds)
        val slow = workout.copy(entries = listOf(Entry.Single(Step("Long", Length.Time(60), Target.Velocity(30)))))
        val r = WorkoutRunner(scope, slow, { Client(sim, scope) }, tick = 10.milliseconds)
        r.start()
        withTimeout(10.seconds) { r.state.first { it.phase == Phase.RUNNING } }
        sim.write(Command.stop()) // as if STOP was pressed on the console
        val end = withTimeout(10.seconds) { r.state.first { it.finished } }
        assertEquals(Phase.ENDED, end.phase)
        assertEquals(listOf(Msg.BeltStoppedOnConsole), end.message)
    }

    @Test
    fun skipMovesToTheNextStep() = runBlocking {
        val sim = Simulator(scope, tick = 5.milliseconds)
        val long = workout.copy(
            entries = listOf(
                Entry.Single(Step("Long", Length.Time(600), Target.Velocity(30))),
                Entry.Single(Step("Short", Length.Time(1), Target.Velocity(50), incline = 2)),
            ),
        )
        val r = WorkoutRunner(scope, long, { Client(sim, scope) }, tick = 10.milliseconds)
        r.start()
        withTimeout(10.seconds) { r.state.first { it.phase == Phase.RUNNING && it.index == 0 } }
        r.skip()
        withTimeout(10.seconds) { r.state.first { it.index == 1 } }
        val end = withTimeout(10.seconds) { r.state.first { it.finished } }
        assertEquals(Phase.COMPLETED, end.phase, end.message.toString())
        assertEquals(listOf(30 to 0, 50 to 2), sim.targets())
    }

    @Test
    fun connectionFailureEndsWithTheReason() = runBlocking {
        val r = WorkoutRunner(scope, workout, { throw java.io.IOException("Treadmill not found.") })
        r.start()
        val end = withTimeout(5.seconds) { r.state.first { it.finished } }
        assertEquals(listOf(Msg.Text("Treadmill not found.")), end.message)
        assertTrue(end.messageIsError)
        assertEquals(null, end.status?.state?.takeIf { it == State.RUNNING })
    }
}
