package com.ai.assistance.operit.core.agent

import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 智能体团队编排层。
 *
 * 把 dsh-superagent 的编排语义（Ticket 状态机 + 观察模式 + 静默工作）精简移植到 App 内场景：
 * - 主智能体把一次复杂任务动态拆解成多个 [TeamTicket]，spawn 给 [SubagentRunner] 并行执行；
 * - 每个 Ticket 有显式状态机，写文件/跑命令的副作用默认「观察模式」先挂起，等人确认再放行；
 * - 结果回传后可被主智能体汇总，或直接呈现给用户。
 *
 * 与 dsh-superagent 的差异（有意为之）：
 * - 没有 Trigger/Channel/Repo 那套「来源」抽象（那是给 GitHub/飞书的），这里来源就是主会话；
 * - Assignment 合并进 Ticket（App 内一次拆解就是一次分配，不搞多次重新分配）；
 * - 授权简化：子智能体的能力由 [SubagentSpec.capabilities] 显式授予，观察模式由 [TeamTicket.observeOnly] 控制。
 */

/** 工作项状态（对齐 dsh-superagent 的 stage 语义，精简为 App 内六态）。 */
enum class TicketStatus {
    /** 已创建，排队等待执行。 */
    PENDING,

    /** 子智能体执行中。 */
    RUNNING,

    /** 执行完成，但存在副作用，等待人工确认后放行（观察模式）。 */
    AWAITING_REVIEW,

    /** 需要人工介入（阻塞）。 */
    BLOCKED,

    /** 完成。 */
    DONE,

    /** 失败。 */
    FAILED
}

/** 一个工作项：主智能体拆解出的单个子任务，交给一个子智能体执行。 */
data class TeamTicket(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val spec: SubagentSpec,
    val observeOnly: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    var status: TicketStatus = TicketStatus.PENDING
        internal set

    var result: SubagentResult? = null
        internal set

    var updatedAt: Long = createdAt
        internal set

    var error: String? = null
        internal set
}

/**
 * 一次智能体团队编排：主智能体拆解任务后产生的全部工作项及其运行时状态。
 *
 * 线程安全：状态通过 [MutableStateFlow] 暴露给 UI（团队看板），内部用锁保护状态迁移。
 */
