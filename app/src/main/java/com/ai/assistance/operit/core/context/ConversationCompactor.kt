package com.ai.assistance.operit.core.context

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.prompt.TokenEstimator

/**
 * 上下文预算与压缩。
 *
 * 为什么要有预算：模型上下文窗口是硬上限，超了要么请求失败、要么供应商静默截断。
 * 旧实现靠「用户自己开总结」和写死的条数，跟具体模型无关，换个窗口大的模型也不会放宽。
 *
 * 为什么压缩不调模型：摘要请求本身要占一次往返，还会让首字延迟翻倍；
 * 这里先做确定性的抽取式压缩（保留每轮首句 + 工具名），把「丢掉一半信息」
 * 换成「保留骨架」。等这块稳定了再考虑用模型写摘要。
 */
object ConversationCompactor {

    /** 只用窗口的 70%，给系统提示词、工具定义和输出留余量。 */
    const val WINDOW_BUDGET_RATIO: Double = 0.7

    /** 压缩时从尾部保留的轮数：最近这些轮不参与压缩。 */
    const val RECENT_TURNS_KEPT: Int = 12

    /** 摘要里每轮最多保留的字符数。 */
    private const val SUMMARY_CHARS_PER_TURN: Int = 120

    data class CompactResult(
        val turns: List<PromptTurn>,
        val compacted: Boolean,
        val removedTurns: Int,
        val tokensBefore: Int,
        val tokensAfter: Int,
        val summary: String,
    )

    fun budgetTokens(modelWindow: Int): Int {
        if (modelWindow <= 0) return 0
        return (modelWindow * WINDOW_BUDGET_RATIO).toInt()
    }

    fun estimateTurns(turns: List<PromptTurn>): Int =
        turns.sumOf { TokenEstimator.estimate(it.content) }

    fun needsCompaction(turns: List<PromptTurn>, modelWindow: Int): Boolean {
        val budget = budgetTokens(modelWindow)
        if (budget <= 0) return false
        return estimateTurns(turns) > budget
    }

    /**
     * 压缩较早的轮次。
     *
     * 保留规则：系统轮（提示词）一律不动、最近 [RECENT_TURNS_KEPT] 轮不动，
     * 中间部分折成一条 SYSTEM 轮插在最前面。这样角色交替不会被破坏，
     * 供应商侧也不会因为出现两条连续 user 而报错。
     */
    fun compact(turns: List<PromptTurn>, modelWindow: Int): CompactResult {
        val before = estimateTurns(turns)
        if (!needsCompaction(turns, modelWindow) || turns.size <= RECENT_TURNS_KEPT + 1) {
            return CompactResult(turns, false, 0, before, before, "")
        }

        val systemTurns = turns.filter { it.kind == PromptTurnKind.SYSTEM }
        val nonSystem = turns.filter { it.kind != PromptTurnKind.SYSTEM }
        if (nonSystem.size <= RECENT_TURNS_KEPT) {
            return CompactResult(turns, false, 0, before, before, "")
        }

        val cut = nonSystem.size - RECENT_TURNS_KEPT
        val older = nonSystem.subList(0, cut)
        val recent = nonSystem.subList(cut, nonSystem.size)

        val summary = buildSummary(older)
        val summaryTurn = PromptTurn(
            kind = PromptTurnKind.SYSTEM,
            content = summary,
            metadata = mapOf("compactedTurns" to older.size)
        )

        val rebuilt = systemTurns + summaryTurn + recent
        val after = estimateTurns(rebuilt)
        return CompactResult(
            turns = rebuilt,
            compacted = true,
            removedTurns = older.size,
            tokensBefore = before,
            tokensAfter = after,
            summary = summary
        )
    }

    private fun buildSummary(older: List<PromptTurn>): String = buildString {
        append("[较早的 ")
        append(older.size)
        append(" 轮对话已压缩，以下是骨架]\n")
        older.forEach { turn ->
            val label = when (turn.kind) {
                PromptTurnKind.USER -> "用户"
                PromptTurnKind.ASSISTANT -> "助手"
                PromptTurnKind.TOOL_CALL, PromptTurnKind.TOOL_RESULT -> "工具 " + (turn.toolName ?: "")
                PromptTurnKind.SYSTEM, PromptTurnKind.SUMMARY -> "系统"
            }
            val body = turn.content.replace(Regex("\\s+"), " ").trim()
            if (body.isEmpty()) return@forEach
            append("- ")
            append(label)
            append("：")
            append(if (body.length > SUMMARY_CHARS_PER_TURN) body.substring(0, SUMMARY_CHARS_PER_TURN) else body)
            append("\n")
        }
        append("[压缩结束]")
    }
}
