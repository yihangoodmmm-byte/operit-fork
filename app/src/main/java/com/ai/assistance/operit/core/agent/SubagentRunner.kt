package com.ai.assistance.operit.core.agent

import android.content.Context
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.FunctionType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * 子智能体执行器。
 *
 * 演进：从「受限只读子分析器」升级为「能力感知的子智能体」。
 *
 * - 只读（默认）：[run] / [SubagentSpec] 无 capabilities，纯分析，不写不跑命令。
 * - 可工具 / 可写 / 可跑命令：通过 [SubagentSpec.capabilities] 显式授权，
 *   由 [EnhancedAIService.sendMessage] 内建工具循环执行（isSubTask=true 只关 UI 副作用，不关工具）。
 * - 并行：[runParallel] 用 coroutineScope + async 并发拉起多个子任务，awaitAll 汇聚。
 *
 * 授权语义（对齐 dsh-superagent）：
 * - 最小权限默认只读；写文件与跑命令是两个独立开关，调用方必须显式授予。
 * - [SubagentSpec.observeOnly] 观察模式：提示词要求「只给计划与结论，不落副作用」。
 */
object SubagentRunner {

    const val MAX_BYTES_PER_FILE: Int = 64 * 1024
    const val MAX_TOTAL_CHARS: Int = 200_000

    private const val SUBTASK_SYSTEM_PROMPT_READONLY =
        "你是一个只读的子分析器。你拿不到工具，只能阅读用户提供的文件内容。" +
            "请只依据给定材料回答，不要编造未出现的内容；" +
            "结论要短，先给判断再给依据，不要复述原文。"

    private const val SUBTASK_SYSTEM_PROMPT_AGENT =
        "你是一个执行子任务的智能体，独立于主会话工作。\n" +
            "你可以调用工具完成交给你的任务（读文件、搜索、写文件、跑命令等，以实际可用工具为准）。\n" +
            "规则：\n" +
            "- 先做后说：优先直接调用工具完成任务，不要只描述计划。\n" +
            "- 不臆造路径：写文件或跑命令前，先用工具确认目标路径存在。\n" +
            "- 结论收敛：完成后用简短文字总结你做了什么、结果如何。\n" +
            "- 只做分配给你的这件事，不越界去改无关文件。"

    private const val SUBTASK_SYSTEM_PROMPT_OBSERVE =
        "你是一个执行子任务的智能体，但当前处于观察模式。\n" +
            "你可以阅读文件、搜索、分析，但**不得写文件、不得执行任何有副作用的命令**。\n" +
            "请输出：\n" +
            "1. 你的执行计划（打算做什么）；\n" +
            "2. 基于只读分析得出的结论或预期结果。\n" +
            "不要真正落任何副作用。"

    /**
     * 只读子分析（向后兼容入口）。等价于 [runSpec] 且 capabilities 为空。
     */
    suspend fun run(
        context: Context,
        task: String,
        filePaths: List<String> = emptyList()
    ): String {
        return runSpec(
            context,
            SubagentSpec(task = task, filePaths = filePaths)
        ).content
    }

    /**
     * 能力感知地跑一次子任务。
     */
    suspend fun runSpec(
        context: Context,
        spec: SubagentSpec
    ): SubagentResult {
        require(spec.task.isNotBlank()) { "子任务描述不能为空" }

        val materials = buildMaterials(spec.filePaths, spec.maxBytesPerFile, spec.maxTotalChars)
        val userContent = buildString {
            append(spec.task.trim())
            if (materials.isNotEmpty()) {
                append("\n\n--- 以下是可读材料 ---\n")
                append(materials)
            }
        }

        val systemPrompt = when {
            spec.observeOnly -> SUBTASK_SYSTEM_PROMPT_OBSERVE
            spec.requiresToolLoop -> SUBTASK_SYSTEM_PROMPT_AGENT
            else -> SUBTASK_SYSTEM_PROMPT_READONLY
        }

        val service = EnhancedAIService.getInstance(context)
        return runCatching {
            val stream = service.sendMessage(
                EnhancedAIService.SendMessageOptions(
                    functionType = FunctionType.CHAT,
                    chatHistory = listOf(
                        PromptTurn(kind = PromptTurnKind.SYSTEM, content = systemPrompt),
                        PromptTurn(kind = PromptTurnKind.USER, content = userContent)
                    ),
                    isSubTask = true,
                    enableMemoryAutoUpdate = false,
                    stream = true,
                    workspacePath = spec.workspacePath,
                    chatModelConfigIdOverride = spec.modelConfigIdOverride,
                    chatModelIndexOverride = spec.modelIndexOverride
                )
            )
            val out = StringBuilder()
            stream.collect { chunk -> out.append(chunk) }
            SubagentResult(
                spec = spec,
                content = out.toString().trim(),
                success = true
            )
        }.getOrElse { error ->
            SubagentResult(
                spec = spec,
                content = "",
                success = false,
                error = error.message ?: "子任务执行失败"
            )
        }
    }

    /**
     * 并行编排多个子任务，awaitAll 汇聚结果。
     *
     * 每个子任务相互独立（不共享历史、不共享状态），天然适合并发。
     * 调用方应控制 [specs] 的数量与总量，避免一次性挤爆模型并发预算。
     */
    suspend fun runParallel(
        context: Context,
        specs: List<SubagentSpec>
    ): SubagentBatchResult {
        require(specs.isNotEmpty()) { "子任务列表不能为空" }
        return coroutineScope {
            val deferred = specs.map { spec ->
                async { runSpec(context, spec) }
            }
            SubagentBatchResult(results = deferred.awaitAll())
        }
    }

    private fun buildMaterials(
        filePaths: List<String>,
        maxBytesPerFile: Int,
        maxTotalChars: Int
    ): String {
        if (filePaths.isEmpty()) return ""
        val builder = StringBuilder()
        var total = 0
        for (path in filePaths) {
            if (total >= maxTotalChars) {
                builder.append("\n[已达材料总量上限，其余文件未读取]\n")
                break
            }
            val file = File(path)
            if (!file.isFile) {
                builder.append("\n[文件不存在: ").append(path).append("]\n")
                continue
            }
            val text = runCatching {
                file.inputStream().use { input ->
                    val bytes = ByteArray(maxBytesPerFile)
                    val read = input.read(bytes)
                    if (read <= 0) "" else String(bytes, 0, read, Charsets.UTF_8)
                }
            }.getOrElse { error ->
                builder.append("\n[读取失败: ").append(path).append(" - ").append(error.message).append("]\n")
                ""
            }
            if (text.isEmpty()) continue
            builder.append("\n=== ").append(path).append(" ===\n")
            builder.append(text)
            val truncated = file.length() > maxBytesPerFile
            if (truncated) builder.append("\n[该文件超过 ").append(maxBytesPerFile).append(" 字节，已截断]\n")
            total += text.length
        }
        return builder.toString()
    }
}
