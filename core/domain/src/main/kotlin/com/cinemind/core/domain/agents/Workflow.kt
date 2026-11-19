package com.cinemind.core.domain.agents

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Lifecycle of one delegated task. */
enum class TaskStatus { QUEUED, RUNNING, SUCCEEDED, SKIPPED, FAILED, CANCELLED, TIMED_OUT }

/**
 * One unit of delegated work (inspired by agent-harness designs: a task tree with typed
 * contracts). [input] / [output] name the typed data it consumes and produces.
 */
data class AgentTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val parentId: String?,
    val agent: AgentId,
    val priority: Int,
    val input: Set<DataKind>,
    val output: DataKind,
    val timeoutMs: Long,
    val step: PlannedStep,
)

/** What an agent reports back. [addAgents] lets a result pull in more work (dynamic execution). */
sealed interface AgentOutcome {
    data class Done(val note: String = "", val addAgents: Set<AgentId> = emptySet()) : AgentOutcome
    /** The agent decided it isn't needed (conditional execution), e.g. no speech found. */
    data class Skip(val reason: String) : AgentOutcome
    data class Fail(val error: String) : AgentOutcome
}

/** One line of the execution trace (shown in the developer diagnostics). */
data class TraceEntry(
    val taskId: String,
    val parentId: String?,
    val agent: AgentId,
    val runtime: RuntimeKind,
    val modelId: String?,
    val status: TaskStatus,
    val startedMs: Long,
    val durationMs: Long,
    val attempt: Int,
    val note: String = "",
    val memoryMb: Long = -1,
) {
    fun encode(): String = listOf(taskId, parentId.orEmpty(), agent.name, runtime.name, modelId.orEmpty(), status.name, startedMs, durationMs, attempt, note.replace('\t', ' ').replace('\n', ' '), memoryMb).joinToString("\t")

    companion object {
        fun decode(s: String): TraceEntry? = runCatching {
            val p = s.split('\t')
            TraceEntry(p[0], p[1].ifBlank { null }, AgentId.valueOf(p[2]), RuntimeKind.valueOf(p[3]), p[4].ifBlank { null }, TaskStatus.valueOf(p[5]), p[6].toLong(), p[7].toLong(), p[8].toInt(), p[9], p.getOrNull(10)?.toLongOrNull() ?: -1)
        }.getOrNull()
    }
}

/** The record of one request: what the engine planned and what actually happened. */
data class ExecutionTrace(
    val id: String = UUID.randomUUID().toString().take(8),
    val request: String,
    val startedMs: Long,
    val planned: List<String>,
    val entries: List<TraceEntry>,
) {
    val succeeded: Boolean get() = entries.none { it.status == TaskStatus.FAILED || it.status == TaskStatus.TIMED_OUT }
}

/**
 * Runs an [ExecutionPlan]: stage by stage; inside a stage, independent steps run in parallel
 * (at most one on-device model at a time, see [ExecutionPlan.parallelGroups]). Each task has a
 * timeout; a failure is retried on the step's next runtime (the engine's fallbacks); an agent
 * may skip itself (conditional) or add agents (dynamic: they run in a following stage). Every
 * attempt is traced. Pure coroutine code: the agents themselves are passed in.
 */
class WorkflowRunner(
    private val run: suspend (AgentTask) -> AgentOutcome,
    private val now: () -> Long = System::currentTimeMillis,
    private val memoryMb: () -> Long = { -1 },
    private val timeoutFor: (AgentId) -> Long = { DEFAULT_TIMEOUT_MS },
) {
    suspend fun execute(plan: ExecutionPlan, parentId: String? = null, extraPlanner: (Set<AgentId>) -> List<PlannedStep> = { emptyList() }): List<TraceEntry> {
        val trace = mutableListOf<TraceEntry>()
        val queue = plan.stages.map { it.toMutableList() }.toMutableList()
        val done = mutableSetOf<AgentId>()
        var i = 0
        while (i < queue.size) {
            val stage = queue[i]
            val added = mutableSetOf<AgentId>()
            for (group in plan.parallelGroups(stage)) {
                val results = coroutineScope {
                    group.map { step -> async { runWithRetries(step, parentId) } }.awaitAll()
                }
                results.forEach { (entries, extra) ->
                    trace += entries
                    entries.lastOrNull()?.let { if (it.status == TaskStatus.SUCCEEDED || it.status == TaskStatus.SKIPPED) done += it.agent }
                    added += extra
                }
            }
            // Dynamic execution: agents asked for by results run in the next stage (once).
            val newSteps = extraPlanner(added - done - queue.flatten().map { it.agent }.toSet())
            if (newSteps.isNotEmpty()) {
                if (i + 1 < queue.size) queue[i + 1].addAll(newSteps) else queue += newSteps.toMutableList()
            }
            i++
        }
        return trace
    }

    private suspend fun runWithRetries(first: PlannedStep, parentId: String?): Pair<List<TraceEntry>, Set<AgentId>> {
        val entries = mutableListOf<TraceEntry>()
        var step: PlannedStep? = first
        var attempt = 1
        while (step != null) {
            val spec = AgentRegistry.byId(step.agent)
            val task = AgentTask(parentId = parentId, agent = step.agent, priority = 100 - step.stage, input = spec.requires + spec.uses, output = spec.produces, timeoutMs = timeoutFor(step.agent), step = step)
            val started = now()
            val outcome = withTimeoutOrNull(task.timeoutMs) { run(task) }
            val duration = now() - started
            val (status, note) = when (outcome) {
                null -> TaskStatus.TIMED_OUT to "no answer within ${task.timeoutMs / 1000} s"
                is AgentOutcome.Done -> TaskStatus.SUCCEEDED to outcome.note
                is AgentOutcome.Skip -> TaskStatus.SKIPPED to outcome.reason
                is AgentOutcome.Fail -> TaskStatus.FAILED to outcome.error
            }
            entries += TraceEntry(task.id, parentId, step.agent, step.runtime, step.modelId, status, started, duration, attempt, note, memoryMb())
            if (status == TaskStatus.SUCCEEDED || status == TaskStatus.SKIPPED) {
                return entries to ((outcome as? AgentOutcome.Done)?.addAgents ?: emptySet())
            }
            // Reassign to the next option (cloud → phone → rules), if any.
            step = DecisionEngine.recover(step)
            attempt++
        }
        return entries to emptySet()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
