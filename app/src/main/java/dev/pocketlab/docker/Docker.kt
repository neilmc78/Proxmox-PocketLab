package dev.pocketlab.docker

import dev.pocketlab.data.SshHost
import dev.pocketlab.data.SshKey
import dev.pocketlab.ssh.SshPool
import dev.pocketlab.ssh.shq
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

data class Container(
    val hostId: String,
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val ports: String,
    val composeProject: String?,
    val composeService: String?,
) {
    val running get() = state == "running"
    val shortId get() = id.take(12)
    val health: String? get() = Regex("\\((healthy|unhealthy|health: starting)\\)").find(status)?.groupValues?.get(1)
    val key get() = "$hostId/$id"
}

class DockerClient(private val host: SshHost, private val keys: List<SshKey>) {
    private val json = Json { ignoreUnknownKeys = true }
    /**
     * The docker command on this host. For an LXC reached through its node it runs inside the
     * container with `pct exec <vmid> --` (needs root on the node, so sudo for other users).
     */
    private val docker get() = when {
        host.pctVmid != null -> (if (host.username == "root") "" else "sudo -n ") + "pct exec ${host.pctVmid} -- docker"
        host.dockerSudo -> "sudo -n docker"
        else -> "docker"
    }

    suspend fun list(): List<Container> {
        val r = SshPool.exec(host, keys, "$docker ps -a --no-trunc --format '{{json .}}'")
        if (!r.ok) throw IOException(r.stderr.ifBlank { "docker ps failed (exit ${r.exit})" }.trim())
        return r.stdout.lineSequence().filter { it.isNotBlank() }.map { line ->
            val o = json.parseToJsonElement(line).jsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val labels = s("Labels").split(',').mapNotNull {
                val i = it.indexOf('='); if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
            }.toMap()
            Container(
                hostId = host.id, id = s("ID"), name = s("Names").split(',').first(), image = s("Image"),
                state = s("State"), status = s("Status"), ports = s("Ports"),
                composeProject = labels["com.docker.compose.project"], composeService = labels["com.docker.compose.service"],
            )
        }.sortedWith(compareBy({ it.composeProject ?: "~" }, { it.name })).toList()
    }

    suspend fun action(id: String, action: String) {
        require(action in setOf("start", "stop", "restart", "pause", "unpause", "kill"))
        val r = SshPool.exec(host, keys, "$docker $action ${shq(id)}", timeoutSec = 120)
        if (!r.ok) throw IOException(r.stderr.ifBlank { "docker $action failed" }.trim())
    }

    fun logs(id: String, tail: Int = 500): Flow<String> =
        SshPool.stream(host, keys, "$docker logs --follow --timestamps --tail $tail ${shq(id)}")

    fun execCommand(id: String) = "$docker exec -it ${shq(id)} sh -c 'command -v bash >/dev/null && exec bash || exec sh'"
}
