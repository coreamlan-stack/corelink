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
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import javax.net.ssl.SSLSocket

data class CompanionUiState(val connected: Boolean = false, val busy: Boolean = false, val message: String = "", val error: Boolean = false)

class CompanionViewModel : ViewModel() {
    private val _ui = MutableStateFlow(CompanionUiState())
    val ui = _ui.asStateFlow()
    private var client: CoreLinkClient? = null
    private var inputJob: Job? = null
    private val inputQueue = Channel<JSONObject>(capacity = 128)
    init {
        inputJob = viewModelScope.launch {
            for (value in inputQueue) {
                val active = client ?: continue
                withContext(Dispatchers.IO) { active.input(value) }.onFailure { problem ->
                    if (client === active) _ui.value = _ui.value.copy(message = problem.message ?: "Input rejected", error = true)
                }
            }
        }
    }
    fun pair(context: Context, qrPayload: String) {
        if (_ui.value.busy) return
        _ui.value = CompanionUiState(busy = true, message = "Requesting desktop approval…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CoreLinkClient.fromQr(context, qrPayload) }.fold(
                    onSuccess = { candidate -> candidate.pair(android.os.Build.MODEL).map { candidate } },
                    onFailure = { Result.failure(it) }
                )
            }
            result.onSuccess { candidate -> client = candidate; _ui.value = CompanionUiState(connected = true, message = "Connected") }
                .onFailure { problem -> _ui.value = CompanionUiState(message = problem.message ?: "Pairing failed", error = true) }
        }
    }
    fun input(value: JSONObject) {
        if (!_ui.value.connected || client == null) return
        if (inputQueue.trySend(value).isFailure) _ui.value = _ui.value.copy(message = "Control queue is full; try again.", error = true)
    }
    fun openVideo(): Result<SSLSocket> = client?.openVideo() ?: Result.failure(IllegalStateException("Pair with this PC first"))
    fun disconnect() {
        val old = client ?: return
        client = null
        while (inputQueue.tryReceive().isSuccess) { /* discard controls for the old session */ }
        viewModelScope.launch(Dispatchers.IO) { old.disconnect() }
        _ui.value = CompanionUiState(message = "Disconnected")
    }
    override fun onCleared() { disconnect(); super.onCleared() }
}
