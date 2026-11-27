package dev.coreamlan.corelink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
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
    var typed by rememberSaveable { mutableStateOf("") }
    var qrPayload by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    MaterialTheme(colorScheme = darkColorScheme(primary = orange, background = Color(0xff09090b), surface = Color(0xff18181b))) {
        Surface { Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("CoreLink", style = MaterialTheme.typography.headlineMedium)
            Text("Windows companion", color = Color.LightGray)
            if (!ui.connected) {
                Text("Pair with your PC", style = MaterialTheme.typography.titleMedium)
                Text("Start CoreLink Companion on Windows, scan its QR code, then approve this phone on the PC.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                Button({ GmsBarcodeScanning.getClient(context as ComponentActivity).startScan().addOnSuccessListener { qrPayload = it.rawValue.orEmpty() } }, modifier = Modifier.fillMaxWidth()) { Text("Scan CoreLink QR") }
                OutlinedTextField(qrPayload, { qrPayload = it }, label = { Text("QR payload (manual fallback)") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button({ vm.pair(context, qrPayload) }, modifier = Modifier.fillMaxWidth(), enabled = qrPayload.startsWith("corelink://pair")) { Text("Request pairing") }
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