class AgentTeam(
    val id: String = UUID.randomUUID().toString(),
    val goal: String
) {
    private val lock = Any()

    private val _tickets = MutableStateFlow<List<TeamTicket>>(emptyList())
    val tickets: StateFlow<List<TeamTicket>> = _tickets.asStateFlow()

    private val _status = MutableStateFlow(TeamStatus.IDLE)
    val status: StateFlow<TeamStatus> = _status.asStateFlow()

    private val ticketsById = ConcurrentHashMap<String, TeamTicket>()

    /** 添加一个工作项（由主智能体拆解产生）。 */
    fun addTicket(ticket: TeamTicket) {
        synchronized(lock) {
            ticketsById[ticket.id] = ticket
            publish()
        }
    }

    fun addTickets(tickets: List<TeamTicket>) {
        synchronized(lock) {
            tickets.forEach { ticketsById[it.id] = it }
            publish()
        }
    }

    /** 标记某工作项开始执行。 */
    fun markRunning(ticketId: String) {
        synchronized(lock) {
            ticketsById[ticketId]?.let {
                it.status = TicketStatus.RUNNING
                it.updatedAt = System.currentTimeMillis()
                _status.value = TeamStatus.RUNNING
            }
            publish()
        }
    }

    /** 记录执行结果；观察模式下挂起待确认。 */
    fun recordResult(ticketId: String, result: SubagentResult) {
        synchronized(lock) {
            ticketsById[ticketId]?.let { ticket ->
                ticket.result = result
                ticket.updatedAt = System.currentTimeMillis()
                ticket.status = when {
                    !result.success -> TicketStatus.FAILED
                    ticket.observeOnly -> TicketStatus.AWAITING_REVIEW
                    else -> TicketStatus.DONE
                }
                ticket.error = result.error
            }
            recomputeStatus()
            publish()
        }
    }

    /** 观察模式下人工确认放行：把 AWAITING_REVIEW 推进到 DONE。 */
    fun approve(ticketId: String) {
        synchronized(lock) {
            ticketsById[ticketId]?.let { ticket ->
                if (ticket.status == TicketStatus.AWAITING_REVIEW) {
                    ticket.status = TicketStatus.DONE
                    ticket.updatedAt = System.currentTimeMillis()
                }
            }
            recomputeStatus()
            publish()
        }
    }

    /** 拒绝观察模式的副作用（把挂起项标记为 BLOCKED 供重规划）。 */
    fun reject(ticketId: String) {
        synchronized(lock) {
            ticketsById[ticketId]?.let { ticket ->
                if (ticket.status == TicketStatus.AWAITING_REVIEW) {
                    ticket.status = TicketStatus.BLOCKED
                    ticket.updatedAt = System.currentTimeMillis()
                }
            }
            recomputeStatus()
            publish()
        }
    }

    fun markBlocked(ticketId: String, reason: String) {
        synchronized(lock) {
            ticketsById[ticketId]?.let { ticket ->
                ticket.status = TicketStatus.BLOCKED
                ticket.error = reason
                ticket.updatedAt = System.currentTimeMillis()
            }
            recomputeStatus()
            publish()
        }
    }

    fun getTicket(ticketId: String): TeamTicket? = ticketsById[ticketId]

    /** 是否有工作项等待人工确认（观察模式挂起）。 */
    fun hasAwaitingReview(): Boolean = ticketsById.values.any { it.status == TicketStatus.AWAITING_REVIEW }

    /** 汇总文本：供主智能体把团队结果合并进回复。 */
    fun summarize(): String {
        val list = ticketsById.values.toList()
        val done = list.filter { it.status == TicketStatus.DONE }
        val failed = list.filter { it.status == TicketStatus.FAILED }
        val waiting = list.filter { it.status == TicketStatus.AWAITING_REVIEW }
        val blocked = list.filter { it.status == TicketStatus.BLOCKED }
        return buildString {
            append("团队编排「").append(goal).append("」结果：")
            append("完成 ").append(done.size).append("，失败 ").append(failed.size)
            if (waiting.isNotEmpty()) append("，待确认 ").append(waiting.size)
            if (blocked.isNotEmpty()) append("，阻塞 ").append(blocked.size)
            append("\n")
            list.forEach { t ->
                append("\n- [").append(statusLabel(t.status)).append("] ").append(t.title)
                t.result?.let { r ->
                    if (r.content.isNotBlank()) append("：").append(r.content.take(200))
                }
                t.error?.let { append("（").append(it).append("）") }
            }
        }
    }

    private fun recomputeStatus() {
        val all = ticketsById.values.toList()
        _status.value = when {
            all.isEmpty() -> TeamStatus.IDLE
            all.any { it.status == TicketStatus.RUNNING || it.status == TicketStatus.PENDING } -> TeamStatus.RUNNING
            all.any { it.status == TicketStatus.AWAITING_REVIEW } -> TeamStatus.AWAITING_REVIEW
            all.any { it.status == TicketStatus.BLOCKED } -> TeamStatus.BLOCKED
            all.all { it.status == TicketStatus.FAILED } -> TeamStatus.FAILED
            all.all { it.status == TicketStatus.DONE } -> TeamStatus.DONE
            else -> TeamStatus.DONE
        }
    }

    private fun publish() {
        _tickets.value = ticketsById.values.toList()
    }

    private fun statusLabel(status: TicketStatus): String = when (status) {
        TicketStatus.PENDING -> "排队"
        TicketStatus.RUNNING -> "执行中"
        TicketStatus.AWAITING_REVIEW -> "待确认"
        TicketStatus.BLOCKED -> "阻塞"
        TicketStatus.DONE -> "完成"
        TicketStatus.FAILED -> "失败"
    }
}

