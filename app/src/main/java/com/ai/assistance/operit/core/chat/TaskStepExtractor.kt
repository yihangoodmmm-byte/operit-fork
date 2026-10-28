package com.ai.assistance.operit.core.chat

/**
 * 任务预览：从一条 AI 消息里抽出本轮的工具调用步骤。
 *
 * 为什么不依赖运行时事件：Operit 的工具状态散在流式回调里，跨会话、跨重试都会丢；
 * 而消息正文里的调用与结果标记是**已经落库的事实**，重启后仍然准确。
 * 代价是「正在执行」的那一步只能推断为最后一个没有结果的调用。
 */
object TaskStepExtractor {

    enum class StepState { DONE, FAILED, RUNNING }

    data class TaskStep(
        val toolName: String,
        val state: StepState,
    )

    private val callRegex = Regex("<tool\\s+name=\"([^\"]+)\"")
    private val resultRegex = Regex("<[A-Za-z0-9_]+\\s+[^>]*name=\"([^\"]+)\"[^>]*status=\"(success|error)\"")
    private val resultStatusRegex = Regex("status=\"(success|error)\"")

    /** 最多保留的步骤数：预览条只是索引，不是日志。 */
    private const val MAX_STEPS = 12

    fun extractFrom(content: String): List<TaskStep> {
        if (content.isBlank()) return emptyList()

        val results = HashMap<String, StepState>()
        resultRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val status = match.groupValues[2]
            results[name] = if (status == "error") StepState.FAILED else StepState.DONE
        }

        val calls = callRegex.findAll(content).map { it.groupValues[1] }.toList()
        if (calls.isEmpty()) return emptyList()

        // 同名工具多次调用时按出现顺序消费结果，避免第二次调用直接算「完成」。
        val consumed = HashMap<String, Int>()
        val steps = calls.map { name ->
            val index = consumed.getOrDefault(name, 0)
            consumed[name] = index + 1
            val state = results[name] ?: StepState.RUNNING
            TaskStep(toolName = name, state = state)
        }
        return steps.takeLast(MAX_STEPS)
    }
}
