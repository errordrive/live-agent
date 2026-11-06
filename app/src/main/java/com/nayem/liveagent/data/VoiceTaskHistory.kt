package com.nayem.liveagent.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory record of voice tasks started from the Live tab (v1.2.0).
 *
 * This is a RECORD LOG only — not a task engine: it holds no queue, no
 * parallel runner, no scheduling. Voice tasks are executed directly through
 * AgentOrchestrator.buildPlan/executePlan (the same call the Tasks tab
 * already makes); this object only remembers them so the Tasks tab can show
 * running / done / failed voice tasks and the user can cancel a running one
 * via its stored Job. Records are in-memory only (max 20, newest first) and
 * are lost when the app process dies; the audit log keeps the durable trail.
 */
enum class VoiceTaskStatus { RUNNING, DONE, FAILED, CANCELLED }

data class VoiceTaskRecord(
    val id: String,
    val goal: String,
    val status: VoiceTaskStatus,
    val result: String,
    val time: Long,
    val job: Job? = null
)

object VoiceTaskHistory {
    private val _tasks = MutableStateFlow<List<VoiceTaskRecord>>(emptyList())
    val tasks: StateFlow<List<VoiceTaskRecord>> = _tasks

    fun add(record: VoiceTaskRecord) {
        _tasks.value = (listOf(record) + _tasks.value).take(20)
    }

    fun update(id: String, status: VoiceTaskStatus, result: String) {
        _tasks.value = _tasks.value.map {
            if (it.id == id) it.copy(status = status, result = result) else it
        }
    }

    fun updateProgress(id: String, progress: String) {
        _tasks.value = _tasks.value.map {
            if (it.id == id && it.status == VoiceTaskStatus.RUNNING) it.copy(result = progress) else it
        }
    }

    /** Cancel a running voice task (cancels its coroutine Job). Returns false if not found. */
    fun cancel(id: String): Boolean {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return false
        if (task.status != VoiceTaskStatus.RUNNING) return false
        try {
            task.job?.cancel()
        } catch (_: Exception) {
        }
        update(id, VoiceTaskStatus.CANCELLED, "Cancelled by user")
        return true
    }
}
