package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind

/**
 * 悬空工具调用修复。
 *
 * 问题：进程被杀、用户中断、或者工具结果落库失败时，历史里会留下
 * 「有 TOOL_CALL 但没有对应 TOOL_RESULT」的断口。直接把这种历史发给模型，
 * 有些供应商会报错，有些会让模型以为工具还在跑而空等。
 *
 * 为什么不做自动重放：重放写操作（改文件、发请求、装应用）会造成真实副作用，
 * 而进程中断时我们无法确认上一次到底执行到哪一步。所以这里只补一条
 * 「结果未知、先核验」的占位，把决定权交回给模型。
 */
object DanglingToolCallRepair {

    private const val PLACEHOLDER =
        "[工具调用未返回结果] 该调用可能因为进程中断而丢失。" +
            "如果这是写操作，请先核验实际状态再决定是否重试；不要直接假定它没有执行。"

    fun repair(turns: List<PromptTurn>): List<PromptTurn> {
        if (turns.isEmpty()) return turns
        val out = ArrayList<PromptTurn>(turns.size + 2)
        var pending: PromptTurn? = null

        for (turn in turns) {
            // 上一条 TOOL_CALL 还没等到结果，却来了别的类型 —— 先补占位再继续
            if (pending != null && turn.kind != PromptTurnKind.TOOL_RESULT) {
                out.add(placeholder(pending))
                pending = null
            }
            when (turn.kind) {
                PromptTurnKind.TOOL_CALL -> {
                    if (pending != null) out.add(placeholder(pending))
                    pending = turn
                }
                PromptTurnKind.TOOL_RESULT -> pending = null
                else -> Unit
            }
            out.add(turn)
        }
        pending?.let { out.add(placeholder(it)) }
        return out
    }

    private fun placeholder(call: PromptTurn): PromptTurn = PromptTurn(
        kind = PromptTurnKind.TOOL_RESULT,
        content = PLACEHOLDER,
        toolName = call.toolName,
        metadata = mapOf("danglingRepaired" to true)
    )
}
