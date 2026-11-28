package dev.coreamlan.corelink

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private val Panel = Color(0xF0191A1E)
private val Accent = Color(0xffffa84d)

/** Use the picture as a relative touchpad: drag to move and tap to click. */
@Composable
fun RemoteScreen(vm: CompanionViewModel, ui: CompanionUiState, onDashboard: () -> Unit) {
    var controls by remember { mutableStateOf(true) }
    var fillScreen by remember { mutableStateOf(true) }
    var pointerSpeed by remember { mutableFloatStateOf(2.4f) }
    var keyboard by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    val currentHolding by rememberUpdatedState(holding)
    var text by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    fun send(kind: String, field: String, value: Any) {
        vm.input(JSONObject().put("kind", kind).put(field, value))
    }
    fun key(value: String, control: Boolean = false, alternate: Boolean = false) {
        vm.input(JSONObject().put("kind", "key").put("key", value).put("ctrl", control).put("alt", alternate).put("shift", false))
    }
    fun release() {
        if (holding) { vm.input(JSONObject().put("kind", "button").put("button", "left").put("down", false)); holding = false }
    }
    DisposableEffect(vm) {
        onDispose { if (currentHolding) vm.input(JSONObject().put("kind", "button").put("button", "left").put("down", false)) }
    }
    LaunchedEffect(keyboard) {
        if (keyboard) { inputFocus.requestFocus(); keyboardController?.show() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        DesktopVideo(open = { vm.openVideo() }, modifier = Modifier.fillMaxSize(), fill = fillScreen)
        Box(Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { send("click", "button", "left") }, onLongPress = { send("click", "button", "right") }) }
            .pointerInput(pointerSpeed) {
                var xRemainder = 0f
                var yRemainder = 0f
                detectDragGestures(onDragStart = { xRemainder = 0f; yRemainder = 0f }) { change, drag ->
                    change.consume()
                    xRemainder += drag.x * pointerSpeed; yRemainder += drag.y * pointerSpeed
                    val dx = xRemainder.toInt().coerceIn(-5000, 5000); val dy = yRemainder.toInt().coerceIn(-5000, 5000)
                    xRemainder -= dx; yRemainder -= dy
                    if (dx != 0 || dy != 0) vm.input(JSONObject().put("kind", "move").put("dx", dx).put("dy", dy))
                }
            })
        Column(Modifier.align(Alignment.TopEnd).displayCutoutPadding().padding(8.dp), horizontalAlignment = Alignment.End) {
            FilledTonalButton(onClick = { controls = !controls; if (!controls) { keyboard = false; keyboardController?.hide() } }, contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.height(44.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = Panel, contentColor = Color.White)) {
                Text(if (controls) "Hide controls" else "Controls", style = MaterialTheme.typography.labelLarge)
            }
            if (ui.error && ui.message.isNotBlank()) Text(ui.message, color = Color(0xffffaaa5), modifier = Modifier.background(Panel, RoundedCornerShape(8.dp)).padding(8.dp))
        }
        if (controls) Column(Modifier.align(Alignment.BottomCenter).displayCutoutPadding().imePadding().padding(8.dp).fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (keyboard) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    RemoteAction("Esc") { key("escape") }
                    RemoteAction("Tab") { key("tab", ctrl, alt); ctrl = false; alt = false }
                    RemoteAction("⌫") { key("backspace") }
                    RemoteAction("Enter") { key("enter") }
                    RemoteAction(if (ctrl) "Ctrl •" else "Ctrl", ctrl) { ctrl = !ctrl }
                    RemoteAction(if (alt) "Alt •" else "Alt", alt) { alt = !alt }
                    RemoteAction("←") { key("left") }; RemoteAction("↑") { key("up") }; RemoteAction("↓") { key("down") }; RemoteAction("→") { key("right") }
                    RemoteAction("Del") { key("delete") }
                }
                OutlinedTextField(value = text, onValueChange = { value ->
                    if (value.isNotEmpty()) {
                        if ((ctrl || alt) && value.length == 1 && value[0].isLetterOrDigit() && value[0].code < 128) key(value, ctrl, alt)
                        else send("text", "text", value)
                        ctrl = false; alt = false; text = ""
                    }
                }, label = { Text("Type on PC") }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(inputFocus))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                IconRemoteAction("mouse_left", "Left click") { send("click", "button", "left") }
                IconRemoteAction("mouse_right", "Right click") { send("click", "button", "right") }
                IconRemoteAction("drag", if (holding) "Release drag" else "Hold to drag", holding) { holding = !holding; vm.input(JSONObject().put("kind", "button").put("button", "left").put("down", holding)) }
                IconRemoteAction("up", "Scroll up") { send("scroll", "dy", 120) }
                IconRemoteAction("down", "Scroll down") { send("scroll", "dy", -120) }
                RemoteAction("Speed ${pointerSpeed}×") { pointerSpeed = when (pointerSpeed) { 2.4f -> 4.0f; 4.0f -> 1.2f; else -> 2.4f } }
                IconRemoteAction("keyboard", if (keyboard) "Hide keyboard" else "Keyboard", keyboard) { keyboard = !keyboard; if (!keyboard) keyboardController?.hide() }
                IconRemoteAction("fit", if (fillScreen) "Keep proportions" else "Stretch to fill") { fillScreen = !fillScreen }
                IconRemoteAction("dashboard", "Dashboard") { release(); onDashboard() }
                IconRemoteAction("power", "Disconnect") { release(); vm.disconnect() }
            }
        }
    }
}

@Composable
private fun IconRemoteAction(icon: String, label: String, active: Boolean = false, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = label }, contentPadding = PaddingValues(12.dp), colors = ButtonDefaults.textButtonColors(contentColor = if (active) Accent else Color.White)) {
        RemoteIcon(icon, if (active) Accent else Color.White)
    }
}

@Composable
private fun RemoteAction(label: String, active: Boolean = false, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.heightIn(min = 44.dp), contentPadding = PaddingValues(horizontal = 10.dp), colors = ButtonDefaults.textButtonColors(contentColor = if (active) Accent else Color.White)) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}
