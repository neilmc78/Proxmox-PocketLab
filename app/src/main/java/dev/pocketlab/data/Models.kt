package dev.pocketlab.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ProxmoxCluster(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** e.g. https://10.0.10.81:8006 */
    val baseUrl: String,
    /** user@realm!tokenname */
    val tokenId: String,
    val tokenSecret: String,
    /** SHA-256 of the leaf certificate, hex, set when the user trusts a self-signed cert. */
    val pinnedCertSha256: String? = null,
)

@Serializable
enum class SshAuthType { PASSWORD, KEY }

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
)

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
)
