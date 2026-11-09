package com.ai.assistance.operit.core.chat

/**
 * 主流模型上下文窗口注册表（本地离线兜底）。
 *
 * 背景：provider 的 /models 接口通常不返回 context window，因此主流 harness
 * （Pi / OpenCode / Cline / 太墟）都内置一张「模型 id → 最大上下文」元数据表，
 * 在发请求前按模型 id 解析出真实窗口，而不是让用户手动填一个 64K/200K 二选一。
 *
 * 解析顺序：
 * 1. 用户显式配置的 maxContextLength（作为兜底，由调用方处理）；
 * 2. 本表按模型 id 识别出的主流模型窗口；
 * 3. 全局兜底 DEFAULT_FALLBACK_TOKENS。
 *
 * 数值取各主流 harness 使用的 API 上下文窗口，已向上取整。
 */
object ModelContextWindows {
    private data class Rule(
        val label: String,
        val pattern: Regex,
        val tokens: Int,
    )

    /**
     * 顺序有意义：具体型号族必须排在宽泛的厂商兜底之前。
     */
    private val rules = listOf(
        Rule("DeepSeek V4/Flash", Regex("^deepseek-(v4|flash|4[.]0)"), 1_000_000),
        Rule("DeepSeek V3/R1", Regex("^deepseek-(v3|r1|chat|reasoner)"), 128_000),
        Rule("DeepSeek", Regex("^deepseek"), 128_000),
        Rule("GPT-4.1", Regex("^gpt-4[.]1"), 1_047_576),
        Rule("GPT-4o", Regex("^gpt-4o"), 128_000),
        Rule("GPT-5", Regex("^gpt-5"), 400_000),
        Rule("o-series", Regex("^(o1|o3|o4)"), 200_000),
        Rule("gpt-oss", Regex("^gpt-oss"), 131_072),
        Rule("GPT legacy", Regex("^gpt-4"), 128_000),
        Rule("GPT", Regex("^gpt"), 128_000),
        Rule("Claude", Regex("^claude"), 200_000),
        Rule("Gemini 2.5 Pro", Regex("^gemini-2[.]5-pro"), 2_097_152),
        Rule("Gemini", Regex("^gemini"), 1_048_576),
        Rule("GLM-5/4.6", Regex("^glm-(5|4[.]6)"), 200_000),
        Rule("GLM", Regex("^glm"), 128_000),
        Rule("Doubao 32k", Regex("^doubao.*32k"), 32_768),
        Rule("Doubao Seed", Regex("^doubao-seed"), 262_144),
        Rule("Doubao", Regex("^doubao"), 128_000),
        Rule("Qwen3 Coder", Regex("^qwen3-coder"), 1_000_000),
        Rule("Qwen3 Max", Regex("^qwen3.*max"), 1_000_000),
        Rule("Qwen3", Regex("^qwen3"), 262_144),
        Rule("Qwen2.5", Regex("^qwen2[.]5"), 32_768),
        Rule("Qwen", Regex("^qwen"), 131_072),
        Rule("Kimi K3", Regex("^kimi-k3"), 262_144),
        Rule("Kimi K2", Regex("^kimi-k2"), 262_144),
        Rule("Moonshot", Regex("^moonshot"), 128_000),
        Rule("MiniMax M3", Regex("^minimax-m3"), 1_000_000),
        Rule("MiniMax M2", Regex("^minimax-m2"), 204_800),
        Rule("MiniMax", Regex("^minimax"), 204_800),
        Rule("Grok", Regex("^grok"), 256_000),
        Rule("Llama 4", Regex("^llama-4"), 1_000_000),
        Rule("Llama 3", Regex("^llama-3"), 131_072),
        Rule("Mistral Large", Regex("^mistral-large"), 131_072),
        Rule("Mistral Medium", Regex("^mistral-medium"), 131_072),
        Rule("Mistral/Mixtral", Regex("^(mistral|mixtral)"), 32_768),
    )

    /** 按模型 id 解析上下文窗口（token 数），识别不出返回 null。 */
    fun resolveTokens(modelId: String?): Int? {
        val normalized = normalize(modelId) ?: return null
        return rules.firstOrNull { it.pattern.containsMatchIn(normalized) }?.tokens
    }

    /** 解析出窗口，识别不出时返回 [defaultTokens]。 */
    fun resolveOrDefault(modelId: String?, defaultTokens: Int = DEFAULT_FALLBACK_TOKENS): Int =
        resolveTokens(modelId) ?: defaultTokens

    /**
     * 归一化 OpenRouter / SiliconFlow 别名和多模型档案字段：
     * `deepseek-ai/DeepSeek-V3.2:free` -> `deepseek-v3.2`
     */
    private fun normalize(raw: String?): String? {
        val cleaned = raw
            ?.substringBefore(',')
            ?.trim()
            ?.lowercase()
            ?.substringAfterLast('/')
            ?.substringBefore(':')
            ?.trim()
        return cleaned?.takeIf { it.isNotBlank() }
    }

    const val DEFAULT_FALLBACK_TOKENS = 128_000
}
