package dev.coreamlan.corelink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val RemoteBackground = Color(0xff0c0d10)
private val RemoteSurface = Color(0xff181a1e)
private val RemoteBorder = Color(0xff303238)
private val RemoteMuted = Color(0xffa1a2aa)
private val RemoteOrange = Color(0xffff942c)

@Composable
fun RemoteDashboard(vm: CompanionViewModel, ui: CompanionUiState, onViewPc: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var keyboardVisible by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(2.4f) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun key(name: String) = vm.input(JSONObject().put("kind", "key").put("key", name).put("ctrl", false).put("alt", false).put("shift", false))
    fun click(button: String) = vm.input(JSONObject().put("kind", "click").put("button", button))

    LaunchedEffect(keyboardVisible) {
        if (keyboardVisible) { focus.requestFocus(); keyboard?.show() }
    }

    Column(
        Modifier.fillMaxSize().background(RemoteBackground).statusBarsPadding().navigationBarsPadding()
            .imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("CORELINK", color = RemoteOrange, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text("PC remote", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.background(Color(0xff173126), CircleShape).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(Color(0xff71db9c), CircleShape))
                Spacer(Modifier.width(7.dp))
                Text("Connected", color = Color(0xffa2e8bc), style = MaterialTheme.typography.labelMedium)
            }
        }

        Button(onClick = onViewPc, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = RemoteOrange, contentColor = Color(0xff201004))) {
            RemoteIcon("screen", Color(0xff201004))
            Spacer(Modifier.width(10.dp))
            Text("View PC screen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("›", fontSize = 27.sp, lineHeight = 27.sp)
        }

        Column(Modifier.fillMaxWidth().background(RemoteSurface, RoundedCornerShape(22.dp)).border(1.dp, RemoteBorder, RoundedCornerShape(22.dp)).padding(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("TOUCHPAD", color = Color(0xffe5e5e8), fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                    Text("Tap to click · Hold for right click", color = RemoteMuted, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { speed = when (speed) { 2.4f -> 4f; 4f -> 1.2f; else -> 2.4f } }, contentPadding = PaddingValues(horizontal = 9.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("${speed}×", color = RemoteOrange, style = MaterialTheme.typography.labelLarge)
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 170.dp).height(190.dp)
                .background(Color(0xff26282d), RoundedCornerShape(15.dp))
                .border(1.dp, Color(0xff393b42), RoundedCornerShape(15.dp))
                .semantics { contentDescription = "Touchpad. Drag to move pointer, tap for left click, hold for right click." }
                .pointerInput(Unit) { detectTapGestures(onTap = { click("left") }, onLongPress = { click("right") }) }
                .pointerInput(speed) {
                    var x = 0f; var y = 0f
                    detectDragGestures(onDragStart = { x = 0f; y = 0f }) { change, delta ->
                        change.consume(); x += delta.x * speed; y += delta.y * speed
                        val dx = x.toInt().coerceIn(-5000, 5000); val dy = y.toInt().coerceIn(-5000, 5000)
                        x -= dx; y -= dy
                        if (dx != 0 || dy != 0) vm.input(JSONObject().put("kind", "move").put("dx", dx).put("dy", dy))
                    }
                }, contentAlignment = Alignment.Center) {
                Text("·  ·  ·", color = Color(0xff666970), fontSize = 21.sp, letterSpacing = 4.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteKey("mouse_left", "Left click", Modifier.weight(1f)) { click("left") }
                RemoteKey("mouse_right", "Right click", Modifier.weight(1f)) { click("right") }
            }
        }

        Column(Modifier.fillMaxWidth().background(RemoteSurface, RoundedCornerShape(20.dp)).border(1.dp, RemoteBorder, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PC VOLUME", color = Color(0xffe5e5e8), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    Text("Phone buttons also work", color = RemoteMuted, fontSize = 10.sp, maxLines = 1)
                }
                RemoteCircle("minus", "Volume down") { key("volume_down") }
                Spacer(Modifier.width(4.dp))
                RemoteCircle("mute", "Mute PC") { key("volume_mute") }
                Spacer(Modifier.width(4.dp))
                RemoteCircle("plus", "Volume up") { key("volume_up") }
            }
            HorizontalDivider(color = RemoteBorder)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MEDIA", color = Color(0xffe5e5e8), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    Text("Control PC playback", color = RemoteMuted, fontSize = 10.sp, maxLines = 1)
                }
                RemoteCircle("previous", "Previous track") { key("media_previous") }
                Spacer(Modifier.width(4.dp))
                RemoteCircle("play_pause", "Play or pause", highlighted = true) { key("media_play_pause") }
                Spacer(Modifier.width(4.dp))
                RemoteCircle("next", "Next track") { key("media_next") }
            }
        }

        if (keyboardVisible) {
            OutlinedTextField(value = typed, onValueChange = { value ->
                if (value.isNotEmpty()) { vm.input(JSONObject().put("kind", "text").put("text", value)); typed = "" }
            }, label = { Text("Type on PC") }, modifier = Modifier.fillMaxWidth().focusRequester(focus), singleLine = true, shape = RoundedCornerShape(14.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RemoteKey("keyboard", if (keyboardVisible) "Hide keyboard" else "Keyboard", Modifier.weight(1f)) {
                keyboardVisible = !keyboardVisible
                if (!keyboardVisible) keyboard?.hide()
            }
            RemoteKey("power", "Disconnect", Modifier.weight(1f)) { vm.disconnect() }
        }
        if (ui.error && ui.message.isNotBlank()) Text(ui.message, color = Color(0xffffaaa5), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RemoteKey(icon: String, label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    OutlinedButton(onClick = action, modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = label }, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorder), contentPadding = PaddingValues(horizontal = 8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xffe7e7e9), containerColor = Color(0xff202126))) {
        RemoteIcon(icon, Color(0xffe7e7e9))
    }
}

@Composable
private fun RemoteCircle(icon: String, label: String, highlighted: Boolean = false, action: () -> Unit) {
    FilledTonalButton(onClick = action, modifier = Modifier.size(48.dp).semantics { contentDescription = label }, shape = CircleShape, contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (highlighted) Color(0xff4a301b) else Color(0xff292b30), contentColor = if (highlighted) RemoteOrange else Color(0xffeeeeef))) {
        RemoteIcon(icon, if (highlighted) RemoteOrange else Color(0xffeeeeef))
    }
}

