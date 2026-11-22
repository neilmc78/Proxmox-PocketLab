package dev.pocketlab

import dev.pocketlab.ssh.SlimCryptoProvider
import dev.pocketlab.ssh.generateEd25519
import dev.pocketlab.ssh.hostKeyFingerprint
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Ed25519KeyFactory
import net.schmizz.sshj.common.KeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * On Android, Conscrypt's Ed25519 keys report an algorithm name sshj doesn't recognise, so every
 * server with an Ed25519 host key failed with "Don't know how to encode key". Ed25519 must come from
 * our provider, and fingerprints must not depend on sshj recognising the key class.
 */
class Ed25519Test {
    @Before fun setUp() = SlimCryptoProvider.install()

    private fun rawAndBlob(): Pair<ByteArray, ByteArray> {
        val blob = Base64.getDecoder().decode(generateEd25519("t").second.split(' ')[1])
        val buf = Buffer.PlainBuffer(blob)
        assertEquals("ssh-ed25519", buf.readString())
        return buf.readBytes() to blob
    }

    private fun expectedFingerprint(blob: ByteArray) =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    @Test fun ed25519ComesFromOurProvider() {
        assertEquals(SlimCryptoProvider.NAME, KeyFactory.getInstance("Ed25519").provider.name)
        assertEquals(SlimCryptoProvider.NAME, Signature.getInstance("Ed25519").provider.name)
    }

    @Test fun hostKeyDecodesAndFingerprintsLikeSshKeygen() {
        val (raw, blob) = rawAndBlob()
        val key = Ed25519KeyFactory.getPublicKey(raw)            // what sshj does with the server's host key
        assertEquals(KeyType.ED25519, KeyType.fromKey(key))
        assertEquals(expectedFingerprint(blob), hostKeyFingerprint(key))
    }

    @Test fun fingerprintWorksForAnEd25519KeySshjDoesNotRecognise() {
        val (raw, blob) = rawAndBlob()
        val spki = Base64.getDecoder().decode("MCowBQYDK2VwAyEA") + raw
        val foreign = object : PublicKey {                       // like Conscrypt's OpenSslEdDsaPublicKey
            override fun getAlgorithm() = "OpenSslEdDsa"
            override fun getFormat() = "X.509"
            override fun getEncoded() = spki
        }
        assertEquals(KeyType.UNKNOWN, KeyType.fromKey(foreign))
        assertEquals(expectedFingerprint(blob), hostKeyFingerprint(foreign))
    }

    @Test fun signAndVerifyRoundTrip() {
        // Keys from another provider (the JDK here, Conscrypt on a phone), re-imported through ours.
        val kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val kf = KeyFactory.getInstance("Ed25519")
        val priv = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(kp.private.encoded))
        val pub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(kp.public.encoded))
        val msg = "pocketlab".toByteArray()
        val sig = Signature.getInstance("Ed25519").apply { initSign(priv); update(msg) }.sign()
        assertTrue(Signature.getInstance("Ed25519").apply { initVerify(pub); update(msg) }.verify(sig))
    }
}
