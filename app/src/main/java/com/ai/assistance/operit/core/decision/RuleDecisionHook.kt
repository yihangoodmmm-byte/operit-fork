package com.ai.assistance.operit.core.decision

import com.ai.assistance.operit.core.tools.AIToolHook
import com.ai.assistance.operit.core.tools.AIToolHookDecision
import com.ai.assistance.operit.data.model.AITool

/**
 * 把 [RuleDecisionGate] 接到 Operit 现有工具执行链路的高危拦截 Hook。
 *
 * 接线点:通过 [com.ai.assistance.operit.core.tools.AIToolHandler.addToolHook]
 * 注册,在 [AIToolHook.onToolCallIntercept] 里同步调用 RuleDecisionGate.evaluateSync。
 *
 * 为什么用 Hook 而不是改 executeInvocations:
 * - AIToolHook 机制已存在且零侵入,不用动 EnhancedAIService/ToolExecutionManager;
 * - RuleDecisionGate 是纯本地规则,同步、零延迟,符合 Hook 回调「必须轻量」的约束;
 * - 高危工具(转账/密码/格式化等)在权限弹窗之前就被拦下,避免「先弹窗再拒绝」的浪费。
 *
 * 注意:只把 Block 映射为拦截;Confirm 不在 Hook 层处理(它需要用户确认通道,
 * 交给已有的 ToolPermissionSystem 的 ASK 弹窗),避免重复询问。
 */
class RuleDecisionHook : AIToolHook {
    override fun onToolCallIntercept(tool: AITool): AIToolHookDecision {
        val verdict = RuleDecisionGate.evaluateSync(
            GateRequest(
                userRequest = "",
                toolId = tool.name,
                toolTitle = tool.name,
                argumentsSummary = "",
            )
        )
        return when (verdict) {
            is GateVerdict.Block -> AIToolHookDecision.Block(verdict.reason)
            else -> AIToolHookDecision.Allow
        }
    }
}
