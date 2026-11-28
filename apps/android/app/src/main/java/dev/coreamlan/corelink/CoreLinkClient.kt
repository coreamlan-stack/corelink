package dev.coreamlan.corelink

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.URI
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Parsed only from an out-of-band CoreLink QR code. No plaintext transport exists. */
data class QrEnrollment(val host: String, val port: Int, val pin: ByteArray, val token: String, val expiresAt: Long) {
    companion object {
        fun parse(value: String): QrEnrollment {
            val uri = URI(value)
            require(uri.scheme == "corelink" && uri.host == "pair") { "Not a CoreLink pairing QR code" }
            val query = uri.rawQuery.split("&").associate { val p = it.split("=", limit = 2); p[0] to p.getOrElse(1) { "" } }
            require(query["v"] == "3") { "Unsupported CoreLink protocol" }
            val host = query["host"] ?: error("QR code has no endpoint")
            val port = query["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Invalid QR port")
            require(isPrivateIpv4(host)) { "CoreLink only connects to private IPv4 addresses" }
            val pin = Base64.getUrlDecoder().decode(query["pin"] ?: error("QR code has no certificate pin"))
            require(pin.size == 32) { "Invalid certificate pin" }
            val token = query["token"] ?: error("QR code has no enrollment credential")
            require(Base64.getUrlDecoder().decode(token).size == 32) { "Invalid enrollment credential" }
            val expiry = query["expires"]?.toLongOrNull() ?: error("QR code has no expiration")
            require(expiry > System.currentTimeMillis() / 1000) { "This QR code has expired" }
            return QrEnrollment(host, port, pin, token, expiry)
        }
        internal fun isPrivateIpv4(host: String): Boolean {
            val p = host.split(".").map { it.toIntOrNull() ?: return false }; if (p.size != 4 || p.any { it !in 0..255 }) return false
            return p[0] == 10 || p[0] == 127 || p[0] == 169 && p[1] == 254 || p[0] == 192 && p[1] == 168 || p[0] == 172 && p[1] in 16..31
        }
    }
}

class CoreLinkClient private constructor(private val context: Context, private val enrollment: QrEnrollment) {
    private val store by lazy { secureStore(context) }
    private val deviceId get() = store.getString("device_id", null) ?: randomId().also { store.edit().putString("device_id", it).apply() }
    private var secret: ByteArray? = store.getString("secret_${enrollment.host}_${enrollment.port}", null)?.let { decode(it) }
    private var sequence = 0L
    private var sessionId: String? = null
    private var sessionKey: ByteArray? = null
    private var control: ControlConnection? = null

    @Synchronized fun pair(name: String): Result<Unit> = runCatching {
        // A fresh one-time QR allows pairing again after restart or revocation.
        // Keep the old credential until the replacement succeeds.
        exchange(JSONObject().put("type", "enroll").put("token", enrollment.token).put("device_id", deviceId).put("name", name)) { response ->
            val paired = if (response.current.optString("type") == "approval_required") response.next ?: error("Desktop pairing did not complete") else response.current
            check(paired.optString("type") == "paired") { "Pairing was rejected or timed out" }
            val newSecret = decode(paired.getString("secret"))
            check(newSecret.size == 32) { "Invalid desktop credential" }
            check(store.edit().putString("saved_host", enrollment.host).putInt("saved_port", enrollment.port)
                .putString("saved_pin", encode(enrollment.pin)).putString("saved_secret", encode(newSecret)).commit()) {
                "Could not save the PC securely"
            }
            secret = newSecret
        }
    }

    /** Authenticate a new session before presenting the PC as connected. */
    @Synchronized fun connect(): Result<Unit> = runCatching { ensureSession(); Unit }

