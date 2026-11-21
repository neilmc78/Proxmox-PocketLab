package dev.pocketlab.proxmox

import dev.pocketlab.data.ProxmoxCluster
import dev.pocketlab.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

enum class GuestType(val api: String, val label: String) { QEMU("qemu", "VM"), LXC("lxc", "LXC") }

data class PveNode(
    val clusterId: String,
    val name: String,
    val online: Boolean,
    val cpu: Double,
    val maxCpu: Int,
    val mem: Long,
    val maxMem: Long,
    val uptime: Long,
)

data class PveGuest(
    val clusterId: String,
    val node: String,
    val type: GuestType,
    val vmid: Int,
    val name: String,
    val status: String,
    val cpu: Double,
    val maxCpu: Int,
    val mem: Long,
    val maxMem: Long,
    val uptime: Long,
    val tags: List<String>,
    val template: Boolean,
) {
    val running get() = status == "running"
    val key get() = "$clusterId/$node/${type.api}/$vmid"
}

data class RrdPoint(val time: Long, val cpu: Double?, val mem: Double?, val maxMem: Double?)
data class Snapshot(val name: String, val description: String, val time: Long?, val parent: String?)

class PveException(val status: Int, message: String) : IOException(message)

class ProxmoxClient(private val cluster: ProxmoxCluster) {
    private val http = HttpClients.forPin(cluster.pinnedCertSha256)
    private val json = Json { ignoreUnknownKeys = true }
    private val base = cluster.baseUrl.trimEnd('/') + "/api2/json"

    private fun auth(b: Request.Builder) =
        b.header("Authorization", "PVEAPIToken=${cluster.tokenId}=${cluster.tokenSecret}")

    private suspend fun call(method: String, path: String, form: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val url = (base + path).toHttpUrl()
            val rb = auth(Request.Builder().url(url))
            when (method) {
                "GET" -> rb.get()
                else -> rb.method(method, FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
            }
            http.newCall(rb.build()).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    val msg = when (resp.code) {
                        401 -> "Authentication failed: check the API token ID and secret"
                        403 -> "Permission denied: the token lacks rights for this (${resp.message})"
                        else -> "HTTP ${resp.code}: ${resp.message}"
                    }
                    throw PveException(resp.code, msg)
                }
                json.parseToJsonElement(body).jsonObject["data"] ?: JsonNull
            }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun version(): String =
        call("GET", "/version").jsonObject.str("version") ?: "?"

    suspend fun resources(): Pair<List<PveNode>, List<PveGuest>> {
        val arr = call("GET", "/cluster/resources").jsonArray
        val nodes = mutableListOf<PveNode>()
        val guests = mutableListOf<PveGuest>()
        for (e in arr) {
            val o = e.jsonObject
            when (o.str("type")) {
                "node" -> nodes += PveNode(
                    clusterId = cluster.id,
                    name = o.str("node") ?: "?",
                    online = o.str("status") == "online",
                    cpu = o.dbl("cpu"), maxCpu = o.lng("maxcpu").toInt(),
                    mem = o.lng("mem"), maxMem = o.lng("maxmem"), uptime = o.lng("uptime"),
                )
                "qemu", "lxc" -> guests += PveGuest(
                    clusterId = cluster.id,
                    node = o.str("node") ?: "?",
                    type = if (o.str("type") == "qemu") GuestType.QEMU else GuestType.LXC,
                    vmid = o.lng("vmid").toInt(),
                    name = o.str("name") ?: "#${o.lng("vmid")}",
                    status = o.str("status") ?: "unknown",
                    cpu = o.dbl("cpu"), maxCpu = o.lng("maxcpu").toInt(),
                    mem = o.lng("mem"), maxMem = o.lng("maxmem"), uptime = o.lng("uptime"),
                    tags = o.str("tags")?.split(';', ',', ' ')?.filter { it.isNotBlank() } ?: emptyList(),
                    template = o.lng("template") == 1L,
                )
            }
        }
        return nodes.sortedBy { it.name } to guests.sortedWith(compareBy({ it.node }, { it.vmid }))
    }

    private fun gp(g: PveGuest) = "/nodes/${enc(g.node)}/${g.type.api}/${g.vmid}"

    suspend fun config(g: PveGuest): Map<String, String> =
        call("GET", "${gp(g)}/config").jsonObject
            .mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }

    /**
     * /cluster/resources reports a paused VM as "running"; only status/current's qmpstatus tells.
     * Returns the guest with status "paused" when QEMU is paused or suspended.
     */
    suspend fun withLiveStatus(g: PveGuest): PveGuest {
        if (g.type != GuestType.QEMU || !g.running) return g
        val qmp = call("GET", "${gp(g)}/status/current").jsonObject.str("qmpstatus")
        return if (qmp == "paused" || qmp == "suspended") g.copy(status = "paused") else g
    }

    suspend fun rrd(g: PveGuest): List<RrdPoint> =
        call("GET", "${gp(g)}/rrddata?timeframe=hour&cf=AVERAGE").jsonArray.map {
            val o = it.jsonObject
            RrdPoint(o.lng("time"), o.dblOrNull("cpu"), o.dblOrNull("mem"), o.dblOrNull("maxmem"))
        }

    suspend fun nodeRrd(node: String): List<RrdPoint> =
        call("GET", "/nodes/${enc(node)}/rrddata?timeframe=hour&cf=AVERAGE").jsonArray.map {
            val o = it.jsonObject
            RrdPoint(o.lng("time"), o.dblOrNull("cpu"), o.dblOrNull("memused"), o.dblOrNull("memtotal"))
        }

    /** Starts a power action and waits for its task to finish. Returns the task's exit status. */
    suspend fun power(g: PveGuest, action: String): String {
        val upid = call("POST", "${gp(g)}/status/$action").jsonPrimitive()
        return waitTask(g.node, upid)
    }

    suspend fun snapshots(g: PveGuest): List<Snapshot> =
        call("GET", "${gp(g)}/snapshot").jsonArray.map {
            val o = it.jsonObject
            Snapshot(o.str("name") ?: "?", o.str("description") ?: "", o.lng("snaptime").takeIf { t -> t > 0 }, o.str("parent"))
        }.filter { it.name != "current" }.sortedByDescending { it.time ?: 0 }

    suspend fun createSnapshot(g: PveGuest, name: String, description: String, withRam: Boolean): String {
        val form = buildMap {
            put("snapname", name)
            if (description.isNotBlank()) put("description", description)
            if (g.type == GuestType.QEMU && withRam) put("vmstate", "1")
        }
        val upid = call("POST", "${gp(g)}/snapshot", form).jsonPrimitive()
        return waitTask(g.node, upid)
    }

    suspend fun rollback(g: PveGuest, snap: String): String {
        val upid = call("POST", "${gp(g)}/snapshot/${enc(snap)}/rollback").jsonPrimitive()
        return waitTask(g.node, upid)
    }

    private suspend fun waitTask(node: String, upid: String): String {
        repeat(240) {
            val st = call("GET", "/nodes/${enc(node)}/tasks/${enc(upid)}/status").jsonObject
            if (st.str("status") == "stopped") return st.str("exitstatus") ?: "unknown"
            delay(500)
        }
        return "still running (check Proxmox task log)"
    }

    private fun JsonElement.jsonPrimitive(): String = (this as? JsonPrimitive)?.contentOrNull ?: toString()
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.dbl(k: String): Double = dblOrNull(k) ?: 0.0
private fun JsonObject.dblOrNull(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.lng(k: String): Long = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
@Suppress("unused") private fun JsonElement.arr(): JsonArray = jsonArray
