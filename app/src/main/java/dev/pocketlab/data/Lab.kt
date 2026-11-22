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
    /** The host asks for its password each time, so it isn't polled (no stats, no Docker). */
    val promptOnly: Boolean = false,
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
    /** A stored SSH host, or an LXC Docker host made from one ([AppConfig.lxcDockerHosts]). */
    fun host(hostId: String) = config.allHosts().firstOrNull { it.id == hostId }
    fun docker(hostId: String) = host(hostId)?.let { DockerClient(it, config.keys) }

    /** Bumped whenever a single target is refreshed, so an older full refresh doesn't overwrite it. */
    private val versions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun bump(id: String) { versions.merge(id, 1L, Long::plus) }

    /**
     * Polls everything in parallel. Each target fails on its own (SSH execs have hard deadlines and
     * HTTP calls a call timeout), so one wedged host can't hold up the rest. [wait] = true (pull to
     * refresh) waits for an in-flight poll instead of silently doing nothing.
     */
    suspend fun refresh(wait: Boolean = false) {
        if (wait) mutex.lock() else if (!mutex.tryLock()) return
        try {
            _snap.update { it.copy(refreshing = true) }
            val cfg = config
            val started = HashMap(versions)
            coroutineScope {
                val c = cfg.clusters.map { cl -> async { cl to loadCluster(cl) } }
                val h = cfg.allHosts().map { host -> async { host to loadHost(host, cfg.keys) } }
                val clusters = c.awaitAll()
                val hosts = h.awaitAll()
                _snap.update { cur ->
                    val now = config
                    // Only apply results whose target is unchanged since we started and that no
                    // single-target refresh has superseded meanwhile; drop removed targets.
                    fun fresh(id: String) = versions[id] == started[id]
                    LabSnapshot(
                        clusters = now.clusters.associate { cl ->
                            val r = clusters.firstOrNull { it.first.id == cl.id }
                            cl.id to (if (r != null && r.first == cl && fresh(cl.id)) r.second else cur.clusters[cl.id] ?: ClusterState())
                        },
                        hosts = now.allHosts().associate { ho ->
                            val r = hosts.firstOrNull { it.first.id == ho.id }
                            ho.id to (if (r != null && r.first == ho && fresh(ho.id)) r.second else cur.hosts[ho.id] ?: HostState())
                        },
                        updatedAt = System.currentTimeMillis(),
                        refreshing = false,
                    )
                }
            }
        } finally {
            _snap.update { it.copy(refreshing = false) }
            mutex.unlock()
        }
    }

    suspend fun refreshCluster(id: String) {
        val cl = config.clusters.firstOrNull { it.id == id } ?: return
        val st = loadCluster(cl)
        bump(id)
        _snap.update { it.copy(clusters = it.clusters + (id to st)) }
    }

    suspend fun refreshHost(id: String) {
        val h = host(id) ?: return
        val st = loadHost(h, config.keys)
        bump(id)
        _snap.update { it.copy(hosts = it.hosts + (id to st)) }
    }

    private suspend fun loadCluster(cl: ProxmoxCluster): ClusterState = try {
        val (nodes, guests) = ProxmoxClient(cl).resources()
        ClusterState(nodes, guests, loaded = true)
    } catch (e: Exception) {
        ClusterState(error = e.untrustedCert()?.let { "Certificate not trusted" } ?: e.message ?: e.javaClass.simpleName,
            untrusted = e.untrustedCert(), loaded = true)
    }

    private suspend fun loadHost(h: SshHost, keys: List<SshKey>): HostState {
        // A host that asks for its password each time can't be polled: it connects only when you open a shell.
        if (h.authType == SshAuthType.PROMPT && h.password == null) return HostState(loaded = true, promptOnly = true)
        // Reachability is decided by connecting, not by whether the stats script parses.
        try {
            SshPool.client(h, keys)
        } catch (e: HostKeyException) {
            return HostState(error = e.message, hostKey = e, loaded = true)
        } catch (e: Exception) {
            return HostState(error = dev.pocketlab.ssh.sshErrorMessage(e, h.address, h.port), loaded = true)
        }
        return coroutineScope {
            // An LXC Docker host shares the node's connection; its own CPU/memory come from Proxmox.
            val stats = async { if (h.pctVmid != null) null else runCatching { HostStatsProbe.probe(h, keys) }.getOrNull() }
            val docker = async {
                if (!h.dockerEnabled) emptyList<Container>() to null
                else try { DockerClient(h, keys).list() to null } catch (e: Exception) { emptyList<Container>() to (e.message ?: e.javaClass.simpleName) }
            }
            val (containers, dockerErr) = docker.await()
            HostState(stats.await(), containers, dockerError = dockerErr, loaded = true)
        }
    }

    fun forgetConnections() = SshPool.dropAll()

    /** For UI tests only. */
    internal fun setSnapshotForTest(s: LabSnapshot) { _snap.value = s }
}
