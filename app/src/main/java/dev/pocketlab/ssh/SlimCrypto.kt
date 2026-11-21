package dev.pocketlab.ssh

import com.hierynomus.sshj.key.KeyAlgorithms
import com.hierynomus.sshj.transport.kex.ExtInfoClientFactory
import com.hierynomus.sshj.transport.mac.Macs
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.kex.Curve25519SHA256
import net.schmizz.sshj.transport.kex.DHGexSHA256
import net.schmizz.sshj.transport.kex.ECDHNistP
import com.hierynomus.sshj.transport.kex.DHGroups
import org.bouncycastle.jcajce.provider.asymmetric.edec.KeyAgreementSpi
import org.bouncycastle.jcajce.provider.asymmetric.edec.KeyFactorySpi
import org.bouncycastle.jcajce.provider.asymmetric.edec.KeyPairGeneratorSpi
import org.bouncycastle.jcajce.provider.symmetric.ChaCha
import org.bouncycastle.jcajce.provider.symmetric.Poly1305
import java.security.Provider
import java.security.Security

/**
 * The only JCA algorithms SSH needs that Android's built-in providers lack on older releases:
 * X25519 (curve25519 key exchange) and raw ChaCha20 + Poly1305 (chacha20-poly1305@openssh.com).
 *
 * Registering BouncyCastle's full provider instead would work too, but it loads hundreds of
 * algorithm classes by name, which forces the shrinker to keep all of them (~8 MB of dex).
 * Here every implementation is constructed directly, so R8 keeps only these few classes.
 * Everything else (AES, ECDH, RSA, ECDSA, HMAC-SHA2) comes from the platform (Conscrypt).
 */
class SlimCryptoProvider : @Suppress("DEPRECATION") Provider(NAME, 1.0, "X25519, ChaCha20, Poly1305 for SSH") {
    private class Svc(p: Provider, type: String, algorithm: String, aliases: List<String>, private val make: () -> Any) :
        Provider.Service(p, type, algorithm, "dev.pocketlab.ssh.SlimCryptoProvider\$$type\$$algorithm", aliases, null) {
        override fun newInstance(constructorParameter: Any?): Any = make()
        override fun supportsParameter(parameter: Any?) = true
    }

    init {
        putService(Svc(this, "KeyPairGenerator", "X25519", listOf("1.3.101.110", "OID.1.3.101.110")) { KeyPairGeneratorSpi.X25519() })
        putService(Svc(this, "KeyFactory", "X25519", listOf("1.3.101.110", "OID.1.3.101.110")) { KeyFactorySpi.X25519() })
        putService(Svc(this, "KeyAgreement", "X25519", listOf("1.3.101.110", "OID.1.3.101.110")) { KeyAgreementSpi.X25519() })
        putService(Svc(this, "Cipher", "CHACHA", emptyList()) { ChaCha.Base() })
        putService(Svc(this, "Mac", "POLY1305", emptyList()) { Poly1305.Mac() })
    }

    companion object {
        const val NAME = "PocketlabSlim"

        /**
         * Idempotent. Goes first so X25519's key generator, key factory and agreement always come from
         * the same provider (mixing BC keys with Conscrypt's agreement would fail on Android 13+).
         * It only offers these five algorithms, so nothing else in the process is affected.
         */
        @Synchronized fun install() {
            if (Security.getProvider(NAME) == null) Security.insertProviderAt(SlimCryptoProvider(), 1)
            // Never let sshj pull in (or pin itself to) a "BC" provider; use default JCA lookup.
            SecurityUtils.setRegisterBouncyCastle(false)
        }
    }
}

/**
 * sshj's defaults minus legacy algorithms: no group1/gex-sha1 key exchange and no MD5, RIPEMD or
 * truncated-96 MACs. Order is strongest-first; the server picks the first one both sides support.
 * Ciphers: AEAD and CTR modes only. sshj's default list offers AES-CBC second (ahead of GCM/CTR) plus
 * arcfour, blowfish and 3DES, so a server without ChaCha20 would have negotiated CBC.
 */
class HardenedSshConfig : DefaultConfig() {
    companion object {
        val PREFERRED_CIPHERS = listOf(
            "chacha20-poly1305@openssh.com", "aes256-gcm@openssh.com", "aes128-gcm@openssh.com",
            "aes256-ctr", "aes192-ctr", "aes128-ctr",
        )
    }

    override fun initCipherFactories() {
        super.initCipherFactories() // probes which ciphers actually work on this device
        val available = cipherFactories.associateBy { it.name }
        cipherFactories = PREFERRED_CIPHERS.mapNotNull { available[it] }
    }

    override fun initKeyExchangeFactories() {
        keyExchangeFactories = listOf(
            Curve25519SHA256.Factory(),
            Curve25519SHA256.FactoryLibSsh(),
            ECDHNistP.Factory521(),
            ECDHNistP.Factory384(),
            ECDHNistP.Factory256(),
            DHGexSHA256.Factory(),
            DHGroups.Group18SHA512(),
            DHGroups.Group16SHA512(),
            DHGroups.Group14SHA256(),
            ExtInfoClientFactory(),
        )
    }

    override fun initMACFactories() {
        macFactories = listOf(
            Macs.HMACSHA2512Etm(), Macs.HMACSHA2256Etm(),
            Macs.HMACSHA2512(), Macs.HMACSHA2256(),
            Macs.HMACSHA1Etm(), Macs.HMACSHA1(),
        )
    }

    override fun initKeyAlgorithms() {
        // No DSA (removed from OpenSSH). ssh-rsa (SHA-1 signatures) only as a last resort for old servers.
        // No *-cert-v01 host key types: we pin plain host keys (there's no CA to verify certificates
        // against), and a certificate's fingerprint wouldn't match `ssh-keygen -lf` and would change
        // on every re-issue.
        keyAlgorithms = listOf(
            KeyAlgorithms.EdDSA25519(),
            KeyAlgorithms.SkSSHEd25519(), KeyAlgorithms.SkECDSANistp256(),
            KeyAlgorithms.ECDSASHANistp521(), KeyAlgorithms.ECDSASHANistp384(), KeyAlgorithms.ECDSASHANistp256(),
            KeyAlgorithms.RSASHA512(), KeyAlgorithms.RSASHA256(),
            KeyAlgorithms.SSHRSA(),
        )
    }
}
