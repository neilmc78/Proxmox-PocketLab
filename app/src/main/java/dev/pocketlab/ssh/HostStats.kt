package dev.pocketlab.ssh

import dev.pocketlab.data.SshHost
import dev.pocketlab.data.SshKey

/** Any field can be null: hosts without /proc (BSD, busybox variants) still connect and list Docker. */
data class HostStats(val cpu: Double?, val memUsed: Long?, val memTotal: Long?, val uptimeSec: Long?, val load: String?) {
    val memFraction: Double? get() = if (memUsed != null && memTotal != null && memTotal > 0) memUsed.toDouble() / memTotal else null
}

object HostStatsProbe {
    private const val SCRIPT =
        "head -1 /proc/stat; sleep 0.5; head -1 /proc/stat; " +
            "grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; echo \"uptime \$(cut -d' ' -f1 /proc/uptime)\"; " +
            "echo \"load \$(cut -d' ' -f1-3 /proc/loadavg)\""

    suspend fun probe(host: SshHost, keys: List<SshKey>): HostStats {
        val lines = SshPool.exec(host, keys, SCRIPT, 10).stdout.lines()
        return parse(lines)
    }

    internal fun parse(lines: List<String>): HostStats {
        val cpuLines = lines.filter { it.startsWith("cpu ") }.map { l -> l.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() } }
        val cpu = if (cpuLines.size >= 2 && cpuLines[0].size >= 4 && cpuLines[1].size >= 4) {
            val (a, b) = cpuLines
            val idle = { v: List<Long> -> v[3] + v.getOrElse(4) { 0 } }
            val total = (b.sum() - a.sum()).coerceAtLeast(1)
            (1.0 - (idle(b) - idle(a)).toDouble() / total).coerceIn(0.0, 1.0)
        } else null
        fun kb(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024)
        val total = kb("MemTotal:")
        val avail = kb("MemAvailable:")
        val uptime = lines.firstOrNull { it.startsWith("uptime ") }?.removePrefix("uptime ")?.toDoubleOrNull()?.toLong()
        val load = lines.firstOrNull { it.startsWith("load ") }?.removePrefix("load ")?.takeIf { it.isNotBlank() }
        return HostStats(cpu, if (total != null && avail != null) total - avail else null, total, uptime, load)
    }
}
