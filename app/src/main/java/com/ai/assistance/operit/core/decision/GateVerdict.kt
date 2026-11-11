package com.ai.assistance.operit.core.decision

/**
 * 操作安全闸对一次工具调用的裁决。
 *
 * 移植自 mobile-agent 的 DecisionGate(allow/confirm/block 三态语义),
 * 适配 Operit 现有的 AIToolHook + ToolPermissionSystem。
 *
 * 与 ToolPermissionSystem 的关系:
 * - ToolPermissionSystem 是「用户静态配置」:ALLOW/ASK/FORBID,一次性设定后长期生效;
 * - DecisionGate 是「每次执行的语义裁决」:由模型看当前参数与用户请求判断风险,
 *   即使工具在权限上设为 ALLOW,若本次参数明显高危(如发短信、转账),仍会拦到 confirm/block。
 * - 两者是「先静态后动态」的串联:静态 FORBID 直接挡;静态 ALLOW 仍过语义闸兜底;
 *   静态 ASK 则无论如何都会询问。
 */
sealed interface GateVerdict {
    /** 普通操作,直接执行,不打扰用户。 */
    data object Allow : GateVerdict

    /** 有外部副作用或不易撤销,需要用户确认;进入与手动审批相同的确认通道。 */
    data class Confirm(val reason: String) : GateVerdict

    /** 明确危险不可逆,拒绝执行并把原因回传给模型,让它换路子。 */
    data class Block(val reason: String) : GateVerdict
}

/**
 * 语义裁决的输入。只带最小判定上下文,不含完整历史与截图,
 * 保证隐私边界(不把敏感数据送出去裁决)。
 */
data class GateRequest(
    val userRequest: String,
    val toolId: String,
    val toolTitle: String,
    val argumentsSummary: String,
    val runId: String? = null,
    val toolCallId: String? = null,
)

/**
 * 有外部副作用或破坏性的动作需要过闸;读操作与本地写直接放行。
 * 对应 mobile-agent 的 ToolSideEffect 语义,但 Operit 现有工具没有 sideEffect 标注,
 * 因此这里提供显式判定入口,由调用方(工具注册处)决定哪些工具过闸。
 */
enum class ToolSideEffect { READ, LOCAL_WRITE, EXTERNAL_WRITE, DESTRUCTIVE }

/** 只有会产生外部副作用或破坏性的动作需要过闸;读操作直接放行。 */
val GATED_SIDE_EFFECTS: Set<ToolSideEffect> =
    setOf(ToolSideEffect.EXTERNAL_WRITE, ToolSideEffect.DESTRUCTIVE)

/**
 * 操作安全闸接口。后端可替换:
 * - 默认 [LlmDecisionGate] 用当前对话模型回答一道有界选择题;
 * - 也可替换为专用决策模型服务,或纯本地规则裁决。
 */
interface DecisionGate {
    suspend fun gate(request: GateRequest): GateVerdict
}
