package com.ai.assistance.operit.core.decision

import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/**
 * 用当前对话模型实现的决策闸。不生成自由文本,只解析一行 JSON 裁决,
 * 超时或解析失败时降级为 Confirm(交还给用户判断),保证 fail-safe。
 *
 * 移植自 mobile-agent 的 LlmDecisionGate,但把「模型调用」抽象成注入的裁决函数:
 * - Operit 的 AIService.sendMessage 签名很重(PromptTurn/ModelParameter/Stream),
 *   若在这里硬绑会引入大量耦合;
 * - 改为注入 suspend (prompt: String) -> String,由上层(AIToolHandler 或桥接层)
 *   用现有 provider 实现,DecisionGate 本身保持纯逻辑、可单测。
 *
 * fail-safe 语义:任何异常(除取消外)都降级为 Confirm,绝不静默放行,
 * 也不把异常当成 Block(避免误杀普通操作)。
 */
class LlmDecisionGate(
    private val decide: suspend (prompt: String) -> String,
    private val timeoutMs: Long = 20_000,
) : DecisionGate {

    override suspend fun gate(request: GateRequest): GateVerdict = runCatching {
        withTimeout(timeoutMs) { runDecision(request) }
    }.getOrElse {
        // 取消是协程生命周期信号,必须向上传播而不是降级成 Confirm。
        if (it is CancellationException) throw it
        AppLogger.w(TAG, "gate check fell back to confirm: ${it.message}")
        GateVerdict.Confirm("安全检查未完成,需要你确认")
    }

    private suspend fun runDecision(request: GateRequest): GateVerdict {
        val prompt = buildPrompt(request)
        val text = decide(prompt)
        return parse(text)
    }

    companion object {
        private const val TAG = "DecisionGate"

        private val JSON_PATTERN = Regex("\\{[^{}]*\\}")
        /** 非 JSON 输出兜底:`decision: allow`(可不带引号)或行首裸裁决词。 */
        private val LOOSE_DECISION = Regex("(?i)(?:decision\\s*[:=]\\s*\"?|^\\s*)(allow|confirm|block)\\b")

        fun buildPrompt(request: GateRequest): String =
            """你是手机操作安全裁决器。判断下面这个动作是否可以直接执行,只输出一行 JSON,不要输出任何其它内容:
{"decision":"allow|confirm|block","reason":"不超过40字的原因"}
裁决规则:
- allow:普通操作,如打开应用、点击浏览、滚动、输入普通文本、等待。
- confirm:有外部影响或不易撤销,如发送消息或评论、发布内容、修改系统设置、删除或覆盖数据、提交含个人信息的表单、登录注册。
- block:高风险不可逆操作,如支付转账充值、填写验证码或密码、账号安全操作、解除授权、格式化或清空全部数据。
拿不准时输出 confirm,不要输出 block 之外的长解释。
用户请求:${request.userRequest.take(300)}
待执行动作:${request.toolTitle}
参数摘要:${request.argumentsSummary.take(500)}"""

        fun parse(text: String): GateVerdict {
            JSON_PATTERN.find(text)?.value?.let { json ->
                val reason = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"")
                    .find(json)?.groupValues?.get(1)?.take(120).orEmpty()
                return when (Regex("\"decision\"\\s*:\\s*\"(\\w+)\"").find(json)?.groupValues?.get(1)?.lowercase()) {
                    "allow" -> GateVerdict.Allow
                    "block" -> GateVerdict.Block(reason.ifBlank { "该操作被安全策略阻止" })
                    else -> GateVerdict.Confirm(reason.ifBlank { "该操作需要你确认" })
                }
            }
            // 模型没按 JSON 输出时:接受开头裸裁决词或 decision: xxx 形式;拿不准仍降级 Confirm。
            val loose = LOOSE_DECISION.find(text)?.groupValues?.get(1)?.lowercase()
            AppLogger.w(TAG, "unparseable verdict raw=${text.take(160)}")
            return when (loose) {
                "allow" -> GateVerdict.Allow
                "block" -> GateVerdict.Block("该操作被安全策略阻止")
                else -> GateVerdict.Confirm("安全裁决结果无法解析")
            }
        }
    }
}

/**
 * 纯本地规则裁决器:不调模型,按工具名 + 参数关键词做确定性判断。
 * 适合在没有可用模型、或对延迟敏感的路径上兜底;规则刻意保守,
 * 拿不准一律 Confirm,绝不 Allow。
 */
object RuleDecisionGate : DecisionGate {
    // 明确高危工具:直接 Block。
    private val BLOCK_TOOL_KEYWORDS = listOf(
        "payment", "transfer", "pay", "转账", "支付", "付款", "充值",
        "password", "verification", "验证码", "密码", "登录凭证",
        "uninstall", "format", "wipe", "格式化", "清空", "恢复出厂",
    )

    // 外部副作用工具:Confirm。
    private val CONFIRM_TOOL_KEYWORDS = listOf(
        "send", "message", "sms", "post", "publish", "发送", "短信", "发布", "评论",
        "delete", "remove", "删除", "移除",
        "settings", "设置", "修改系统",
    )

    override suspend fun gate(request: GateRequest): GateVerdict = evaluateSync(request)

    /**
     * 同步版规则判定:不调模型、不挂起,可在 AIToolHook.onToolCallIntercept
     * 这类同步回调里直接调用,作为工具执行前的第一道高危拦截。
     */
    fun evaluateSync(request: GateRequest): GateVerdict {
        val hay = (request.toolId + " " + request.toolTitle).lowercase()
        val block = BLOCK_TOOL_KEYWORDS.firstOrNull { hay.contains(it) }
        if (block != null) return GateVerdict.Block("该操作被判定为高风险,已阻止")

        val confirm = CONFIRM_TOOL_KEYWORDS.firstOrNull { hay.contains(it) }
        if (confirm != null) return GateVerdict.Confirm("该操作可能有外部副作用,需要你确认")

        return GateVerdict.Allow
    }
}
