package dev.pocketlab

import dev.pocketlab.data.ProxmoxCluster
import dev.pocketlab.net.untrustedCert
import dev.pocketlab.proxmox.GuestType
import dev.pocketlab.proxmox.ProxmoxClient
import dev.pocketlab.proxmox.PveException
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class ProxmoxClientTest {
    private val cert = HeldCertificate.Builder().commonName("pve1").addSubjectAlternativeName("pve1.lan").build()
    private val server = MockWebServer()
    private val fp = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).joinToString(":") { "%02X".format(it) }
    private val requests = mutableListOf<RecordedRequest>()

    @Before fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val auth = request.headers["Authorization"]
                if (auth != "PVEAPIToken=me@pve!phone=secret") return MockResponse.Builder().code(401).build()
                val path = request.url.encodedPath
                val body = when {
                    path.endsWith("/version") -> """{"data":{"version":"9.1.2"}}"""
                    path.endsWith("/cluster/resources") -> """{"data":[
                        {"type":"node","node":"pve1","status":"online","cpu":0.012,"maxcpu":8,"mem":3000000000,"maxmem":16000000000,"uptime":100},
                        {"type":"qemu","node":"pve1","vmid":202,"name":"docker","status":"running","cpu":0.02,"maxcpu":2,"mem":2147483648,"maxmem":2147483648,"uptime":1209600,"tags":"clustara;disposable","template":0},
                        {"type":"lxc","node":"pve1","vmid":301,"name":"ct","status":"stopped","cpu":0,"maxcpu":1,"mem":0,"maxmem":536870912,"uptime":0},
                        {"type":"storage","node":"pve1","storage":"local"}]}"""
                    path.endsWith("/status/shutdown") -> """{"data":"UPID:pve1:0001:shutdown:202:me@pve!phone:"}"""
                    path.contains("/tasks/") -> """{"data":{"status":"stopped","exitstatus":"OK"}}"""
                    else -> return MockResponse.Builder().code(404).build()
                }
                return MockResponse.Builder().body(body).build()
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    private fun cluster(pin: String? = null, secret: String = "secret") = ProxmoxCluster(
        name = "lab", baseUrl = "https://127.0.0.1:${server.port}", tokenId = "me@pve!phone", tokenSecret = secret, pinnedCertSha256 = pin)

    @Test fun selfSignedCertIsRefusedUntilPinned() = runBlocking {
        val e = runCatching { ProxmoxClient(cluster()).version() }.exceptionOrNull()
        val u = e?.untrustedCert()
        assertNotNull("expected untrusted cert, got $e", u)
        assertEquals(fp, u!!.fingerprint)
        // Pinned: accepted even though the cert is for "pve1", not 127.0.0.1.
        assertEquals("9.1.2", ProxmoxClient(cluster(pin = fp)).version())
    }

    @Test fun wrongPinIsRefused() = runBlocking {
        val e = runCatching { ProxmoxClient(cluster(pin = "00:11")).version() }.exceptionOrNull()
        assertNotNull(e?.untrustedCert())
    }

    @Test fun badTokenGivesReadableError() = runBlocking {
        val e = runCatching { ProxmoxClient(cluster(pin = fp, secret = "nope")).version() }.exceptionOrNull()
        assertTrue(e is PveException && e.status == 401 && e.message!!.contains("token"))
    }

    @Test fun parsesResourcesAndRunsPowerTask() = runBlocking {
        val c = ProxmoxClient(cluster(pin = fp))
        val (nodes, guests) = c.resources()
        assertEquals(listOf("pve1"), nodes.map { it.name })
        assertEquals(2, guests.size)
        val vm = guests.first { it.vmid == 202 }
        assertEquals(GuestType.QEMU, vm.type); assertEquals(listOf("clustara", "disposable"), vm.tags); assertTrue(vm.running)
        assertEquals("OK", c.power(vm, "shutdown"))
        val post = requests.first { it.method == "POST" }
        assertEquals("/api2/json/nodes/pve1/qemu/202/status/shutdown", post.url.encodedPath)
    }
}
