package com.craftersy.treadmill.workout.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import com.craftersy.treadmill.client.Client
import com.craftersy.treadmill.client.ReconnectingTransport
import com.craftersy.treadmill.simulator.Simulator
import com.craftersy.treadmill.workout.AppLocale
import com.craftersy.treadmill.workout.MainActivity
import com.craftersy.treadmill.workout.R
import com.craftersy.treadmill.workout.bluetooth.BleOptions
import com.craftersy.treadmill.workout.bluetooth.BleTransport
import com.craftersy.treadmill.workout.format.Length
import com.craftersy.treadmill.workout.format.Workout
import com.craftersy.treadmill.workout.format.estimate
import com.craftersy.treadmill.workout.format.formatClock
import com.craftersy.treadmill.workout.format.formatDistance
import com.craftersy.treadmill.workout.format.formatPace
import com.craftersy.treadmill.workout.format.paceOf
import com.craftersy.treadmill.workout.ui.name
import com.craftersy.treadmill.workout.ui.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Where a workout runs. */
sealed interface Device {
    data class Treadmill(val name: String) : Device

    data object Simulator : Device
}

/** The workout running in this process, shared by the service and the screen. */
object ActiveRun {
    private val mutableRunner = MutableStateFlow<WorkoutRunner?>(null)
    val runner: StateFlow<WorkoutRunner?> = mutableRunner.asStateFlow()

    /** Where the current run goes. */
    @Volatile
    var device: Device = Device.Simulator
        private set

    internal fun set(r: WorkoutRunner?, d: Device) {
        device = d
        mutableRunner.value = r
    }

    /** Hides a finished run from the screen. */
    fun dismiss() {
        if (mutableRunner.value?.state?.value?.finished == true) mutableRunner.value = null
    }
}

/**
 * Runs the workout as a foreground service, so it keeps controlling the
 * treadmill when the app is in the background or the screen is locked. A
 * partial wake lock keeps the CPU awake for the step timers; the notification
 * shows the running step and offers Skip and Stop. Step changes vibrate and
 * beep, so the screen can stay off.
 */
class WorkoutService : Service() {
    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tones: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SKIP -> ActiveRun.runner.value?.skip()
            ACTION_STOP -> {
                val runner = ActiveRun.runner.value
                // A notification left behind by an ended run must still go away when Stop is pressed.
                if (runner == null || runner.state.value.finished) dismiss() else runner.stop()
            }
            ACTION_START -> {
                val workout = pending ?: run { stopSelf(); return START_NOT_STICKY }
                val device = pendingDevice ?: Device.Simulator
                pending = null
                begin(workout, device)
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(workout: Workout, device: Device) {
        if (ActiveRun.runner.value?.state?.value?.finished == false) return
        createChannels()
        startForeground(NOTIFICATION_ID, notification(null, workout), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        val minutes = workout.estimate().seconds / 60
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "workout:run")
            .apply { acquire(((minutes + 30) * 60_000).toLong()) }
        tones = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()

        val context = applicationContext
        val runner = WorkoutRunner(s, workout, connect = { onEvent ->
            when (device) {
                is Device.Simulator -> Client(Simulator(s), s)
                is Device.Treadmill -> {
                    val transport = ReconnectingTransport.dial(s, onChange = onEvent) {
                        BleTransport.dial(context, BleOptions(name = device.name))
                    }
                    Client(transport, s)
                }
            }
        })
        ActiveRun.set(runner, device)
        runner.start()

        val manager = getSystemService(NotificationManager::class.java)
        s.launch {
            // Step changes and the end get a cue; the notification follows every second.
            runner.state.distinctUntilChangedBy { it.index to it.phase }.collect { st ->
                if (st.index >= 0 || st.finished) cue(final = st.finished)
            }
        }
        s.launch {
            runner.state.takeWhile { !it.finished }.collect { st ->
                manager.notify(NOTIFICATION_ID, notification(st, workout))
                delay(1.seconds)
            }
            finish(runner.state.value)
        }
    }

    /**
     * The workout has ended, for whatever reason: the notification goes first, so nothing can leave it behind,
     * and the summary stays on the run screen. Then the connection is closed, never waiting for it for long.
     */
    private suspend fun finish(st: RunState) {
        dismiss(stopService = false)
        // Give a reconnecting transport a minute to come back, so the runner can stop a belt left running.
        if (st.phase == Phase.ENDED && !st.connected) delay(1.minutes)
        withTimeoutOrNull(5.seconds) { ActiveRun.runner.value?.client?.close() }
        stopSelf()
    }

    /** Removes the notification and leaves the foreground. */
    private fun dismiss(stopService: Boolean = true) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (stopService) stopSelf()
    }

