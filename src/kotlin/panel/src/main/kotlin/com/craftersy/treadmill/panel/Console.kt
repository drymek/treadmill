package com.craftersy.treadmill.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craftersy.treadmill.protocol.State

object Palette {
    val background = Color(0xFF0B0B0C)
    val led = Color(0xFFFF3B30)
    val ledDim = Color(0xFF5A1E1B)
    val ledOff = Color(0xFF241010)
    val label = Color(0xFF8E8E93)
    val frame = Color(0xFF3A3A3C)
    val speed = Color(0xFF30B0C7)
    val incline = Color(0xFFFFD60A)
    val start = Color(0xFF34C759)
    val stop = Color(0xFFFF453A)
    val brand = Color(0xFFE5E5EA)
    val text = Color(0xFFD1D1D6)
}

/** The virtual HMS console: the display with all readouts at once, and the console buttons. */
@Composable
fun Console(state: PanelState, onPress: (Action, Int) -> Unit, onQuit: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Keep the screen on while connected: the console is useless when the phone locks mid-run.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .safeDrawingPadding()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val native = event.nativeKeyEvent
                when (val cmd = keyCommand(native.keyCode, native.unicodeChar)) {
                    is KeyCommand.Press -> onPress(cmd.action, cmd.value)
                    KeyCommand.Quit -> onQuit()
                    null -> return@onKeyEvent false
                }
                true
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(state, onHelp = { onPress(Action.HELP, 0) })
        if (state.link == Link.LOST) LostBanner()
        Display(state)
        Buttons(onPress)
        Text(
            state.note,
            color = if (state.noteIsError) Palette.stop else Palette.text,
            fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        )
        if (state.help) Help()
    }
}

@Composable
private fun Header(state: PanelState, onHelp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("HMS", color = Palette.brand, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("  ·  ${state.device}", color = Palette.label, fontSize = 15.sp, maxLines = 1)
        Spacer(Modifier.weight(1f))
        val (text, color) = when (state.link) {
            Link.CONNECTING -> "○ searching…" to Palette.label
            Link.CONNECTED -> "● connected" to Palette.start
            Link.LOST -> "● reconnecting…" to Palette.stop
            Link.FAILED -> "● not connected" to Palette.stop
        }
        Text(text, color = color, fontSize = 14.sp)
        TextButton(onClick = onHelp) { Text("?", color = Palette.label, fontSize = 18.sp) }
    }
}

@Composable
private fun LostBanner() {
    Text(
        "CONNECTION LOST – THE BELT KEEPS ITS LAST SETTING. USE THE CONSOLE STOP OR THE SAFETY KEY.",
        color = Color.Black,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.stop, RoundedCornerShape(6.dp))
            .padding(10.dp),
    )
}

