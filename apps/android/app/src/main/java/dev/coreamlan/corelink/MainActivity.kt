package dev.coreamlan.corelink

import android.os.Bundle
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
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
    private val companionViewModel: CompanionViewModel by viewModels()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        CoreLinkClient.initialize(applicationContext)
        setContent { CoreLinkApp(companionViewModel) }
    }

    override fun onStart() {
        super.onStart()
        companionViewModel.setForeground(true)
    }

    override fun onStop() {
        companionViewModel.setForeground(false)
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (companionViewModel.ui.value.connected && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val key = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) "volume_up" else "volume_down"
            companionViewModel.input(JSONObject().put("kind", "key").put("key", key).put("ctrl", false).put("alt", false).put("shift", false))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (companionViewModel.ui.value.connected && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }
}

@Composable
fun CoreLinkApp(vm: CompanionViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val orange = Color(0xffff8a19)
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf("") }
    var viewingPc by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current as ComponentActivity
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.initialize(context) }
    LaunchedEffect(ui.connected) { if (!ui.connected) viewingPc = false }
    DisposableEffect(ui.connected, viewingPc) {
        activity.requestedOrientation = if (ui.connected && viewingPc) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (ui.connected && viewingPc) {
            bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler(enabled = ui.connected && viewingPc) { viewingPc = false }
    MaterialTheme(colorScheme = darkColorScheme(primary = orange, background = Color(0xff09090b), surface = Color(0xff18181b))) {
        Surface { if (ui.connected) {
            if (viewingPc) RemoteScreen(vm, ui, onDashboard = { viewingPc = false }) else RemoteDashboard(vm, ui, onViewPc = { viewingPc = true })
        } else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("CoreLink", style = MaterialTheme.typography.headlineMedium)
            Text("Windows companion", color = Color.LightGray)
            if (!ui.connected) {
                Text("Pair with your PC", style = MaterialTheme.typography.titleMedium)
                Text("Start CoreLink on Windows and scan its QR code to connect.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                if (ui.hasSavedPc) Button({ vm.reconnect(context) }, modifier = Modifier.fillMaxWidth(), enabled = !ui.busy && !scanning) { Text(if (ui.busy) "Connecting…" else "Connect saved PC") }
                Button({
                    scanError = ""
                    scanning = true
                    GmsBarcodeScanning.getClient(context as ComponentActivity).startScan()
                        .addOnSuccessListener { barcode ->
                            val value = barcode.rawValue
                            if (value.isNullOrBlank()) scanError = "The QR code is empty. Please scan the code shown on your PC."
                            else vm.pair(context, value)
                        }
                        .addOnFailureListener { scanError = "Could not scan the QR code. Please try again." }
                        .addOnCompleteListener { scanning = false }
                }, modifier = Modifier.fillMaxWidth(), enabled = !ui.busy && !scanning) { Text(if (ui.busy) "Connecting…" else if (scanning) "Opening scanner…" else "Scan CoreLink QR") }
                if (scanError.isNotEmpty()) Text(scanError, color = Color(0xfffca5a5))
            }
            if (ui.message.isNotBlank()) Text(ui.message, color = if (ui.error) Color(0xfffca5a5) else Color.LightGray)
        } }
    }
}
