package dev.pocketlab.net

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

fun X509Certificate.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(":") { "%02X".format(it) }

/** Thrown when a server presents a certificate that is neither publicly trusted nor pinned. */
class UntrustedCertificateException(val fingerprint: String, val subject: String) :
    CertificateException("Untrusted certificate $fingerprint")

/**
 * Trusts the system CAs, or exactly one pinned leaf certificate (trust on first use, confirmed by
 * the user). A pinned cert is accepted whatever host name it carries, because Proxmox's self-signed
 * certs usually do not match the IP you reach them on; the pin itself is the identity check.
 */
class PinningTrustManager(private val pinnedSha256: String?) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    @Volatile var lastLeaf: X509Certificate? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("client auth not supported")

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.first()
        lastLeaf = leaf
        val fp = leaf.sha256Hex()
        if (pinnedSha256 != null) {
            if (fp.equals(pinnedSha256, ignoreCase = true)) return
            throw UntrustedCertificateException(fp, leaf.subjectX500Principal.name)
        }
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            throw UntrustedCertificateException(fp, leaf.subjectX500Principal.name)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

object HttpClients {
    private val base = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun forPin(pinnedSha256: String?): OkHttpClient {
        val tm = PinningTrustManager(pinnedSha256)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val default = HttpsURLConnection.getDefaultHostnameVerifier()
        val verifier = HostnameVerifier { host, session ->
            if (pinnedSha256 != null) {
                val leaf = try { session.peerCertificates.first() as X509Certificate } catch (e: SSLPeerUnverifiedException) { return@HostnameVerifier false }
                leaf.sha256Hex().equals(pinnedSha256, ignoreCase = true)
            } else default.verify(host, session)
        }
        return base.newBuilder().sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier(verifier).build()
    }
}

/** Walk an exception's causes looking for a certificate problem the user can resolve by pinning. */
fun Throwable.untrustedCert(): UntrustedCertificateException? {
    var t: Throwable? = this
    while (t != null) {
        if (t is UntrustedCertificateException) return t
        t = t.cause
    }
    return null
}