    @Synchronized fun input(body: JSONObject): Result<Unit> = runCatching {
        val session = ensureSession(); val connection = ensureControl(session); val seq = nextSequence()
        val payload = body.toString(); val tag = sign(session.key, transcript("input", session.id, seq.toString(), payload))
        val isMove = body.optString("kind") == "move"
        val request = JSONObject().put("type", if (isMove) "move" else "input").put("session_id", session.id).put("sequence", seq).put("payload", payload).put("mac", tag)
        connection.writer.write(request.toString() + "\n"); connection.writer.flush()
        if (!isMove) {
            val response = JSONObject(connection.reader.readLine() ?: error("Desktop closed the control connection"))
            check(response.optString("type") == "ack" && response.optBoolean("ok")) { "Desktop rejected this control" }
        }
    }.onFailure { closeControl() }
    @Synchronized fun heartbeat(): Result<Unit> = runCatching {
        val session = ensureSession(); val connection = ensureControl(session); val seq = nextSequence()
        val request = JSONObject().put("type", "ping").put("session_id", session.id).put("sequence", seq)
            .put("mac", sign(session.key, transcript("ping", session.id, seq.toString())))
        connection.writer.write(request.toString() + "\n"); connection.writer.flush()
        val response = JSONObject(connection.reader.readLine() ?: error("Desktop closed the control connection"))
        check(response.optString("type") == "pong" && response.optBoolean("ok")) { "Desktop session ended" }
    }.onFailure { closeControl(); sessionId = null; sessionKey = null; sequence = 0 }
    @Synchronized fun disconnect() {
        closeControl()
        val session = sessionId?.let { id -> sessionKey?.let { key -> Session(id, key) } } ?: return
        runCatching { val seq=nextSequence(); exchange(JSONObject().put("type","disconnect").put("session_id",session.id).put("sequence",seq).put("mac",sign(session.key,transcript("disconnect",session.id,seq.toString())))) { response -> check(response.current.optBoolean("ok")) } }
        sessionId = null; sessionKey = null; sequence = 0
    }

    private fun ensureControl(session: Session): ControlConnection {
        control?.let { return it }
        val socket = openSocket()
        try {
            socket.soTimeout = 5_000
            val nonce = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
            val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
            val reader = BufferedReader(socket.inputStream.reader(Charsets.UTF_8))
            val request = JSONObject().put("type", "control_open").put("session_id", session.id).put("nonce", nonce)
                .put("mac", sign(session.key, transcript("control-open", session.id, nonce)))
            writer.write(request.toString() + "\n"); writer.flush()
            val response = JSONObject(reader.readLine() ?: error("Desktop closed the control connection"))
            if (response.optString("type") != "control_ready") {
                sessionId = null; sessionKey = null; sequence = 0
                error("Desktop rejected the control session")
            }
            return ControlConnection(socket, reader, writer).also { control = it }
        } catch (error: Exception) { socket.close(); throw error }
    }
    private fun closeControl() { runCatching { control?.socket?.close() }; control = null }

