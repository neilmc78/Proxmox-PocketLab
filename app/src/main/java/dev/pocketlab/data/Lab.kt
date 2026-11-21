package dev.pocketlab.data

import dev.pocketlab.docker.Container
import dev.pocketlab.docker.DockerClient
import dev.pocketlab.net.UntrustedCertificateException
import dev.pocketlab.net.untrustedCert
import dev.pocketlab.proxmox.ProxmoxClient
import dev.pocketlab.proxmox.PveGuest
import dev.pocketlab.proxmox.PveNode
import dev.pocketlab.ssh.HostKeyException
import dev.pocketlab.ssh.HostStats
import dev.pocketlab.ssh.HostStatsProbe
import dev.pocketlab.ssh.SshPool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ClusterState(
    val nodes: List<PveNode> = emptyList(),
    val guests: List<PveGuest> = emptyList(),
    val error: String? = null,
    val untrusted: UntrustedCertificateException? = null,
    val loaded: Boolean = false,
)

data class HostState(
    val stats: HostStats? = null,
    val containers: List<Container> = emptyList(),
    val error: String? = null,
    val dockerError: String? = null,
    val hostKey: HostKeyException? = null,
    val loaded: Boolean = false,
)

data class LabSnapshot(
    val clusters: Map<String, ClusterState> = emptyMap(),
    val hosts: Map<String, HostState> = emptyMap(),
    val updatedAt: Long = 0,
    val refreshing: Boolean = false,
)

/** Polls every configured cluster and host. One instance for the app. */
class Lab(val store: SecureStore) {
    private val _snap = MutableStateFlow(LabSnapshot())
    val snapshot: StateFlow<LabSnapshot> = _snap.asStateFlow()
    private val mutex = Mutex()

    val config get() = store.config.value

    fun client(clusterId: String) = config.clusters.firstOrNull { it.id == clusterId }?.let(::ProxmoxClient)
    fun host(hostId: String) = config.hosts.firstOrNull { it.id == hostId }
    fun docker(hostId: String) = host(hostId)?.let { DockerClient(it, config.keys) }

    suspend fun refresh() {
        if (!mutex.tryLock()) return
        try {
            _snap.update { it.copy(refreshing = true) }
            val cfg = config
            coroutineScope {
                val c = cfg.clusters.map { cl -> async { cl.id to loadCluster(cl) } }
                val h = cfg.hosts.map { host -> async { host.id to loadHost(host, cfg.keys) } }
                val clusters = c.awaitAll().toMap()
                val hosts = h.awaitAll().toMap()
                _snap.value = LabSnapshot(clusters, hosts, System.currentTimeMillis(), false)
            }
        } finally {
            _snap.update { it.copy(refreshing = false) }
            mutex.unlock()
        }
    }

    suspend fun refreshCluster(id: String) {
        val cl = config.clusters.firstOrNull { it.id == id } ?: return
        val st = loadCluster(cl)
        _snap.update { it.copy(clusters = it.clusters + (id to st)) }
    }

    suspend fun refreshHost(id: String) {
        val h = host(id) ?: return
        val st = loadHost(h, config.keys)
        _snap.update { it.copy(hosts = it.hosts + (id to st)) }
    }

    private suspend fun loadCluster(cl: ProxmoxCluster): ClusterState = try {
        val (nodes, guests) = ProxmoxClient(cl).resources()
        ClusterState(nodes, guests, loaded = true)
    } catch (e: Exception) {
        ClusterState(error = e.untrustedCert()?.let { "Certificate not trusted" } ?: e.message ?: e.javaClass.simpleName,
            untrusted = e.untrustedCert(), loaded = true)
    }

    private suspend fun loadHost(h: SshHost, keys: List<SshKey>): HostState = coroutineScope {
        try {
            val stats = HostStatsProbe.probe(h, keys)
            var containers = emptyList<Container>()
            var dockerErr: String? = null
            if (h.dockerEnabled) {
                try { containers = DockerClient(h, keys).list() } catch (e: Exception) { dockerErr = e.message }
            }
            HostState(stats, containers, dockerError = dockerErr, loaded = true)
        } catch (e: HostKeyException) {
            HostState(error = e.message, hostKey = e, loaded = true)
        } catch (e: Exception) {
            HostState(error = e.message ?: e.javaClass.simpleName, loaded = true)
        }
    }

    fun forgetConnections() = SshPool.dropAll()

    /** For UI tests only. */
    internal fun setSnapshotForTest(s: LabSnapshot) { _snap.value = s }
}
