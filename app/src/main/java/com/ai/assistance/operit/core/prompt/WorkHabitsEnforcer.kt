package com.ai.assistance.operit.core.prompt

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工作习惯强校验器。
 *
 * 背景：工作习惯通常是「几百行、结构松散」的持续性约定，纯靠提示词注入会被
 * context rot 稀释、被 persona_precedence 压制。真正能「保证」服从的，是把它
 * 变成代码里可程序化判断对错、违规即重试的硬约束。
 *
 * 本对象只做「判断」，不做「注入」也不做「重试」：
 * - parseRules：把持久化的 JSON 规则字符串解析成 [WorkHabitsRule]；
 * - check：给定一段模型输出，返回违反的规则列表（空 = 通过）；
 * 调用方（EnhancedAIService 的流收集完成后）拿到违规列表，自行决定追加纠错消息重试。
 *
 * 规则 JSON 结构（与 ApiPreferences.WORK_HABITS_RULES_JSON 对应）：
 * [
 *   { "id": "must_reply_markdown", "label": "必须用 Markdown 回复", "regex": "..." },
 *   { "id": "must_plan_first",   "label": "必须先列计划",       "regex": "..." }
 * ]
 *
 * 匹配语义：regex 是对「输出里必须出现」的约束（MUST_CONTAIN）；未匹配到即视为违规。
 * regex 为空或非法时跳过该条（避免一条坏规则拖垮整段校验）。
 */
object WorkHabitsEnforcer {

    /** 单条校验规则。 */
    data class WorkHabitsRule(
        val id: String,
        val label: String,
        val regex: Regex?,
    )

    /** 一次校验的结果。 */
    data class WorkHabitsViolation(
        val ruleId: String,
        val label: String,
    )

    /**
     * 解析规则 JSON。任何单条解析失败都不抛异常，只跳过坏条目。
     * 非法整体 JSON 返回空列表（视为「没有可校验的规则」）。
     */
    fun parseRules(rulesJson: String): List<WorkHabitsRule> {
        if (rulesJson.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(rulesJson) }.getOrNull() ?: return emptyList()
        val rules = mutableListOf<WorkHabitsRule>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            if (id.isEmpty()) continue
            val label = obj.optString("label").trim().ifEmpty { id }
            val pattern = obj.optString("regex").trim()
            val regex = if (pattern.isEmpty()) null else runCatching {
                Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            }.getOrNull()
            if (regex == null) continue
            rules += WorkHabitsRule(id = id, label = label, regex = regex)
        }
        return rules
    }

    /**
     * 检查输出是否满足全部 MUST_CONTAIN 规则。
     * 返回违反的规则列表；空列表表示全部通过。
     */
    fun check(output: String, rules: List<WorkHabitsRule>): List<WorkHabitsViolation> {
        if (rules.isEmpty() || output.isBlank()) return emptyList()
        val violations = mutableListOf<WorkHabitsViolation>()
        for (rule in rules) {
            val regex = rule.regex ?: continue
            if (!regex.containsMatchIn(output)) {
                violations += WorkHabitsViolation(ruleId = rule.id, label = rule.label)
            }
        }
        return violations
    }

    /** 生成一条「你违反了习惯，重来」的纠错消息，供调用方追加为新的 user turn 触发重试。 */
    fun buildCorrectionMessage(violations: List<WorkHabitsViolation>): String {
        if (violations.isEmpty()) return ""
        val names = violations.joinToString("、") { it.label }
        return "你刚才的回复违反了以下工作习惯，请严格修正后重新完整回复（不要只解释）：$names"
    }
}
