package com.ai.assistance.operit.core.agent

import android.content.Context
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import java.io.File

/**
 * 受限子智能体：单次问答 + 只读文件。
 *
 * 为什么不做完整工具循环：Operit 的 agent 循环深度绑定主会话的流式状态、UI 回调和生命周期，
 * 抽一个无 UI 的子循环属于重构而不是加功能。先做「读文件 + 给结论」这一种最常用形态，
 * 跑稳之后再考虑让它能改代码、跑命令。
 *
 * 边界：
 * - 只读调用方显式给出的文件，单文件超过 [MAX_BYTES_PER_FILE] 截断并标注；
 * - 总注入量受 [MAX_TOTAL_CHARS] 限制，避免把主会话预算挤爆；
 * - 子会话不带主会话历史，避免污染判断。
 */
object SubagentRunner {

    const val MAX_BYTES_PER_FILE: Int = 64 * 1024
    const val MAX_TOTAL_CHARS: Int = 200_000

    private const val SUBTASK_SYSTEM_PROMPT =
        "你是一个只读的子分析器。你拿不到工具，只能阅读用户提供的文件内容。" +
            "请只依据给定材料回答，不要编造未出现的内容；" +
            "结论要短，先给判断再给依据，不要复述原文。"

    /**
     * 跑一次子分析。
     *
     * @param filePaths 需要阅读的绝对路径；可以为空（纯文本任务）。
     */
    suspend fun run(
        context: Context,
        task: String,
        filePaths: List<String> = emptyList()
    ): String {
        require(task.isNotBlank()) { "子任务描述不能为空" }

        val materials = buildMaterials(filePaths)
        val userContent = buildString {
            append(task.trim())
            if (materials.isNotEmpty()) {
                append("\n\n--- 以下是可读材料 ---\n")
                append(materials)
            }
        }

        val service = EnhancedAIService.getInstance(context)
        val stream = service.sendMessage(
            EnhancedAIService.SendMessageOptions(
                functionType = com.ai.assistance.operit.data.model.FunctionType.CHAT,
                chatHistory = listOf(
                    PromptTurn(kind = PromptTurnKind.SYSTEM, content = SUBTASK_SYSTEM_PROMPT),
                    PromptTurn(kind = PromptTurnKind.USER, content = userContent)
                ),
                isSubTask = true,
                enableMemoryAutoUpdate = false,
                stream = true
            )
        )

        val out = StringBuilder()
        stream.collect { chunk -> out.append(chunk) }
        return out.toString().trim()
    }

    private fun buildMaterials(filePaths: List<String>): String {
        if (filePaths.isEmpty()) return ""
        val builder = StringBuilder()
        var total = 0
        for (path in filePaths) {
            if (total >= MAX_TOTAL_CHARS) {
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
                    val bytes = ByteArray(MAX_BYTES_PER_FILE)
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
            val truncated = file.length() > MAX_BYTES_PER_FILE
            if (truncated) builder.append("\n[该文件超过 ").append(MAX_BYTES_PER_FILE).append(" 字节，已截断]\n")
            total += text.length
        }
        return builder.toString()
    }
}
