package dev.coreamlan.corelink

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun RemoteDashboard(vm: CompanionViewModel, ui: CompanionUiState, onViewPc: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    fun key(name: String) = vm.input(JSONObject().put("kind", "key").put("key", name).put("ctrl", false).put("alt", false).put("shift", false))
    fun click(button: String) = vm.input(JSONObject().put("kind", "click").put("button", button))
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("CoreLink", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text("PC remote", style = MaterialTheme.typography.bodyMedium, color = Color.LightGray) }
            Text("Connected", color = Color(0xff4ade80), style = MaterialTheme.typography.labelLarge)
        }
        Button(onClick = onViewPc, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("View PC screen") }
        Text("Touchpad", style = MaterialTheme.typography.titleMedium)
        Text("Move your finger to move the pointer. Tap to click; press and hold for right-click.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        Box(Modifier.fillMaxWidth().heightIn(min = 180.dp).height(210.dp).background(Color(0xff292a2f), RoundedCornerShape(16.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { click("left") }, onLongPress = { click("right") }) }
            .pointerInput(Unit) {
                var x = 0f; var y = 0f
                detectDragGestures(onDragStart = { x = 0f; y = 0f }) { change, delta ->
                    change.consume(); x += delta.x * 2.4f; y += delta.y * 2.4f
                    val dx = x.toInt().coerceIn(-5000, 5000); val dy = y.toInt().coerceIn(-5000, 5000)
                    x -= dx; y -= dy
                    if (dx != 0 || dy != 0) vm.input(JSONObject().put("kind", "move").put("dx", dx).put("dy", dy))
                }
            })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { click("left") }, Modifier.weight(1f)) { Text("Left click") }
            OutlinedButton(onClick = { click("right") }, Modifier.weight(1f)) { Text("Right click") }
        }
        Text("PC volume", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { key("volume_down") }, Modifier.weight(1f)) { Text("−") }
            OutlinedButton(onClick = { key("volume_mute") }, Modifier.weight(1f)) { Text("Mute") }
            OutlinedButton(onClick = { key("volume_up") }, Modifier.weight(1f)) { Text("+") }
        }
        Text("Media", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { key("media_previous") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Previous") }
            OutlinedButton(onClick = { key("media_play_pause") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Play / pause") }
            OutlinedButton(onClick = { key("media_next") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Next") }
        }
        OutlinedTextField(value = typed, onValueChange = { value -> if (value.isNotEmpty()) { vm.input(JSONObject().put("kind", "text").put("text", value)); typed = "" } }, label = { Text("Type on PC") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (ui.error && ui.message.isNotBlank()) Text(ui.message, color = Color(0xffffaaa5), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { vm.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect", color = Color(0xffffaaa5)) }
    }
}
