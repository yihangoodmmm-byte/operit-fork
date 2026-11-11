package com.ai.assistance.operit.core.decision

/**
 * 模型式记忆合并:调用模型把较早的对话压缩成结构化摘要。
 * 移植自 PalmClaw 的 MemoryConsolidator,抽掉对具体存储(MessageRepository/MemoryStore)
 * 和 provider 的依赖,改为注入「生成函数」,保持纯逻辑、可单测。
 *
 * 与现有 ConversationCompactor 的关系:
 * - ConversationCompactor 是抽取式压缩(保留每轮首句 + 工具名),确定性、零往返延迟;
 * - 本模块是模型式合并(调模型写摘要),质量更高但占一次请求往返;
 * - 二者互补:预算紧张/低延迟场景用抽取式,追求质量时用模型式。
 *   本模块不替换 ConversationCompactor,而是作为可选增强。
 */
class MemoryConsolidator(
    private val generate: suspend (prompt: String) -> String,
) {

    /**
     * 把 [turns](形如 "[时间] ROLE: content")合并进 [currentMemory]。
     * 返回模型生成的合并摘要;失败时返回 null(调用方自行降级为抽取式或跳过)。
     */
    suspend fun consolidate(
        turns: List<String>,
        currentMemory: String,
    ): String? {
        if (turns.isEmpty()) return null
        val prompt = buildPrompt(turns, currentMemory)
        return try {
            val result = generate(prompt)
            result.trim().takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /**
         * 组装合并提示词:让模型输出「保留了什么、去掉了什么、新增了什么」
         * 的结构化摘要,便于后续检索与审计。
         */
        fun buildPrompt(turns: List<String>, currentMemory: String): String = buildString {
            appendLine("你是记忆合并助手。把下面这段对话压缩成结构化摘要,写入长期记忆。")
            appendLine()
            appendLine("输出格式(严格遵守,不要输出其它内容):")
            appendLine("## 摘要")
            appendLine("(一段话概括这段对话的要点)")
            appendLine("## 事实")
            appendLine("- (用户明确说过、且对未来有用的事实)")
            appendLine("## 偏好")
            appendLine("- (用户表达过的偏好、习惯、约束)")
            appendLine()
            appendLine("## 当前长期记忆")
            appendLine(if (currentMemory.isBlank()) "(空)" else currentMemory)
            appendLine()
            appendLine("## 待合并对话")
            append(turns.joinToString("\n"))
        }
    }
}
