package dev.coreamlan.corelink

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** The desktop itself acts as a relative touchpad; tapping clicks at the PC pointer. */
@Composable
fun RemoteScreen(vm: CompanionViewModel, ui: CompanionUiState) {
    var controls by remember { mutableStateOf(true) }
    var keyboard by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    fun key(value: String, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) {
        vm.input(JSONObject().put("kind", "key").put("key", value).put("ctrl", ctrl).put("alt", alt).put("shift", shift))
    }
    DisposableEffect(vm) {
        onDispose { if (holding) vm.input(JSONObject().put("kind", "button").put("button", "left").put("down", false)) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        DesktopVideo(open = { vm.openVideo() }, modifier = Modifier.fillMaxSize())
        DesktopAudio(open = { vm.openAudio() })
        Box(Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { vm.input(JSONObject().put("kind", "click").put("button", "left")) }, onLongPress = { vm.input(JSONObject().put("kind", "click").put("button", "right")) }) }
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); vm.input(JSONObject().put("kind", "move").put("dx", drag.x.toInt().coerceIn(-5000, 5000)).put("dy", drag.y.toInt().coerceIn(-5000, 5000))) } })
        Column(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalAlignment = Alignment.End) {
            FilledTonalButton(onClick = { controls = !controls }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(40.dp)) { Text(if (controls) "Hide controls" else "Controls") }
            if (controls) {
                Row(Modifier.background(Color(0xD91B1B1F), RoundedCornerShape(10.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.input(JSONObject().put("kind", "click").put("button", "left")) }) { Text("Left") }
                    TextButton(onClick = { vm.input(JSONObject().put("kind", "click").put("button", "right")) }) { Text("Right") }
                    TextButton(onClick = { holding = !holding; vm.input(JSONObject().put("kind", "button").put("button", "left").put("down", holding)) }) { Text(if (holding) "Release" else "Hold") }
                    TextButton(onClick = { vm.input(JSONObject().put("kind", "scroll").put("dy", 120)) }) { Text("↑") }
                    TextButton(onClick = { vm.input(JSONObject().put("kind", "scroll").put("dy", -120)) }) { Text("↓") }
                    TextButton(onClick = { keyboard = !keyboard; if (!keyboard) keyboardController?.hide() }) { Text("Keyboard") }
                    TextButton(onClick = { if (holding) { vm.input(JSONObject().put("kind", "button").put("button", "left").put("down", false)); holding = false }; vm.disconnect() }) { Text("Disconnect", color = Color(0xffffaaa5)) }
                }
                if (keyboard) {
                    Row(Modifier.background(Color(0xE61B1B1F), RoundedCornerShape(10.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { key("escape") }) { Text("Esc") }
                        TextButton(onClick = { key("tab") }) { Text("Tab") }
                        TextButton(onClick = { key("backspace") }) { Text("⌫") }
                        TextButton(onClick = { key("enter") }) { Text("Enter") }
                        TextButton(onClick = { key("a", ctrl = true) }) { Text("Ctrl+A") }
                        TextButton(onClick = { key("c", ctrl = true) }) { Text("Ctrl+C") }
                        TextButton(onClick = { key("v", ctrl = true) }) { Text("Ctrl+V") }
                    }
                    Row(Modifier.background(Color(0xE61B1B1F), RoundedCornerShape(10.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { key("left") }) { Text("←") }
                        TextButton(onClick = { key("up") }) { Text("↑") }
                        TextButton(onClick = { key("down") }) { Text("↓") }
                        TextButton(onClick = { key("right") }) { Text("→") }
                        TextButton(onClick = { key("delete") }) { Text("Delete") }
                    }
                    OutlinedTextField(value = text, onValueChange = { value ->
                        if (value.isNotEmpty()) { vm.input(JSONObject().put("kind", "text").put("text", value)); text = "" }
                    }, label = { Text("Type on PC") }, singleLine = true, modifier = Modifier.width(280.dp).background(Color(0xE61B1B1F)))
                }
                if (ui.error) Text(ui.message, color = Color(0xffffaaa5), modifier = Modifier.background(Color(0xE61B1B1F)).padding(6.dp))
            }
        }
    }
}
