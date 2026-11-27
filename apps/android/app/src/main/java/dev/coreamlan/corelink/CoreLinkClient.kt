package dev.coreamlan.corelink

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import android.util.Base64

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
            val pin = Base64.decode(query["pin"] ?: error("QR code has no certificate pin"), Base64.URL_SAFE or Base64.NO_WRAP)
            require(pin.size == 32) { "Invalid certificate pin" }
            val token = query["token"] ?: error("QR code has no enrollment credential")
            require(Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP).size == 32) { "Invalid enrollment credential" }
            val expiry = query["expires"]?.toLongOrNull() ?: error("QR code has no expiration")
            require(expiry > System.currentTimeMillis() / 1000) { "This QR code has expired" }
            return QrEnrollment(host, port, pin, token, expiry)
        }
        private fun isPrivateIpv4(host: String): Boolean {
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

    fun pair(name: String): Result<Unit> = runCatching {
        check(secret == null) { "This PC is already paired; connect from the dashboard." }
        exchange(JSONObject().put("type", "enroll").put("token", enrollment.token).put("device_id", deviceId).put("name", name)) { response ->
            check(response.current.optString("type") == "approval_required") { "Pairing was rejected" }
            // The TLS connection remains open while the desktop owner decides.
            val paired = response.next ?: error("Desktop pairing did not complete")
            check(paired.optString("type") == "paired") { "Pairing was rejected or timed out" }
            secret = decode(paired.getString("secret"))
            store.edit().putString("secret_${enrollment.host}_${enrollment.port}", encode(secret!!)).apply()
        }
    }

    fun input(body: JSONObject): Result<Unit> = runCatching {
        val session = ensureSession(); val seq = nextSequence()
        val payload = body.toString(); val tag = sign(session.key, transcript("input", session.id, seq.toString(), payload))
        exchange(JSONObject().put("type", "input").put("session_id", session.id).put("sequence", seq).put("payload", body).put("mac", tag)) { response ->
            check(response.current.optString("type") == "ack" && response.current.optBoolean("ok")) { "Desktop rejected this control" }
        }
    }
    fun disconnect() {
        val session = sessionId?.let { id -> sessionKey?.let { key -> Session(id, key) } } ?: return
        runCatching { val seq=nextSequence(); exchange(JSONObject().put("type","disconnect").put("session_id",session.id).put("sequence",seq).put("mac",sign(session.key,transcript("disconnect",session.id,seq.toString())))) { response -> check(response.current.optBoolean("ok")) } }
        sessionId = null; sessionKey = null; sequence = 0
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
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedServerTrustManager(enrollment.pin)), SecureRandom()) }
        (ssl.socketFactory.createSocket(enrollment.host, enrollment.port) as SSLSocket).use { socket ->
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.soTimeout = 65_000
            socket.startHandshake() // pin is checked before the enrollment token is written.
            val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8); writer.write(request.toString() + "\n"); writer.flush()
            val reader = BufferedReader(socket.inputStream.reader(Charsets.UTF_8)); val first=JSONObject(reader.readLine() ?: error("Desktop closed the connection"))
            val second = if (first.optString("type") == "approval_required") JSONObject(reader.readLine() ?: error("Pairing timed out")) else null
            consume(Response(first, second))
        }
    }
    private fun nextSequence(): Long { sequence += 1; return sequence }
    private fun transcript(vararg parts: String) = (listOf("corelink-v3") + parts).joinToString("\n").toByteArray(Charsets.UTF_8)
    private fun sign(key: ByteArray, data: ByteArray): String { val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(key,"HmacSHA256"));return encode(mac.doFinal(data)) }
    private fun deriveSessionKey(deviceKey: ByteArray, id: String, clientNonce: String, serverNonce: String): ByteArray { val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(deviceKey,"HmacSHA256"));return mac.doFinal(transcript("session-key",id,clientNonce,serverNonce)) }
    private data class Session(val id: String, val key: ByteArray)
    private data class Response(val current: JSONObject, val next: JSONObject?)
    companion object {
        fun fromQr(context: Context, qr: String) = CoreLinkClient(context.applicationContext, QrEnrollment.parse(qr))
        fun initialize(context: Context) { secureStore(context) }
        private fun secureStore(context: Context) = EncryptedSharedPreferences.create(context, "corelink_credentials", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(), EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        private fun randomId(): String { val b=ByteArray(16);SecureRandom().nextBytes(b);return encode(b) }
        private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP)
        private fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
    }
}
