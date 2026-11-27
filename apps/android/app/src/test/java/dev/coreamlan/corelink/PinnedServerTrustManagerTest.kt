package dev.coreamlan.corelink

import org.junit.Assert.assertThrows
import org.junit.Test
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
}