/** Small vector symbols are rendered locally and every icon-only control has a spoken label. */
@Composable
internal fun RemoteIcon(name: String, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val factor = size.minDimension / 24f
        scale(factor, factor, pivot = Offset.Zero) {
            val line = 1.8f
            fun segment(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(tint, Offset(x1, y1), Offset(x2, y2), strokeWidth = line, cap = StrokeCap.Round)
            fun triangle(vararg points: Float) {
                val p = Path().apply {
                    moveTo(points[0], points[1]); lineTo(points[2], points[3]); lineTo(points[4], points[5]); close()
                }
                drawPath(p, tint)
            }
            when (name) {
                "screen" -> {
                    drawRoundRect(tint, Offset(3f, 4f), Size(18f, 12f), CornerRadius(2f), style = Stroke(line))
                    segment(12f, 16f, 12f, 20f); segment(8f, 20f, 16f, 20f)
                }
                "mouse_left", "mouse_right" -> {
                    drawRoundRect(tint, Offset(6f, 2f), Size(12f, 20f), CornerRadius(6f), style = Stroke(line))
                    segment(6f, 10f, 18f, 10f); segment(12f, 2f, 12f, 10f)
                    drawCircle(RemoteOrange, 1.7f, Offset(if (name == "mouse_left") 9f else 15f, 6.5f))
                }
                "minus", "plus" -> {
                    segment(5f, 12f, 19f, 12f)
                    if (name == "plus") segment(12f, 5f, 12f, 19f)
                }
                "mute" -> {
                    val p = Path().apply { moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close() }
                    drawPath(p, tint, style = Stroke(line)); segment(15f, 9f, 20f, 15f); segment(20f, 9f, 15f, 15f)
                }
                "previous" -> { segment(6f, 5f, 6f, 19f); triangle(18f, 5f, 18f, 19f, 8f, 12f) }
                "next" -> { segment(18f, 5f, 18f, 19f); triangle(6f, 5f, 6f, 19f, 16f, 12f) }
                "play_pause" -> { triangle(4f, 5f, 4f, 19f, 13f, 12f); segment(17f, 6f, 17f, 18f); segment(21f, 6f, 21f, 18f) }
                "keyboard" -> {
                    drawRoundRect(tint, Offset(2f, 5f), Size(20f, 14f), CornerRadius(2f), style = Stroke(line))
                    for (x in listOf(6f, 10f, 14f, 18f)) { segment(x, 9f, x, 9.3f); segment(x, 12f, x, 12.3f) }
                    segment(8f, 16f, 16f, 16f)
                }
                "power" -> {
                    val p = Path().apply { moveTo(8f, 5f); cubicTo(2f, 8f, 3f, 20f, 12f, 20f); cubicTo(21f, 20f, 22f, 8f, 16f, 5f) }
                    drawPath(p, tint, style = Stroke(line, cap = StrokeCap.Round)); segment(12f, 3f, 12f, 12f)
                }
                "up", "down" -> {
                    val y1 = if (name == "up") 8f else 16f
                    val y2 = if (name == "up") 14f else 10f
                    segment(5f, y2, 12f, y1); segment(12f, y1, 19f, y2)
                    segment(12f, 5f, 12f, 19f)
                }
                "drag" -> {
                    drawRoundRect(tint, Offset(6f, 2f), Size(12f, 20f), CornerRadius(6f), style = Stroke(line))
                    segment(6f, 10f, 18f, 10f); segment(12f, 2f, 12f, 10f)
                    segment(9f, 15f, 15f, 15f)
                }
                "fit" -> {
                    segment(4f, 9f, 4f, 4f); segment(4f, 4f, 9f, 4f)
                    segment(15f, 4f, 20f, 4f); segment(20f, 4f, 20f, 9f)
                    segment(4f, 15f, 4f, 20f); segment(4f, 20f, 9f, 20f)
                    segment(15f, 20f, 20f, 20f); segment(20f, 15f, 20f, 20f)
                }
                "dashboard" -> {
                    drawRoundRect(tint, Offset(3f, 3f), Size(7f, 7f), CornerRadius(1.5f), style = Stroke(line))
                    drawRoundRect(tint, Offset(14f, 3f), Size(7f, 7f), CornerRadius(1.5f), style = Stroke(line))
                    drawRoundRect(tint, Offset(3f, 14f), Size(7f, 7f), CornerRadius(1.5f), style = Stroke(line))
                    drawRoundRect(tint, Offset(14f, 14f), Size(7f, 7f), CornerRadius(1.5f), style = Stroke(line))
                }
            }
        }
    }
}
