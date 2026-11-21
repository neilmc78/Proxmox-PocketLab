package dev.pocketlab.ssh

import dev.pocketlab.data.SshHost
import dev.pocketlab.data.SshKey

data class HostStats(val cpu: Double, val memUsed: Long, val memTotal: Long, val uptimeSec: Long, val load: String)

object HostStatsProbe {
    private const val SCRIPT =
        "head -1 /proc/stat; sleep 0.5; head -1 /proc/stat; " +
            "grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; cut -d' ' -f1 /proc/uptime; cut -d' ' -f1-3 /proc/loadavg"

    suspend fun probe(host: SshHost, keys: List<SshKey>): HostStats {
        val r = SshPool.exec(host, keys, SCRIPT, 10)
        val lines = r.stdout.lines().filter { it.isNotBlank() }
        fun cpuLine(l: String) = l.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        val a = cpuLine(lines[0]); val b = cpuLine(lines[1])
        val idle = { v: List<Long> -> v[3] + v.getOrElse(4) { 0 } }
        val total = (b.sum() - a.sum()).coerceAtLeast(1)
        val cpu = 1.0 - (idle(b) - idle(a)).toDouble() / total
        fun kb(prefix: String) = lines.first { it.startsWith(prefix) }.split(Regex("\\s+"))[1].toLong() * 1024
        val memTotal = kb("MemTotal:"); val memAvail = kb("MemAvailable:")
        val uptime = lines.getOrNull(4)?.toDoubleOrNull()?.toLong() ?: 0
        return HostStats(cpu.coerceIn(0.0, 1.0), memTotal - memAvail, memTotal, uptime, lines.getOrNull(5) ?: "")
    }
}