    /** Caller owns the socket and must close it on disconnect. */
    @Synchronized fun openVideo(): Result<SSLSocket> = runCatching {
        val session = ensureSession()
        val nonce = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val socket = openSocket()
        try {
            val request = JSONObject().put("type", "video_open").put("session_id", session.id)
                .put("nonce", nonce).put("mac", sign(session.key, transcript("video-open", session.id, nonce)))
            val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
            writer.write(request.toString() + "\n")
            writer.flush()
            socket
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    @Synchronized fun openAudio(): Result<SSLSocket> = runCatching {
        val session = ensureSession()
        val nonce = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val socket = openSocket()
        try {
            val request = JSONObject().put("type", "audio_open").put("session_id", session.id)
                .put("nonce", nonce).put("mac", sign(session.key, transcript("audio-open", session.id, nonce)))
            OutputStreamWriter(socket.outputStream, Charsets.UTF_8).apply { write(request.toString() + "\n"); flush() }
            socket
        } catch (error: Exception) { socket.close(); throw error }
    }

    private fun ensureSession(): Session {
        val existing = sessionId?.let { id -> sessionKey?.let { key -> Session(id, key) } }
        if (existing != null) return existing
        val deviceKey = checkNotNull(secret) { "Pair with this PC first" }
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val clientNonce = encode(nonce)
        var opened: Session? = null
        exchange(JSONObject().put("type", "session_open").put("device_id", deviceId).put("client_nonce", clientNonce).put("mac", sign(deviceKey, transcript("session-open", deviceId, clientNonce)))) { response ->
            val value = response.current
            check(value.optString("type") == "session_opened") { "Desktop rejected this session" }
            val id = value.getString("session_id")
            val serverNonce = value.getString("server_nonce")
            val key = deriveSessionKey(deviceKey, id, clientNonce, serverNonce)
            val proof = value.getString("mac")
            check(MessageDigest.isEqual(decode(proof), decode(sign(key, transcript("session-opened", deviceId, id, serverNonce))))) { "Desktop session proof failed" }
            opened = Session(id, key)
        }
        return checkNotNull(opened).also { sessionId = it.id; sessionKey = it.key; sequence = 0 }
    }

    private fun exchange(request: JSONObject, consume: (Response) -> Unit) {
        openSocket().use { socket ->
            val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8); writer.write(request.toString() + "\n"); writer.flush()
            val reader = BufferedReader(socket.inputStream.reader(Charsets.UTF_8)); val first=JSONObject(reader.readLine() ?: error("Desktop closed the connection"))
            val second = if (first.optString("type") == "approval_required") JSONObject(reader.readLine() ?: error("Pairing timed out")) else null
            consume(Response(first, second))
        }
    }
    private fun openSocket(): SSLSocket {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedServerTrustManager(enrollment.pin)), SecureRandom()) }
        val socket = ssl.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(enrollment.host, enrollment.port), 3_000)
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.soTimeout = 65_000
            socket.tcpNoDelay = true
            socket.startHandshake() // Pin is checked before credentials are sent.
            return socket
        } catch (error: Exception) { socket.close(); throw error }
    }
    private fun nextSequence(): Long { sequence += 1; return sequence }
    private fun transcript(vararg parts: String) = (listOf("corelink-v3") + parts).joinToString("\n").toByteArray(Charsets.UTF_8)
    private fun sign(key: ByteArray, data: ByteArray): String { val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(key,"HmacSHA256"));return encode(mac.doFinal(data)) }
    private fun deriveSessionKey(deviceKey: ByteArray, id: String, clientNonce: String, serverNonce: String): ByteArray { val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(deviceKey,"HmacSHA256"));return mac.doFinal(transcript("session-key",id,clientNonce,serverNonce)) }
    private data class Session(val id: String, val key: ByteArray)
    private data class ControlConnection(val socket: SSLSocket, val reader: BufferedReader, val writer: OutputStreamWriter)
    private data class Response(val current: JSONObject, val next: JSONObject?)
    companion object {
        fun fromQr(context: Context, qr: String) = CoreLinkClient(context.applicationContext, QrEnrollment.parse(qr))
        fun fromSaved(context: Context): CoreLinkClient? {
            val app = context.applicationContext
            val store = secureStore(app)
            val host = store.getString("saved_host", null) ?: return null
            val port = store.getInt("saved_port", 0)
            val pin = store.getString("saved_pin", null)?.let { runCatching { decode(it) }.getOrNull() } ?: return null
            val secret = store.getString("saved_secret", null)?.let { runCatching { decode(it) }.getOrNull() } ?: return null
            if (!QrEnrollment.isPrivateIpv4(host) || port !in 1..65535 || pin.size != 32 || secret.size != 32) return null
            return CoreLinkClient(app, QrEnrollment(host, port, pin, "", 0)).also { it.secret = secret }
        }
        /** Discovery replies are untrusted hints; TLS pinning happens before any session request. */
        fun reconnectSaved(context: Context): Result<CoreLinkClient> = runCatching {
            val saved = fromSaved(context) ?: error("No saved PC. Scan a CoreLink QR code first.")
            if (saved.connect().isSuccess) return@runCatching saved
            val candidates = discoverCandidates()
            for (host in candidates) {
                if (host == saved.enrollment.host) continue
                val candidate = CoreLinkClient(saved.context, saved.enrollment.copy(host = host)).also { it.secret = saved.secret }
                if (candidate.connect().isSuccess) {
                    check(candidate.store.edit().putString("saved_host", host).commit()) { "Could not save the PC address" }
                    return@runCatching candidate
                }
            }
            error("Could not connect to the saved PC. Start CoreLink on the same Wi-Fi and try again, or scan its QR code.")
        }
        private fun discoverCandidates(): Set<String> {
            val found = linkedSetOf<String>()
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 400
                    val request = "CORELINK_DISCOVER_V1".toByteArray(Charsets.US_ASCII)
                    socket.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), 49877))
                    val until = SystemClock.elapsedRealtime() + 1600
                    while (SystemClock.elapsedRealtime() < until && found.size < 8) {
                        val packet = DatagramPacket(ByteArray(128), 128)
                        try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                        val host = packet.address.hostAddress ?: continue
                        val value = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                        if (discoveryResponseIsValid(host, value)) found.add(host)
                    }
                }
            }
            return found
        }
        internal fun discoveryResponseIsValid(host: String, value: String) =
            QrEnrollment.isPrivateIpv4(host) && value == "CORELINK_HERE_V1:49876"
        fun initialize(context: Context) { secureStore(context) }
        private fun secureStore(context: Context) = EncryptedSharedPreferences.create(context, "corelink_credentials", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(), EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        private fun randomId(): String { val b=ByteArray(16);SecureRandom().nextBytes(b);return encode(b) }
        private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        private fun decode(value: String) = Base64.getUrlDecoder().decode(value)
    }
}
