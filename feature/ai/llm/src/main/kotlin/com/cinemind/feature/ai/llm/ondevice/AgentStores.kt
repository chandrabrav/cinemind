package com.cinemind.feature.ai.llm.ondevice

import android.content.Context
import com.cinemind.core.domain.agents.AgentId
import com.cinemind.core.domain.agents.ExecutionTrace
import com.cinemind.core.domain.agents.TraceEntry
import java.io.File

/** Developer switches: agents the decision engine must not use on this phone. */
object AgentSwitches {
    private fun prefs(c: Context) = c.getSharedPreferences("agents", Context.MODE_PRIVATE)

    fun disabled(c: Context): Set<AgentId> =
        prefs(c).getStringSet("off", emptySet()).orEmpty().mapNotNull { n -> AgentId.entries.firstOrNull { it.name == n } }.toSet()

    fun set(c: Context, agent: AgentId, enabled: Boolean) {
        val now = disabled(c).map { it.name }.toMutableSet()
        if (enabled) now -= agent.name else now += agent.name
        prefs(c).edit().putStringSet("off", now).apply()
    }
}

/**
 * The last [KEEP] execution traces (which agents ran, where, how long, what failed), for the
 * developer diagnostics. Text only, kept on the phone; no media or prompts are stored.
 */
object TraceStore {
    private const val KEEP = 20

    private fun file(c: Context) = File(c.filesDir, "ai-traces.tsv")

    fun save(c: Context, trace: ExecutionTrace) {
        val block = buildString {
            append("#\t").append(trace.id).append('\t').append(trace.startedMs).append('\t').append(trace.request.replace('\t', ' ').replace('\n', ' ').take(200)).append('\n')
            trace.planned.forEach { append("P\t").append(it.replace('\t', ' ').replace('\n', ' ')).append('\n') }
            trace.entries.forEach { append("E\t").append(it.encode()).append('\n') }
        }
        synchronized(this) {
            val all = (load(c) + listOf(block)).takeLast(KEEP)
            runCatching { file(c).writeText(all.joinToString("")) }
        }
    }

    /** Raw blocks, oldest first. */
    private fun load(c: Context): List<String> {
        val text = runCatching { file(c).readText() }.getOrDefault("")
        if (text.isBlank()) return emptyList()
        return text.split(Regex("(?m)^(?=#\t)")).filter { it.isNotBlank() }
    }

    /** Traces, newest first. */
    fun traces(c: Context): List<ExecutionTrace> = synchronized(this) { load(c) }.mapNotNull { block ->
        val lines = block.lines().filter { it.isNotBlank() }
        val head = lines.firstOrNull()?.split('\t') ?: return@mapNotNull null
        if (head.size < 4) return@mapNotNull null
        ExecutionTrace(
            id = head[1],
            request = head[3],
            startedMs = head[2].toLongOrNull() ?: 0,
            planned = lines.filter { it.startsWith("P\t") }.map { it.removePrefix("P\t") },
            entries = lines.filter { it.startsWith("E\t") }.mapNotNull { TraceEntry.decode(it.removePrefix("E\t")) },
        )
    }.reversed()

    fun clear(c: Context) = synchronized(this) { file(c).delete() }
}
