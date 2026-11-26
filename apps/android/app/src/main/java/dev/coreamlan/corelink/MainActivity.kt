package dev.coreamlan.corelink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        CoreLinkClient.initialize(applicationContext)
        setContent { CoreLinkApp() }
    }
}

@Composable
fun CoreLinkApp(vm: CompanionViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val orange = Color(0xffff8a19)
    var host by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var typed by rememberSaveable { mutableStateOf("") }
    MaterialTheme(colorScheme = darkColorScheme(primary = orange, background = Color(0xff09090b), surface = Color(0xff18181b))) {
        Surface { Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("CoreLink", style = MaterialTheme.typography.headlineMedium)
            Text("Windows companion", color = Color.LightGray)
            if (!ui.connected) {
                Text("Pair with this PC", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(host, { host = it }, label = { Text("PC address (local Wi-Fi)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code shown in CoreLink") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.pair(host, code) }, modifier = Modifier.fillMaxWidth(), enabled = !ui.busy) { Text(if (ui.busy) "Pairing..." else "Request pairing") }
                Text("The PC owner must approve this phone within 90 seconds.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            } else {
                Text("Connected", color = Color(0xff4ade80))
                Text("Touchpad", style = MaterialTheme.typography.titleLarge)
                Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xff27272a), RoundedCornerShape(18.dp)).pointerInput(Unit) {
                    detectDragGestures { _, drag -> vm.input(JSONObject().put("kind", "move").put("dx", drag.x.toInt()).put("dy", drag.y.toInt())) }
                })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button({ vm.input(JSONObject().put("kind", "click").put("button", "left")) }, Modifier.weight(1f)) { Text("Left click") }
                    OutlinedButton({ vm.input(JSONObject().put("kind", "click").put("button", "right")) }, Modifier.weight(1f)) { Text("Right click") }
                }
                OutlinedTextField(typed, { text ->
                    if (text.isNotEmpty()) { vm.input(JSONObject().put("kind", "key").put("key", text.takeLast(1)).put("ctrl", false).put("alt", false).put("shift", false)); typed = "" }
                }, label = { Text("Type to PC") }, modifier = Modifier.fillMaxWidth())
                Button({ vm.disconnect() }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xffb91c1c))) { Text("Disconnect now") }
            }
            if (ui.message.isNotBlank()) Text(ui.message, color = if (ui.error) Color(0xfffca5a5) else Color.LightGray)
        } }
    }
}