/** 团队整体状态。 */
enum class TeamStatus {
    IDLE, RUNNING, AWAITING_REVIEW, BLOCKED, DONE, FAILED
}

/**
 * 团队编排器：把一次任务拆解成 [AgentTeam] 并交给 [SubagentRunner] 并行执行。
 *
 * 这是第 2 期的入口——主智能体（或 UI）调用 [orchestrate] 即得到一份可观察的团队执行。
 */
object AgentTeamOrchestrator {

    const val TAG = "AgentTeamOrchestrator"

    /**
     * 编排并执行一个团队任务。
     *
     * @param goal 团队目标（用于展示与汇总）。
     * @param specs 拆解出的子任务规格。
     * @param observeByDefault 是否默认观察模式（副作用挂起待确认）。
     * @param onTicketUpdate 每个工作项状态变化的回调（可选，供非 StateFlow 消费方使用）。
     */
    suspend fun orchestrate(
        context: android.content.Context,
        goal: String,
        specs: List<SubagentSpec>,
        observeByDefault: Boolean = false,
        onTicketUpdate: ((TeamTicket) -> Unit)? = null
    ): AgentTeam {
        require(specs.isNotEmpty()) { "团队任务至少要拆解出一个子任务" }

        val team = AgentTeam(goal = goal)
        val tickets = specs.map { spec ->
            TeamTicket(
                title = spec.task.take(60).ifBlank { "子任务" },
                description = spec.task,
                spec = spec,
                observeOnly = observeByDefault || spec.observeOnly
            )
        }
        team.addTickets(tickets)

        // 并行执行所有子任务；每个完成即回写状态。
        val results = SubagentRunner.runParallel(context, specs)
        results.results.zip(tickets).forEach { (result, ticket) ->
            team.recordResult(ticket.id, result)
            onTicketUpdate?.invoke(team.getTicket(ticket.id) ?: ticket)
        }

        AppLogger.d(TAG, "团队编排完成: goal=" + goal + ", 成功=" + results.successCount + ", 失败=" + results.failureCount)

        // 注册到全局注册表，供团队看板实时展示
        AgentTeamRegistry.register(team)
        return team
    }

    /**
     * 从工具调用传入的 JSON 解析子任务规格。
     *
     * 期望格式（工具层用 org.json 解析后传原始 JSON 字符串即可）：
     * [{"task":"...","capabilities":["read","write","command"],"files":["/path"]}, ...]
     *
     * 能力字符串映射：
     * "read" -> READ_ONLY, "tools" -> USE_TOOLS, "write" -> WRITE_FILE, "command" -> RUN_COMMAND。
     */
    fun parseSubagentSpecs(subtasksJson: String): List<SubagentSpec> {
        if (subtasksJson.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(subtasksJson)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val task = obj.optString("task").trim()
                if (task.isEmpty()) return@mapNotNull null
                val caps = obj.optJSONArray("capabilities")
                val capabilitySet = if (caps == null) emptySet() else (0 until caps.length()).mapNotNull { idx ->
                    capabilityFromString(caps.optString(idx))
                }.toSet()
                val files = obj.optJSONArray("files")?.let { fa ->
                    (0 until fa.length()).map { fa.optString(it) }
                } ?: emptyList()
                SubagentSpec(
                    task = task,
                    filePaths = files,
                    capabilities = capabilitySet
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun capabilityFromString(raw: String): SubagentCapability? = when (raw.trim().lowercase()) {
        "read", "read_only", "readonly" -> SubagentCapability.READ_ONLY
        "tools", "use_tools", "tool" -> SubagentCapability.USE_TOOLS
        "write", "write_file", "writefile" -> SubagentCapability.WRITE_FILE
        "command", "run_command", "shell", "run" -> SubagentCapability.RUN_COMMAND
        else -> null
    }

}
