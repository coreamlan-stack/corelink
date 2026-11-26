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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() { override fun onCreate(state: Bundle?) { super.onCreate(state); setContent { CoreLinkApp() } } }
@Composable fun CoreLinkApp(vm: CompanionViewModel = viewModel()) {
  val scope=rememberCoroutineScope(); val orange=Color(0xffff8a19); var host by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }
  MaterialTheme(colorScheme=darkColorScheme(primary=orange,background=Color(0xff09090b),surface=Color(0xff18181b))) { Surface { Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
    Text("CoreLink", style=MaterialTheme.typography.headlineMedium); Text("Windows companion", color=Color.LightGray)
    if (!vm.connected) { Text("Pair with this PC", style=MaterialTheme.typography.titleMedium); OutlinedTextField(host,{host=it},label={Text("PC address (local Wi-Fi)")},singleLine=true,modifier=Modifier.fillMaxWidth()); OutlinedTextField(code,{code=it.filter(Char::isDigit).take(6)},label={Text("Code shown in CoreLink")},singleLine=true,modifier=Modifier.fillMaxWidth()); Button(onClick={scope.launch(Dispatchers.IO){vm.pair(host,code)}},modifier=Modifier.fillMaxWidth(),enabled=vm.busy.not()){Text(if(vm.busy)"Pairing…" else "Request pairing")}; Text("The PC must be running CoreLink Companion. The desktop owner must approve this phone and re-enter this code.",style=MaterialTheme.typography.bodySmall,color=Color.LightGray) }
    else { Text("Connected securely",color=Color(0xff4ade80)); Text("Touchpad",style=MaterialTheme.typography.titleLarge); Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xff27272a),RoundedCornerShape(18.dp)).pointerInput(Unit){detectDragGestures { _, drag -> vm.input(JSONObject().put("kind","move").put("dx",drag.x.toInt()).put("dy",drag.y.toInt())) } }); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){Button({vm.input(JSONObject().put("kind","click").put("button","left"))},Modifier.weight(1f)){Text("Left click")}; OutlinedButton({vm.input(JSONObject().put("kind","click").put("button","right"))},Modifier.weight(1f)){Text("Right click")}}; OutlinedTextField("",{if(it.isNotEmpty())vm.input(JSONObject().put("kind","key").put("key",it).put("ctrl",false).put("alt",false).put("shift",false))},label={Text("Type to PC")},modifier=Modifier.fillMaxWidth()); Button({vm.disconnect()},Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Color(0xffb91c1c))){Text("Disconnect now")} }
    if(vm.message.isNotBlank()) Text(vm.message,color=if(vm.error)Color(0xfffca5a5) else Color.LightGray)
  } } }
}
