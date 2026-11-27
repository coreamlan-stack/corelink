package dev.coreamlan.corelink

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Trusts only the exact server certificate identified out of band in a QR code. */
internal class PinnedServerTrustManager(pin: ByteArray) : X509TrustManager {
    private val expected = pin.copyOf().also { require(it.size == 32) }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty() || authType.isNullOrBlank()) throw CertificateException("Invalid server certificate")
        val leaf = chain[0]
        leaf.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        if (!MessageDigest.isEqual(expected, actual)) throw CertificateException("Server identity mismatch")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not accepted here")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
