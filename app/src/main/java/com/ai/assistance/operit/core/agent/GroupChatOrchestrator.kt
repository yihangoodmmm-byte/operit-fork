package com.ai.assistance.operit.core.agent

import android.content.Context
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.GroupChatMember
import com.ai.assistance.operit.util.AppLogger

/**
 * 群聊编排器:把一次群聊请求拆成 N 个并行子任务,交给 [AgentTeamOrchestrator] 执行。
 *
 * 与「角色群组编排(MessageCoordinationDelegate.orchestrateGroupConversation)」的区别:
 * - 角色群组:多个不同角色卡**轮流发言**,成员有独立人格,串行;
 * - 群聊编排:多个**同主提示词的子智能体**并行执行,成员只是执行单元,由主模型拆任务后并行跑。
 *
 * 流程(方案 B:单模型编排):
 * 1. [decompose] 让主模型把用户要求拆成 N 个独立子任务(输出 JSON 数组);
 * 2. [AgentTeamOrchestrator.orchestrate] 并行执行 N 个子任务,注册到团队看板;
 * 3. [summarize] 可选:让主模型汇总各子任务结果,产出最终答复。
 */
object GroupChatOrchestrator {

    private const val TAG = "GroupChatOrchestrator"

    /** 拆任务提示词:让主模型把用户要求拆成 N 个可并行的子任务,只输出 JSON。 */
    private fun buildDecomposePrompt(userRequest: String, memberNames: List<String>): String = buildString {
        append("你是团队任务的拆解者。把用户的要求拆成 ")
        append(memberNames.size)
        append(" 个可以并行执行的独立子任务,分配给以下成员:\n")
        memberNames.forEachIndexed { i, name ->
            append("${i + 1}. $name\n")
        }
        append("\n要求:\n")
        append("- 每个子任务要独立、无依赖、可并行,且描述具体可执行;\n")
        append("- 子任务数量必须等于成员数(").append(memberNames.size).append(" 个),不要多也不要少;\n")
        append("- 只输出一个 JSON 数组,不要输出任何其它内容或解释。\n")
        append("格式: [{\"task\":\"...\"}, ...]\n\n")
        append("用户要求:\n").append(userRequest)
    }

    /** 让主模型拆解任务,返回子任务描述列表(失败时降级为「整段要求作为一个子任务」)。 */
    suspend fun decompose(
        context: Context,
        userRequest: String,
        memberNames: List<String>,
    ): List<String> {
        if (memberNames.isEmpty()) return emptyList()
        if (memberNames.size == 1) return listOf(userRequest)

        val service = EnhancedAIService.getInstance(context)
        val prompt = buildDecomposePrompt(userRequest, memberNames)
        val raw = runCatching {
            val stream = service.sendMessage(
                EnhancedAIService.SendMessageOptions(
                    functionType = FunctionType.CHAT,
                    chatHistory = listOf(
                        PromptTurn(kind = PromptTurnKind.SYSTEM, content = "你是团队任务拆解器。"),
                        PromptTurn(kind = PromptTurnKind.USER, content = prompt),
                    ),
                    isSubTask = true,
                    enableMemoryAutoUpdate = false,
                    stream = true,
                )
            )
            val out = StringBuilder()
            stream.collect { chunk -> out.append(chunk) }
            out.toString().trim()
        }.getOrNull()

        if (raw.isNullOrBlank()) {
            AppLogger.w(TAG, "任务拆解失败,降级为单子任务")
            return listOf(userRequest)
        }

        val tasks = parseDecomposedTasks(raw)
        if (tasks.isEmpty()) return listOf(userRequest)
        return tasks.take(memberNames.size)
    }

    /** 从模型输出里提取 JSON 数组,解析为子任务列表。 */
    fun parseDecomposedTasks(raw: String): List<String> {
        // 提取第一个 JSON 数组
        val arrayRegex = Regex("\\[.*\\]", setOf(RegexOption.DOT_MATCHES_ALL))
        val jsonArray = arrayRegex.find(raw)?.value ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(jsonArray)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val task = obj.optString("task").trim()
                if (task.isEmpty()) null else task
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 编排一次群聊:拆任务 → 并行执行 → 注册看板 → 返回汇总。
     *
     * @return 汇总结果(子任务结果拼接),以及团队(已注册到看板)。
     */
    suspend fun orchestrate(
        context: Context,
        goal: String,
        members: List<GroupChatMember>,
    ): GroupChatOrchestrationResult {
        if (members.isEmpty()) {
            return GroupChatOrchestrationResult(team = null, summary = "群聊没有成员,请先召唤成员。")
        }

        val memberNames = members.map { it.name }
        val tasks = decompose(context, goal, memberNames)

        // 每个任务对应一个成员,用「成员名: 任务」作为子任务描述,便于看板归属
        val specs = tasks.mapIndexed { i, task ->
            val memberName = memberNames.getOrElse(i) { "成员${i + 1}" }
            SubagentSpec(
                task = task,
                capabilities = setOf(SubagentCapability.USE_TOOLS, SubagentCapability.WRITE_FILE),
            )
        }

        val team = AgentTeamOrchestrator.orchestrate(
            context = context,
            goal = goal,
            specs = specs,
            observeByDefault = false,
        )

        val summary = buildString {
            append("群聊完成,共 ")
            append(specs.size)
            append(" 个成员参与:\n")
            specs.forEachIndexed { i, spec ->
                append("\n【").append(memberNames.getOrElse(i) { "成员${i + 1}" }).append("】")
                append(spec.task.take(80))
            }
        }
        return GroupChatOrchestrationResult(team = team, summary = summary)
    }
}

/** 群聊编排结果。 */
data class GroupChatOrchestrationResult(
    val team: AgentTeam?,
    val summary: String,
)