@Composable
private fun Display(state: PanelState) {
    val r = readouts(state.status)
    val lit = state.status != null && state.link == Link.CONNECTED
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Palette.frame, RoundedCornerShape(14.dp))
            .background(Color.Black, RoundedCornerShape(14.dp))
            .padding(vertical = 14.dp, horizontal = 6.dp),
    ) {
        // Four digits plus a point must fit a third of the width.
        val digitHeight = minOf(maxWidth / 3 / 3.2f, 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row {
                Readout("INCLINE", r.incline, "", lit, digitHeight)
                Readout("SPEED  km/h", r.speed, r.belt, lit, digitHeight)
                Readout("PULSE", r.pulse, "", lit, digitHeight)
            }
            Row {
                Readout("TIME", r.elapsed, "", lit, digitHeight)
                Readout("DISTANCE  km", r.distance, "", lit, digitHeight)
                Readout("CALORIES", r.calories, "", lit, digitHeight)
            }
            StateBadge(state, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun RowScope.Readout(label: String, value: String, sub: String, lit: Boolean, digitHeight: androidx.compose.ui.unit.Dp) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Palette.label, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        SevenSegment(value, digitHeight, on = if (lit) Palette.led else Palette.ledDim, off = Palette.ledOff)
        Text(sub, color = Palette.label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun StateBadge(state: PanelState, modifier: Modifier) {
    val status = state.status
    if (status == null) {
        Text("—", color = Palette.label, modifier = modifier)
        return
    }
    val color = when (status.state) {
        State.STARTING -> Palette.incline
        State.RUNNING -> Palette.start
        State.STOPPING, State.STOPPED -> Palette.stop
        else -> Palette.label
    }
    Text("● ${status.state.toString().uppercase()}", color = color, fontWeight = FontWeight.Bold, modifier = modifier)
}

@Composable
private fun Buttons(onPress: (Action, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("START", Palette.start, Modifier.weight(1f)) { onPress(Action.START, 0) }
            BigButton("STOP", Palette.stop, Modifier.weight(1f)) { onPress(Action.STOP, 0) }
        }
        StepRow(
            "SPEED  km/h", Palette.speed, "−", "+", SPEED_PRESETS.map { (it / 10).toString() },
            onDown = { onPress(Action.SPEED_DOWN, 0) },
            onUp = { onPress(Action.SPEED_UP, 0) },
            onPreset = { i -> onPress(Action.SPEED_PRESET, SPEED_PRESETS[i]) },
        )
        StepRow(
            "INCLINE", Palette.incline, "▽", "△", INCLINE_PRESETS.map { it.toString() },
            onDown = { onPress(Action.INCLINE_DOWN, 0) },
            onUp = { onPress(Action.INCLINE_UP, 0) },
            onPreset = { i -> onPress(Action.INCLINE_PRESET, INCLINE_PRESETS[i]) },
        )
    }
}

/** A labelled row of console buttons: step down, step up and the four quick keys. */
@Composable
private fun StepRow(
    label: String,
    color: Color,
    down: String,
    up: String,
    presets: List<String>,
    onDown: () -> Unit,
    onUp: () -> Unit,
    onPreset: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Palette.label, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton(down, color, Modifier.weight(1.3f), onDown)
            KeyButton(up, color, Modifier.weight(1.3f), onUp)
            presets.forEachIndexed { i, text -> KeyButton(text, color, Modifier.weight(1f)) { onPreset(i) } }
        }
    }
}

@Composable
private fun BigButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.Black),
    ) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun KeyButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Help() {
    val rows = listOf(
        "START" to "start the belt (3 s countdown, then 1.0 km/h)",
        "STOP" to "stop the belt",
        "− / +" to "speed −0.1 / +0.1 km/h",
        "3 6 9 12" to "speed 3, 6, 9, 12 km/h",
        "▽ / △" to "incline −1 / +1",
        "3 6 9 12" to "incline 3, 6, 9, 12",
        "back" to "leave (stops a moving belt first; press twice to leave at once)",
        "keyboard" to "enter, space, p/m, 3 6 9 0, arrows, a s d f, q work like in the terminal panel",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Palette.frame, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((key, what) in rows) {
            Row {
                Text(key, color = Palette.speed, fontSize = 14.sp, modifier = Modifier.weight(0.3f))
                Text(what, color = Palette.label, fontSize = 14.sp, modifier = Modifier.weight(0.7f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Speed and incline can only change while the belt runs. Always use the safety key: a phone is not an emergency stop.",
            color = Palette.label,
            fontSize = 13.sp,
        )
    }
}

/** First screen: pick the treadmill or the simulator. */
@Composable
fun ConnectScreen(
    name: String,
    onNameChange: (String) -> Unit,
    message: String?,
    onBluetooth: () -> Unit,
    onSimulator: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SevenSegment("8.8", 72.dp, on = Palette.led, off = Palette.ledOff)
            Text("HMS treadmill console", color = Palette.brand, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                label = { Text("Advertised name") },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Palette.brand,
                    unfocusedTextColor = Palette.brand,
                    focusedBorderColor = Palette.speed,
                    unfocusedBorderColor = Palette.frame,
                    focusedLabelColor = Palette.speed,
                    unfocusedLabelColor = Palette.label,
                ),
            )
            BigButton("Connect", Palette.speed, Modifier.fillMaxWidth(), onBluetooth)
            OutlinedButton(onClick = onSimulator, modifier = Modifier.fillMaxWidth().height(54.dp), border = BorderStroke(1.dp, Palette.frame)) {
                Text("Simulator (no hardware)", color = Palette.text, fontSize = 16.sp)
            }
            if (message != null) Text(message, color = Palette.incline, fontSize = 14.sp, textAlign = TextAlign.Center)
            Text(
                "Attach the safety key. The treadmill accepts one connection at a time.",
                color = Palette.label,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
