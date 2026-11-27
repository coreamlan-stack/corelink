package dev.coreamlan.corelink

import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class PinnedServerTrustManagerTest {
    private fun certificate(): X509Certificate {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        return manager.acceptedIssuers.first { runCatching { it.checkValidity() }.isSuccess }
    }

    @Test fun acceptsExactCertificateAndRejectsDifferentPin() {
        val cert = certificate()
        val pin = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        PinnedServerTrustManager(pin).checkServerTrusted(arrayOf(cert), "RSA")
        pin[0] = (pin[0].toInt() xor 1).toByte()
        assertThrows(CertificateException::class.java) {
            PinnedServerTrustManager(pin).checkServerTrusted(arrayOf(cert), "RSA")
        }
    }

    @Test fun rejectsMissingChainAndClientTrust() {
        val verifier = PinnedServerTrustManager(ByteArray(32))
        assertThrows(CertificateException::class.java) { verifier.checkServerTrusted(emptyArray(), "RSA") }
        assertThrows(CertificateException::class.java) { verifier.checkClientTrusted(arrayOf(certificate()), "RSA") }
        assertThrows(IllegalArgumentException::class.java) { PinnedServerTrustManager(ByteArray(31)) }
    }

    @Test fun acceptsUnpaddedUrlSafeQrCredentials() {
        val pin = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 2 })

        val enrollment = QrEnrollment.parse("corelink://pair?v=3&host=192.168.1.20&port=49876&pin=$pin&token=$token&expires=4102444800")

        org.junit.Assert.assertEquals("192.168.1.20", enrollment.host)
        org.junit.Assert.assertEquals(32, enrollment.pin.size)
        org.junit.Assert.assertEquals(token, enrollment.token)
    }
}