    private fun cue(final: Boolean) {
        runCatching {
            val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
            val pattern = if (final) longArrayOf(0, 300, 120, 300, 120, 500) else longArrayOf(0, 180, 90, 180)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
        tones?.startTone(if (final) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP2, 400)
    }

    private fun notification(st: RunState?, workout: Workout): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val step = st?.step
        val b = Notification.Builder(this, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setColor(0xFFC6FF4A.toInt())
        when {
            st == null || st.phase == Phase.CONNECTING -> b.setContentTitle(workout.name).setContentText(st?.let { text(it.message) } ?: getString(R.string.n_connecting)).setOngoing(true)
            // Braking after the last step or after Stop: say so instead of a step counting below zero.
            st.phase == Phase.STOPPING || st.finished -> b.setContentTitle(workout.name).setContentText(text(st.message)).setOngoing(true)
            step == null -> b.setContentTitle(workout.name).setContentText(text(st.message).ifEmpty { getString(R.string.m_get_ready) }).setOngoing(true)
            else -> {
                val target = getString(R.string.n_target, formatPace(paceOf(step.speed)), step.speed.toString(), step.incline.level)
                val next = st.next?.let { getString(R.string.n_next, name(it), formatPace(paceOf(it.speed))) } ?: getString(R.string.n_last)
                b.setContentTitle("${name(step)} · ${remainingText(st)}")
                    .setContentText(target)
                    .setSubText(getString(R.string.step_of, st.index + 1, st.steps.size))
                    .setStyle(Notification.BigTextStyle().bigText("$target\n$next${step.step.note?.let { "\n$it" } ?: ""}"))
                    .setProgress(1000, (st.stepProgress * 1000).toInt(), false)
                    .setOngoing(true)
                    .addAction(Notification.Action.Builder(null, getString(R.string.skip_step), service(ACTION_SKIP)).build())
                    .addAction(Notification.Action.Builder(null, getString(R.string.stop), service(ACTION_STOP)).build())
                if (step.step.length is Length.Time) {
                    b.setUsesChronometer(true).setChronometerCountDown(true)
                        .setWhen(System.currentTimeMillis() + st.remaining * 1000L)
                        .setShowWhen(true)
                }
            }
        }
        return b.build()
    }

    private fun remainingText(st: RunState): String = when (st.step?.step?.length) {
        is Length.Time -> getString(R.string.n_left, formatClock(st.remaining))
        is Length.Distance -> getString(R.string.n_left, formatDistance(st.remaining))
        null -> ""
    }

    private fun service(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, WorkoutService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, getString(R.string.channel_run), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_run_text)
                setShowBadge(false)
            },
        )
        // Earlier versions posted a summary in its own channel; the summary now lives on the run screen only.
        manager.deleteNotificationChannel("done")
    }

    override fun onDestroy() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        scope?.cancel()
        tones?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.craftersy.treadmill.workout.START"
        private const val ACTION_SKIP = "com.craftersy.treadmill.workout.SKIP"
        private const val ACTION_STOP = "com.craftersy.treadmill.workout.STOP"
        private const val CHANNEL_RUN = "run"
        private const val NOTIFICATION_ID = 1

        // Handed over in memory: a workout is small, and the service runs in the app's process.
        @Volatile private var pending: Workout? = null

        @Volatile private var pendingDevice: Device? = null

        /** Starts [workout] on [device]. The caller holds the Bluetooth permissions. */
        /** Removes a workout notification no run owns any more, e.g. one left by a process the system killed. */
        fun clearStale(context: Context) {
            if (ActiveRun.runner.value?.state?.value?.finished == false) return
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }

        fun start(context: Context, workout: Workout, device: Device) {
            pending = workout
            pendingDevice = device
            context.startForegroundService(Intent(context, WorkoutService::class.java).setAction(ACTION_START))
        }
    }
}
