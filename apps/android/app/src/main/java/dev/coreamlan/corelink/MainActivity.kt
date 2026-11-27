package dev.coreamlan.corelink

import android.os.Bundle
import android.content.pm.ActivityInfo
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        volumeControlStream = AudioManager.STREAM_MUSIC
        CoreLinkClient.initialize(applicationContext)
        setContent { CoreLinkApp() }
    }
}

@Composable
fun CoreLinkApp(vm: CompanionViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val orange = Color(0xffff8a19)
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf("") }
    val activity = LocalContext.current as ComponentActivity
    val context = LocalContext.current
    DisposableEffect(ui.connected) {
        activity.requestedOrientation = if (ui.connected) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (ui.connected) {
            bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler(enabled = ui.connected) { vm.disconnect() }
    MaterialTheme(colorScheme = darkColorScheme(primary = orange, background = Color(0xff09090b), surface = Color(0xff18181b))) {
        Surface { if (ui.connected) {
            RemoteScreen(vm, ui)
        } else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("CoreLink", style = MaterialTheme.typography.headlineMedium)
            Text("Windows companion", color = Color.LightGray)
            if (!ui.connected) {
                Text("Pair with your PC", style = MaterialTheme.typography.titleMedium)
                Text("Start CoreLink Companion on Windows, scan its QR code, then approve this phone on the PC.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
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
                }, modifier = Modifier.fillMaxWidth(), enabled = !ui.busy && !scanning) { Text(if (ui.busy) "Waiting for PC approval…" else if (scanning) "Opening scanner…" else "Scan CoreLink QR") }
                if (scanError.isNotEmpty()) Text(scanError, color = Color(0xfffca5a5))
            }
            if (ui.message.isNotBlank()) Text(ui.message, color = if (ui.error) Color(0xfffca5a5) else Color.LightGray)
        } }
    }
}
