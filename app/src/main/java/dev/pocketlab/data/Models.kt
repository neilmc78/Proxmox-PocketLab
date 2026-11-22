package dev.pocketlab.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ProxmoxCluster(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** e.g. https://192.168.1.10:8006 */
    val baseUrl: String,
    /** user@realm!tokenname */
    val tokenId: String,
    val tokenSecret: String,
    /** SHA-256 of the leaf certificate, hex, set when the user trusts a self-signed cert. */
    val pinnedCertSha256: String? = null,
)

/** KEY: a stored key. PASSWORD: a stored password. PROMPT: password typed each time, never stored (no background polling). */
@Serializable
enum class SshAuthType { PASSWORD, KEY, PROMPT }

@Serializable
data class SshHost(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType = SshAuthType.KEY,
    val password: String? = null,
    /** Id of a stored key in [AppConfig.keys]. */
    val keyId: String? = null,
    /** "SHA256:..." as OpenSSH prints it, set on first trusted connect. */
    val hostKeyFingerprint: String? = null,
    /** Show Docker containers from this host. */
    val dockerEnabled: Boolean = true,
    /** Prefix docker commands with `sudo -n`. */
    val dockerSudo: Boolean = false,
    /** Proxmox node name this host is (enables "console over SSH" for guests on that node). */
    val proxmoxNode: String? = null,
    /** Cluster that [proxmoxNode] belongs to; node names like "pve" repeat across standalone servers. */
    val proxmoxClusterId: String? = null,
    /**
     * Set only on the in-memory hosts made from [AppConfig.lxcDocker]: commands run inside this LXC
     * through the node's SSH connection (`pct exec <vmid> -- …`). Never stored.
     */
    val pctVmid: Int? = null,
)

/** An LXC whose Docker containers are shown, reached through its Proxmox node's SSH host. */
@Serializable
data class LxcDocker(val clusterId: String, val vmid: Int, val node: String, val name: String)

@Serializable
data class SshKey(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val privateKey: String,
    val publicKey: String? = null,
    val passphrase: String? = null,
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class Appearance(
    val mode: ThemeMode = ThemeMode.DARK,
    val theme: String = "Midnight",
    val accent: String = "Blue",
    val terminalFontSp: Int = 12,
)

@Serializable
data class AppConfig(
    val clusters: List<ProxmoxCluster> = emptyList(),
    val hosts: List<SshHost> = emptyList(),
    val keys: List<SshKey> = emptyList(),
    val appearance: Appearance = Appearance(),
    val lxcDocker: List<LxcDocker> = emptyList(),
) {
    /**
     * SSH host that *is* a given Proxmox node. An explicit (cluster, node) link wins. Links without a
     * cluster, and plain name matches, only count when there's a single cluster: with several, a node
     * called "pve" could be any of them and a console command must never go to the wrong machine.
     */
    fun nodeHost(clusterId: String, node: String): SshHost? {
        hosts.firstOrNull { it.proxmoxClusterId == clusterId && it.proxmoxNode.equals(node, true) }?.let { return it }
        if (clusters.size > 1) return null
        return hosts.firstOrNull { it.proxmoxClusterId == null && it.proxmoxNode.equals(node, true) }
            ?: hosts.firstOrNull { it.proxmoxNode == null && it.name.equals(node, true) }
    }

    /** One Docker host per [lxcDocker] entry whose node has an SSH host: the node's connection, run inside the LXC. */
    fun lxcDockerHosts(): List<SshHost> = lxcDocker.mapNotNull { d ->
        nodeHost(d.clusterId, d.node)?.copy(
            id = lxcDockerId(d.clusterId, d.vmid), name = d.name, dockerEnabled = true, dockerSudo = false,
            proxmoxNode = null, proxmoxClusterId = null, pctVmid = d.vmid,
        )
    }

    /** Stored SSH hosts plus the LXC Docker hosts made from them. */
    fun allHosts(): List<SshHost> = hosts + lxcDockerHosts()

    companion object {
        fun lxcDockerId(clusterId: String, vmid: Int) = "lxc:$clusterId:$vmid"
    }
}
