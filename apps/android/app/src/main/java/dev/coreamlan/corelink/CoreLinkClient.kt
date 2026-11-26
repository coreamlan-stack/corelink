package dev.coreamlan.corelink

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Wire client for the desktop's local-only protocol. It never opens a listener. */
class CoreLinkClient(private val host: String, private val port: Int = 49876) {
    private val deviceId = "android-" + android.os.Build.SERIAL.takeLast(8)
    private var key: ByteArray? = null
    private fun hash(vararg values: ByteArray) = MessageDigest.getInstance("SHA-256").digest(values.fold(ByteArray(0)) { a, b -> a + b })
    private fun b64(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun socket(): Pair<Socket, BufferedReader> {
        val address = InetAddress.getByName(host)
        require(address is Inet4Address && (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress)) { "CoreLink accepts local Wi-Fi addresses only" }
        val s = Socket(address, port).apply { soTimeout = 7000 }
        return s to s.getInputStream().bufferedReader()
    }
    private fun send(s: Socket, value: JSONObject) { val writer=OutputStreamWriter(s.getOutputStream()); writer.write(value.toString()+"\n"); writer.flush() }
    fun pair(code: String, name: String): Result<Unit> = runCatching {
        require(code.matches(Regex("\\d{6}"))) { "Enter the six-digit pairing code" }
        val (helloSocket, helloReader) = socket(); send(helloSocket, JSONObject().put("type","hello")); val hello=JSONObject(helloReader.readLine()); helloSocket.close()
        val nonce = hello.getString("nonce"); val clientNonce=ByteArray(16).also { SecureRandom().nextBytes(it) }; val clientNonceText=b64(clientNonce)
        val proof=b64(hash(deviceId.toByteArray(), code.toByteArray(), nonce.toByteArray(), clientNonceText.toByteArray()))
        val (s,r)=socket(); send(s,JSONObject().put("type","pair_request").put("id",deviceId).put("name",name).put("proof",proof).put("client_nonce",clientNonceText))
        val waiting=JSONObject(r.readLine()); require(waiting.getString("type")=="approval_required") { "Desktop rejected the pairing request" }
        val reply=JSONObject(r.readLine()); require(reply.getString("type")=="paired") { "Pairing was not approved" }
        val candidate=hash(code.toByteArray(),nonce.toByteArray(),clientNonceText.toByteArray(),deviceId.toByteArray())
        require(reply.getString("confirm")==b64(hash(candidate,"server-confirm".toByteArray()))) { "Desktop identity confirmation failed" }
        key=candidate; s.close()
    }
    fun input(body: JSONObject): Result<Unit> = runCatching {
        val session=key ?: error("Pair this phone first")
        val nonce=ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,javax.crypto.spec.SecretKeySpec(session,"AES"),GCMParameterSpec(128,nonce))
        val (s,r)=socket(); send(s,JSONObject().put("type","input").put("id",deviceId).put("nonce",b64(nonce)).put("ciphertext",b64(cipher.doFinal(body.toString().toByteArray()))))
        require(JSONObject(r.readLine()).optBoolean("ok")) { "Desktop rejected the input" }; s.close()
    }
    fun disconnect() { key=null }
}
