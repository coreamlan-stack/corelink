package dev.coreamlan.corelink

import androidx.lifecycle.ViewModel
import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import javax.net.ssl.SSLSocket

data class CompanionUiState(val connected: Boolean = false, val busy: Boolean = false, val message: String = "", val error: Boolean = false, val hasSavedPc: Boolean = false)

class CompanionViewModel : ViewModel() {
    private val _ui = MutableStateFlow(CompanionUiState())
    val ui = _ui.asStateFlow()
    private var client: CoreLinkClient? = null
    private var initialized = false
    private var foreground = false
    private var autoReconnect = true
    private var appContext: Context? = null
    private var inputJob: Job? = null
    private val inputQueue = OrderedMoveQueue<JSONObject>(
        move = { if (it.optString("kind") == "move") it.optInt("dx") to it.optInt("dy") else null },
        merged = { previous, dx, dy -> previous.put("dx", dx).put("dy", dy) },
    )
    private val inputSignal = Channel<Unit>(capacity = Channel.CONFLATED)
    init {
        inputJob = viewModelScope.launch {
            for (signal in inputSignal) {
                while (true) {
                    val value = inputQueue.poll() ?: break
                    val active = client ?: continue
                    withContext(Dispatchers.IO) { active.input(value) }.onFailure { problem ->
                        if (client === active) {
                            client = null
                            inputQueue.clear()
                            _ui.value = CompanionUiState(message = problem.message ?: "PC connection lost. Try connecting again.", error = true, hasSavedPc = true)
                            viewModelScope.launch(Dispatchers.IO) { active.disconnect() }
                        }
                    }
                }
            }
        }
    }
    fun pair(context: Context, qrPayload: String) {
        if (_ui.value.busy) return
        autoReconnect = true
        _ui.value = _ui.value.copy(busy = true, message = "Connecting securely…", error = false)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CoreLinkClient.fromQr(context, qrPayload) }.fold(
                    onSuccess = { candidate -> candidate.pair(android.os.Build.MODEL).fold(
                        onSuccess = { candidate.connect().map { candidate } },
                        onFailure = { Result.failure(it) }
                    ) },
                    onFailure = { Result.failure(it) }
                )
            }
            result.onSuccess { candidate -> client = candidate; _ui.value = CompanionUiState(connected = true, message = "Connected", hasSavedPc = true) }
                .onFailure { problem -> _ui.value = _ui.value.copy(busy = false, message = problem.message ?: "Pairing failed", error = true) }
        }
    }
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appContext = app
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { CoreLinkClient.fromSaved(app) != null }.getOrDefault(false) }
            _ui.value = _ui.value.copy(hasSavedPc = saved)
            if (saved && foreground && autoReconnect) reconnect(app)
            while (true) {
                if (foreground && autoReconnect && _ui.value.hasSavedPc && !_ui.value.busy) {
                    val active = client
                    if (active == null) reconnect(app)
                    else withContext(Dispatchers.IO) { active.heartbeat() }.onFailure {
                        if (client === active) {
                            client = null
                            inputQueue.clear()
                            _ui.value = CompanionUiState(message = "Connection lost. Trying to reconnect…", error = true, hasSavedPc = true)
                        }
                    }
                }
                delay(5_000)
            }
        }
    }
    fun setForeground(value: Boolean) {
        foreground = value
        if (value && autoReconnect && _ui.value.hasSavedPc) appContext?.let { reconnect(it) }
    }
    fun reconnect(context: Context) {
        if (_ui.value.busy || _ui.value.connected) return
        autoReconnect = true
        _ui.value = _ui.value.copy(busy = true, message = "Connecting to your saved PC…", error = false)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { CoreLinkClient.reconnectSaved(context) }
            result.onSuccess { candidate ->
                if (foreground && autoReconnect) {
                    client = candidate
                    _ui.value = CompanionUiState(connected = true, message = "Connected", hasSavedPc = true)
                } else {
                    viewModelScope.launch(Dispatchers.IO) { candidate.disconnect() }
                    _ui.value = _ui.value.copy(busy = false, message = "")
                }
            }
                .onFailure { _ui.value = _ui.value.copy(busy = false, message = if (autoReconnect && foreground) "PC not found yet. Trying again automatically…" else "Disconnected", error = false) }
        }
    }
    fun input(value: JSONObject) {
        if (!_ui.value.connected || client == null) return
        val accepted = inputQueue.offer(value)
        if (accepted) inputSignal.trySend(Unit)
        else if (value.optString("kind") != "move") _ui.value = _ui.value.copy(message = "Control queue is full; try again.", error = true)
    }
    fun openVideo(): Result<SSLSocket> = client?.openVideo() ?: Result.failure(IllegalStateException("Pair with this PC first"))
    fun openAudio(): Result<SSLSocket> = client?.openAudio() ?: Result.failure(IllegalStateException("Pair with this PC first"))
    fun disconnect() {
        autoReconnect = false
        val old = client
        client = null
        inputQueue.clear()
        if (old != null) viewModelScope.launch(Dispatchers.IO) { old.disconnect() }
        _ui.value = CompanionUiState(message = "Disconnected", hasSavedPc = true)
    }
    override fun onCleared() { disconnect(); super.onCleared() }
}
