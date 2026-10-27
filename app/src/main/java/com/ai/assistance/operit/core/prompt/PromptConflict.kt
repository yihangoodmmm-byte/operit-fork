package com.ai.assistance.operit.core.prompt

/**
 * 冲突类型。与 proto/src/conflict.ts:19 的 ConflictType 一一对应，
 * 每一类都对应一种「两段提示词互相拆台」的具体形态。
 */
enum class PromptConflictKind {
    /** 强制 vs 禁止：同一行为主题上，一侧要求必须做、另一侧要求不许做。 */
    MODALITY,

    /** 身份断言冲突：两侧都在声称「你是谁」，且互相否定或名字不同。 */
    IDENTITY,

    /** 回答语言冲突。 */
    LANGUAGE,

    /** 输出格式冲突。 */
    OUTPUT_FORMAT,
}

enum class PromptConflictSeverity {
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * 一条冲突。
 *
 * 为什么只有 message 而没有拆成 title / explanation / suggestion：
 * 引擎的消费者是设置页与日志，一条能读的中文说明加上涉及的层 id 就够定位。
 * 拆成四段文案会让每条规则都要维护四份字符串，翻译量翻四倍而信息量不变。
 * message 里带两侧原文片段（截断 180 字），这是人工复核所需的唯一证据。
 */
data class PromptConflict(
    val severity: PromptConflictSeverity,
    val kind: PromptConflictKind,
    /** 面向人的中文说明，包含两侧层标题与原文片段。 */
    val message: String,
    /** 涉及的层 id，按「左 / 右」顺序；默认配置下即「用户侧 / 内置侧」。 */
    val layerIds: List<String>,
)

/**
 * 检测范围。
 *
 * includeUserToUser 默认 false，保持 Persona 原语义：只比较 user × builtin
 * （conflict.ts:1236-1244）。Operit 的人格有多个 user 侧写入点（角色卡、proxy 角色），
 * 保存/预览时必须置 true，否则「用户人格被角色卡顶掉」这类冲突永远检不出来
 * （docs/51-Operit提示词引擎移植.md 6.3b / R1）。
 */
data class ConflictDetectOptions(
    /** true：只看真正进入最终提示词的层；false：连关闭层的潜在冲突一起报。 */
    val includeOnlyIncluded: Boolean = true,
    /** true：额外比较 user 侧内部各层之间的冲突（角色卡 vs 人格）。 */
    val includeUserToUser: Boolean = false,
    val userOrigins: Set<LayerOrigin> = setOf(LayerOrigin.USER),
    val builtinOrigins: Set<LayerOrigin> = setOf(LayerOrigin.BUILTIN),
    /**
     * 按层 id 豁免冲突类型。工具协议、环境路径这类「App 能否工作」的机制文本
     * 与用户的格式偏好天然对立，报出来只会训练用户点「忽略」
     * （docs/51-Operit提示词引擎移植.md 6.3a）。
     */
    val exemptions: Map<String, Set<PromptConflictKind>> = emptyMap(),
)

/**
 * 冲突检测入口。
 *
 * 为什么不依赖模型：全部是分句 + 主题词表 + 情态词的确定性规则，
 * 因此可以在保存配置时同步跑完，结果可复现、可解释、可单测。
 *
 * 为什么不在每轮请求里跑：逐句 × 逐主题 × 逐层是四重循环（conflict.ts:1246-1256），
 * 放在组装路径会拖慢首字延迟；文档 6.1 的结论是保存/预览时跑完整检测，
 * 组装时只做 O(层数) 的开关检查。
 */
object ConflictDetector {

    fun detect(
        result: PromptComposeResult,
        options: ConflictDetectOptions = ConflictDetectOptions(),
    ): List<PromptConflict> = detect(result.layers, options)

    fun detect(
        layers: List<PromptLayer>,
        options: ConflictDetectOptions = ConflictDetectOptions(),
    ): List<PromptConflict> {
        val usable = layers.filter { layer ->
            layer.content.isNotEmpty() && (!options.includeOnlyIncluded || layer.included)
        }
        val leftLayers = usable.filter { options.userOrigins.contains(it.origin) }
        val rightLayers = usable.filter { options.builtinOrigins.contains(it.origin) }

        val pairs = mutableListOf<Pair<PromptLayer, PromptLayer>>()
        for (leftLayer in leftLayers) {
            for (rightLayer in rightLayers) {
                pairs += leftLayer to rightLayer
            }
        }
        if (options.includeUserToUser) {
            // 只补充「同侧两两组合」。若调用方把 userOrigins/builtinOrigins 都设成 USER，
            // 上面的双层循环已经覆盖全部有序组合，这里的追加会被下面的去重吸收。
            for (i in leftLayers.indices) {
                for (j in i + 1 until leftLayers.size) {
                    pairs += leftLayers[i] to leftLayers[j]
                }
            }
        }

        val candidates = mutableListOf<ConflictCandidate>()
        for ((leftLayer, rightLayer) in pairs) {
            candidates += detectModality(leftLayer, rightLayer)
            candidates += detectIdentity(leftLayer, rightLayer)
            candidates += detectLanguage(leftLayer, rightLayer)
            candidates += detectOutputFormat(leftLayer, rightLayer)
        }

        val kept = LinkedHashMap<String, ConflictCandidate>()
        for (candidate in candidates) {
            if (isExempt(candidate, options)) continue
            val key = candidate.kind.name + "|" + candidate.theme + "|" +
                candidate.leftLayerId + "|" + candidate.rightLayerId
            val existing = kept[key]
            if (existing == null || severityRank(candidate.severity) > severityRank(existing.severity)) {
                kept[key] = candidate
            }
        }

        return kept.values
            .sortedWith(
                compareByDescending<ConflictCandidate> { severityRank(it.severity) }
                    .thenBy { kindOrder(it.kind) }
                    .thenBy { it.message },
            )
            .map { candidate ->
                PromptConflict(
                    severity = candidate.severity,
                    kind = candidate.kind,
                    message = candidate.message,
                    layerIds = listOf(candidate.leftLayerId, candidate.rightLayerId),
                )
            }
    }

    private fun isExempt(candidate: ConflictCandidate, options: ConflictDetectOptions): Boolean {
        val leftExempt = options.exemptions[candidate.leftLayerId]?.contains(candidate.kind) == true
        val rightExempt = options.exemptions[candidate.rightLayerId]?.contains(candidate.kind) == true
        return leftExempt || rightExempt
    }

    private fun severityRank(severity: PromptConflictSeverity): Int = when (severity) {
        PromptConflictSeverity.HIGH -> 3
        PromptConflictSeverity.MEDIUM -> 2
        PromptConflictSeverity.LOW -> 1
    }

    private fun kindOrder(kind: PromptConflictKind): Int = when (kind) {
        PromptConflictKind.MODALITY -> 0
        PromptConflictKind.IDENTITY -> 1
        PromptConflictKind.LANGUAGE -> 2
        PromptConflictKind.OUTPUT_FORMAT -> 3
    }

    // -----------------------------------------------------------------------
    // 1) 强制 vs 禁止
    // -----------------------------------------------------------------------

    private data class BehaviorDirective(
        val themeId: String,
        val themeLabel: String,
        val polarity: MarkerPolarity,
        val strength: MarkerStrength,
        val clause: String,
    )

    private fun collectBehaviorDirectives(layer: PromptLayer): List<BehaviorDirective> {
        // 同一主题同一极性只留最强的一条：一层里写十遍「必须用工具」不该产出十条冲突。
        val best = LinkedHashMap<String, BehaviorDirective>()

        for (rawClause in ConflictLexicon.splitClauses(layer.content)) {
            val clause = ConflictLexicon.normalizeClause(rawClause)
            val markers = ConflictLexicon.collectMarkers(clause)
            if (markers.isEmpty()) continue

            for (theme in ConflictLexicon.BEHAVIOR_THEMES) {
                val seen = HashSet<Int>()
                for (keyword in theme.keywords) {
                    for (occurrence in ConflictLexicon.findKeywordOccurrences(clause, keyword)) {
                        if (!seen.add(occurrence.start)) continue
                        if (ConflictLexicon.isMisuseContext(clause, occurrence.start)) continue

                        val classification = ConflictLexicon.classifyPolarity(markers, occurrence.start) ?: continue
                        val directive = BehaviorDirective(
                            themeId = theme.id,
                            themeLabel = theme.label,
                            polarity = classification.polarity,
                            strength = classification.strength,
                            clause = rawClause.trim(),
                        )
                        val key = directive.themeId + ":" + directive.polarity.name
                        val existing = best[key]
                        if (existing == null ||
                            (existing.strength == MarkerStrength.SOFT && directive.strength == MarkerStrength.STRONG)
                        ) {
                            best[key] = directive
                        }
                    }
                }
            }
        }

        return best.values.toList()
    }

    private fun detectModality(left: PromptLayer, right: PromptLayer): List<ConflictCandidate> {
        val leftDirectives = collectBehaviorDirectives(left)
        val rightDirectives = collectBehaviorDirectives(right)
        if (leftDirectives.isEmpty() || rightDirectives.isEmpty()) return emptyList()

        val themeIds = LinkedHashSet<String>()
        leftDirectives.forEach { themeIds += it.themeId }
        rightDirectives.forEach { themeIds += it.themeId }

        val conflicts = mutableListOf<ConflictCandidate>()
        for (themeId in themeIds) {
            val leftMust = leftDirectives.firstOrNull {
                it.themeId == themeId && it.polarity == MarkerPolarity.MUST
            }
            val leftForbid = leftDirectives.firstOrNull {
                it.themeId == themeId && it.polarity == MarkerPolarity.FORBID
            }
            val rightMust = rightDirectives.firstOrNull {
                it.themeId == themeId && it.polarity == MarkerPolarity.MUST
            }
            val rightForbid = rightDirectives.firstOrNull {
                it.themeId == themeId && it.polarity == MarkerPolarity.FORBID
            }

            val matchedLeft: BehaviorDirective
            val matchedRight: BehaviorDirective
            if (leftMust != null && rightForbid != null) {
                matchedLeft = leftMust
                matchedRight = rightForbid
            } else if (leftForbid != null && rightMust != null) {
                matchedLeft = leftForbid
                matchedRight = rightMust
            } else {
                continue
            }

            val severity = ConflictLexicon.severityFrom(matchedLeft.strength, matchedRight.strength, true)
            conflicts += ConflictCandidate(
                kind = PromptConflictKind.MODALITY,
                theme = themeId,
                severity = severity,
                leftLayerId = left.id,
                rightLayerId = right.id,
                message = "强制 vs 禁止：" + matchedLeft.themeLabel +
                    "。「" + left.title + "」要求「" + polarityLabel(matchedLeft.polarity) +
                    "」（原文：" + ConflictLexicon.truncateSnippet(matchedLeft.clause) + "），而「" +
                    right.title + "」要求「" + polarityLabel(matchedRight.polarity) +
                    "」（原文：" + ConflictLexicon.truncateSnippet(matchedRight.clause) +
                    "）。两侧对同一行为主题给出相反指令，模型收到的是互相拉扯的信号。",
            )
        }
        return conflicts
    }

    private fun polarityLabel(polarity: MarkerPolarity): String = when (polarity) {
        MarkerPolarity.MUST -> "必须"
        MarkerPolarity.FORBID -> "禁止"
    }

    // -----------------------------------------------------------------------
    // 2) 身份
    // -----------------------------------------------------------------------

    private enum class IdentityConflictReason {
        DENIED,
        DIFFERENT,
        RIGHT_DENIES,
    }

    private data class IdentityClaim(
        val name: String,
        val normalized: String,
        val polarity: IdentityPolarity,
        val clause: String,
    )

    private fun extractIdentityClaims(layer: PromptLayer): List<IdentityClaim> {
        val claims = mutableListOf<IdentityClaim>()
        val seen = HashSet<String>()

        for (rawClause in ConflictLexicon.splitClauses(layer.content)) {
            for (pattern in ConflictLexicon.IDENTITY_PATTERNS) {
                for (match in pattern.regex.findAll(rawClause)) {
                    val group = match.groupValues.getOrNull(1)
                    if (group == null) continue
                    val name = ConflictLexicon.cleanIdentityName(group) ?: continue
                    val normalized = ConflictLexicon.normalizeIdentityName(name)
                    if (normalized.isEmpty()) continue
                    val key = pattern.polarity.name + ":" + normalized
                    if (!seen.add(key)) continue
                    claims += IdentityClaim(
                        name = name,
                        normalized = normalized,
                        polarity = pattern.polarity,
                        clause = rawClause.trim(),
                    )
                }
            }
        }

        return claims
    }

    private fun detectIdentity(left: PromptLayer, right: PromptLayer): List<ConflictCandidate> {
        val leftClaims = extractIdentityClaims(left)
        val rightClaims = extractIdentityClaims(right)
        if (leftClaims.isEmpty() || rightClaims.isEmpty()) return emptyList()

        val leftPositive = leftClaims.filter { it.polarity == IdentityPolarity.POSITIVE }
        val leftNegative = leftClaims.filter { it.polarity == IdentityPolarity.NEGATIVE }
        val rightPositive = rightClaims.filter { it.polarity == IdentityPolarity.POSITIVE }
        val rightNegative = rightClaims.filter { it.polarity == IdentityPolarity.NEGATIVE }

        var matchedLeft: IdentityClaim? = null
        var matchedRight: IdentityClaim? = null
        var reason: IdentityConflictReason? = null

        // 最明确的否定：右侧说是 X，左侧说「不是 X」。
        for (rightClaim in rightPositive) {
            val denied = leftNegative.firstOrNull { it.normalized == rightClaim.normalized }
            if (denied != null) {
                matchedLeft = denied
                matchedRight = rightClaim
                reason = IdentityConflictReason.DENIED
                break
            }
        }

        // 两个不同的名字：左侧说是 Y，右侧说是 X。
        if (matchedRight == null) {
            for (rightClaim in rightPositive) {
                val different = leftPositive.firstOrNull { it.normalized != rightClaim.normalized }
                if (different != null) {
                    matchedLeft = different
                    matchedRight = rightClaim
                    reason = IdentityConflictReason.DIFFERENT
                    break
                }
            }
        }

        // 反向否定：右侧说「你不是 X」，左侧说「你是 X」。
        if (matchedRight == null) {
            for (rightClaim in rightNegative) {
                val confirmed = leftPositive.firstOrNull { it.normalized == rightClaim.normalized }
                if (confirmed != null) {
                    matchedLeft = confirmed
                    matchedRight = rightClaim
                    reason = IdentityConflictReason.RIGHT_DENIES
                    break
                }
            }
        }

        val resolvedLeft = matchedLeft
        val resolvedRight = matchedRight
        val resolvedReason = reason
        if (resolvedLeft == null || resolvedRight == null || resolvedReason == null) return emptyList()

        val leftDisplay = leftPositive.firstOrNull()?.name ?: resolvedLeft.name
        val rightDisplay = rightPositive.firstOrNull()?.name ?: resolvedRight.name
        val leftSnippet = ConflictLexicon.truncateSnippet(resolvedLeft.clause)
        val rightSnippet = ConflictLexicon.truncateSnippet(resolvedRight.clause)
        // 自称句是可选的：DENIED 分支里用户可能只否认了内置身份，没有另立新身份。
        val selfClaim = leftPositive.firstOrNull()
        val selfClaimPhrase = if (selfClaim == null) "" else "，并自称「" + selfClaim.name + "」"

        // 身份冲突固定 HIGH：身份归属只能有一个，模型必须二选一（conflict.ts:746）。
        val message = when (resolvedReason) {
            IdentityConflictReason.DIFFERENT ->
                "人格冲突：「" + right.title + "」声称「You are " + rightDisplay +
                    "」（原文：" + rightSnippet + "），而「" + left.title + "」声称「你就是 " +
                    leftDisplay + "」（原文：" + leftSnippet +
                    "）。两段人格在争夺「我是谁」的锚点，最终服从哪一段取决于出现位置与措辞强度。"
            IdentityConflictReason.DENIED ->
                "人格冲突：「" + right.title + "」声称「You are " + rightDisplay +
                    "」，而「" + left.title + "」明确否认这个身份（原文：" + leftSnippet +
                    "）" + selfClaimPhrase +
                    "。这是用户人格被另一段身份直接顶掉的高风险信号。"
            IdentityConflictReason.RIGHT_DENIES ->
                "人格冲突：「" + right.title + "」否认身份「" + resolvedRight.name +
                    "」（原文：" + rightSnippet + "），而「" + left.title +
                    "」明确要求这个身份（原文：" + leftSnippet + "）。两者对「我是谁」给出相反断言。"
        }

        return listOf(
            ConflictCandidate(
                kind = PromptConflictKind.IDENTITY,
                theme = "identity",
                severity = PromptConflictSeverity.HIGH,
                leftLayerId = left.id,
                rightLayerId = right.id,
                message = message,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // 3) 语言
    // -----------------------------------------------------------------------

    private enum class LanguageConflictKind {
        DIFFERENT_REQUIRED,
        LEFT_FORBIDS,
        RIGHT_FORBIDS,
    }

    private data class LanguageDirective(
        val lang: String,
        val label: String,
        val polarity: DirectivePolarity,
        val strength: MarkerStrength,
        val clause: String,
    )

    private fun collectLanguageDirectives(layer: PromptLayer): List<LanguageDirective> {
        val best = LinkedHashMap<String, LanguageDirective>()

        for (rawClause in ConflictLexicon.splitClauses(layer.content)) {
            if (ConflictLexicon.isExemptClause(rawClause)) continue
            if (ConflictLexicon.isPermissiveLanguageClause(rawClause)) continue
            val clause = ConflictLexicon.normalizeClause(rawClause)
            val markers = ConflictLexicon.collectMarkers(clause)

            for (language in ConflictLexicon.LANGUAGES) {
                val seen = HashSet<Int>()
                for (keyword in language.keywords) {
                    for (occurrence in ConflictLexicon.findKeywordOccurrences(clause, keyword)) {
                        if (!seen.add(occurrence.start)) continue

                        val classification = ConflictLexicon.classifyPolarity(markers, occurrence.start)
                        val polarity: DirectivePolarity
                        val strength: MarkerStrength
                        if (classification != null) {
                            polarity = if (classification.polarity == MarkerPolarity.MUST) {
                                DirectivePolarity.REQUIRE
                            } else {
                                DirectivePolarity.FORBID
                            }
                            strength = classification.strength
                        } else {
                            val inferred = ConflictLexicon.inferLanguageStrength(
                                clause,
                                occurrence.start,
                                occurrence.end,
                            ) ?: continue
                            polarity = DirectivePolarity.REQUIRE
                            strength = inferred
                        }

                        val directive = LanguageDirective(
                            lang = language.code,
                            label = language.label,
                            polarity = polarity,
                            strength = strength,
                            clause = rawClause.trim(),
                        )
                        val key = directive.lang + ":" + directive.polarity.name
                        val existing = best[key]
                        if (existing == null ||
                            (existing.strength == MarkerStrength.SOFT && directive.strength == MarkerStrength.STRONG)
                        ) {
                            best[key] = directive
                        }
                    }
                }
            }
        }

        return best.values.toList()
    }

    private fun detectLanguage(left: PromptLayer, right: PromptLayer): List<ConflictCandidate> {
        val leftDirectives = collectLanguageDirectives(left)
        val rightDirectives = collectLanguageDirectives(right)
        if (leftDirectives.isEmpty() || rightDirectives.isEmpty()) return emptyList()

        val leftRequire = leftDirectives.filter { it.polarity == DirectivePolarity.REQUIRE }
        val leftForbid = leftDirectives.filter { it.polarity == DirectivePolarity.FORBID }
        val rightRequire = rightDirectives.filter { it.polarity == DirectivePolarity.REQUIRE }
        val rightForbid = rightDirectives.filter { it.polarity == DirectivePolarity.FORBID }

        var matchedLeft: LanguageDirective? = null
        var matchedRight: LanguageDirective? = null
        var kind: LanguageConflictKind? = null

        // 优先级 1：双方要求不同语言。同一条回复无法同时满足两种互斥要求。
        for (leftDirective in leftRequire) {
            val rightDirective = rightRequire.firstOrNull { it.lang != leftDirective.lang }
            if (rightDirective != null) {
                matchedLeft = leftDirective
                matchedRight = rightDirective
                kind = LanguageConflictKind.DIFFERENT_REQUIRED
                break
            }
        }

        // 优先级 2：右侧要求某语言，左侧明确禁止该语言。
        if (kind == null) {
            for (leftDirective in leftForbid) {
                val rightDirective = rightRequire.firstOrNull { it.lang == leftDirective.lang }
                if (rightDirective != null) {
                    matchedLeft = leftDirective
                    matchedRight = rightDirective
                    kind = LanguageConflictKind.LEFT_FORBIDS
                    break
                }
            }
        }

        // 优先级 3：左侧要求某语言，右侧明确禁止该语言。
        if (kind == null) {
            for (leftDirective in leftRequire) {
                val rightDirective = rightForbid.firstOrNull { it.lang == leftDirective.lang }
                if (rightDirective != null) {
                    matchedLeft = leftDirective
                    matchedRight = rightDirective
                    kind = LanguageConflictKind.RIGHT_FORBIDS
                    break
                }
            }
        }

        val resolvedLeft = matchedLeft
        val resolvedRight = matchedRight
        val resolvedKind = kind
        if (resolvedLeft == null || resolvedRight == null || resolvedKind == null) return emptyList()

        val severity = ConflictLexicon.severityFrom(resolvedLeft.strength, resolvedRight.strength, true)
        val leftSnippet = ConflictLexicon.truncateSnippet(resolvedLeft.clause)
        val rightSnippet = ConflictLexicon.truncateSnippet(resolvedRight.clause)

        val message = when (resolvedKind) {
            LanguageConflictKind.DIFFERENT_REQUIRED ->
                "语言冲突：「" + left.title + "」要求用「" + resolvedLeft.label +
                    "」回答（原文：" + leftSnippet + "），而「" + right.title +
                    "」要求用「" + resolvedRight.label + "」回答（原文：" + rightSnippet +
                    "）。同一条回复无法同时满足两种互斥的语言要求。"
            LanguageConflictKind.LEFT_FORBIDS ->
                "语言冲突：「" + left.title + "」明确禁止使用「" + resolvedLeft.label +
                    "」（原文：" + leftSnippet + "），而「" + right.title + "」要求使用「" +
                    resolvedRight.label + "」（原文：" + rightSnippet + "）。这是直接互斥的指令。"
            LanguageConflictKind.RIGHT_FORBIDS ->
                "语言冲突：「" + left.title + "」要求使用「" + resolvedLeft.label +
                    "」（原文：" + leftSnippet + "），而「" + right.title + "」禁止使用该语言（原文：" +
                    rightSnippet + "）。两者无法同时满足。"
        }

        return listOf(
            ConflictCandidate(
                kind = PromptConflictKind.LANGUAGE,
                theme = "language:" + resolvedLeft.lang + "-" + resolvedRight.lang,
                severity = severity,
                leftLayerId = left.id,
                rightLayerId = right.id,
                message = message,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // 4) 输出格式
    // -----------------------------------------------------------------------

    private enum class FormatConflictKind {
        OPPOSITE,
        PLAINTEXT,
        TOP_LEVEL,
    }

    private data class FormatDirective(
        val format: String,
        val label: String,
        val polarity: DirectivePolarity,
        val strength: MarkerStrength,
        val clause: String,
    )

    private data class FormatPairCandidate(
        val left: FormatDirective,
        val right: FormatDirective,
        val priority: Int,
        val severity: PromptConflictSeverity,
        val kind: FormatConflictKind,
    )

    private fun collectFormatDirectives(layer: PromptLayer): List<FormatDirective> {
        val best = LinkedHashMap<String, FormatDirective>()

        for (rawClause in ConflictLexicon.splitClauses(layer.content)) {
            if (ConflictLexicon.isExemptClause(rawClause)) continue
            if (ConflictLexicon.isPermissiveFormatClause(rawClause)) continue
            val clause = ConflictLexicon.normalizeClause(rawClause)
            val markers = ConflictLexicon.collectMarkers(clause)

            for (format in ConflictLexicon.FORMATS) {
                val seen = HashSet<Int>()
                for (keyword in format.keywords) {
                    for (occurrence in ConflictLexicon.findKeywordOccurrences(clause, keyword)) {
                        if (!seen.add(occurrence.start)) continue

                        val classification = ConflictLexicon.classifyPolarity(markers, occurrence.start)
                        val polarity: DirectivePolarity
                        val strength: MarkerStrength
                        if (classification != null) {
                            polarity = if (classification.polarity == MarkerPolarity.MUST) {
                                DirectivePolarity.REQUIRE
                            } else {
                                DirectivePolarity.FORBID
                            }
                            strength = classification.strength
                        } else {
                            val inferred = ConflictLexicon.inferFormatStrength(
                                clause,
                                occurrence.start,
                                occurrence.end,
                            ) ?: continue
                            polarity = DirectivePolarity.REQUIRE
                            strength = inferred
                        }

                        val directive = FormatDirective(
                            format = format.id,
                            label = format.label,
                            polarity = polarity,
                            strength = strength,
                            clause = rawClause.trim(),
                        )
                        val key = directive.format + ":" + directive.polarity.name
                        val existing = best[key]
                        if (existing == null ||
                            (existing.strength == MarkerStrength.SOFT && directive.strength == MarkerStrength.STRONG)
                        ) {
                            best[key] = directive
                        }
                    }
                }
            }
        }

        return best.values.toList()
    }

    private fun detectOutputFormat(left: PromptLayer, right: PromptLayer): List<ConflictCandidate> {
        val leftDirectives = collectFormatDirectives(left)
        val rightDirectives = collectFormatDirectives(right)
        if (leftDirectives.isEmpty() || rightDirectives.isEmpty()) return emptyList()

        val candidates = mutableListOf<FormatPairCandidate>()
        for (leftDirective in leftDirectives) {
            for (rightDirective in rightDirectives) {
                val severity = ConflictLexicon.severityFrom(
                    leftDirective.strength,
                    rightDirective.strength,
                    true,
                )

                // 同一格式，一侧要求一侧禁止：最直接的互斥。
                if (leftDirective.format == rightDirective.format &&
                    leftDirective.polarity != rightDirective.polarity
                ) {
                    candidates += FormatPairCandidate(
                        left = leftDirective,
                        right = rightDirective,
                        priority = 110,
                        severity = severity,
                        kind = FormatConflictKind.OPPOSITE,
                    )
                    continue
                }

                // 两种不同的「要求」：纯文本与任何富格式互斥；两个顶层格式互斥。
                if (leftDirective.polarity == DirectivePolarity.REQUIRE &&
                    rightDirective.polarity == DirectivePolarity.REQUIRE &&
                    leftDirective.format != rightDirective.format
                ) {
                    if (leftDirective.format == "plaintext" || rightDirective.format == "plaintext") {
                        candidates += FormatPairCandidate(
                            left = leftDirective,
                            right = rightDirective,
                            priority = 120,
                            severity = severity,
                            kind = FormatConflictKind.PLAINTEXT,
                        )
                    } else if (
                        ConflictLexicon.TOP_LEVEL_FORMATS.contains(leftDirective.format) &&
                        ConflictLexicon.TOP_LEVEL_FORMATS.contains(rightDirective.format)
                    ) {
                        candidates += FormatPairCandidate(
                            left = leftDirective,
                            right = rightDirective,
                            priority = 80,
                            severity = severity,
                            kind = FormatConflictKind.TOP_LEVEL,
                        )
                    }
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // 每对层最多报一条：格式组合容易爆炸，只留优先级最高、同优先级下最严重的那条。
        val chosen = candidates.sortedWith(
            compareByDescending<FormatPairCandidate> { it.priority }
                .thenByDescending { severityRank(it.severity) },
        ).first()

        val leftSnippet = ConflictLexicon.truncateSnippet(chosen.left.clause)
        val rightSnippet = ConflictLexicon.truncateSnippet(chosen.right.clause)

        val message = when (chosen.kind) {
            FormatConflictKind.OPPOSITE -> {
                if (chosen.left.polarity == DirectivePolarity.FORBID) {
                    "输出格式冲突：「" + left.title + "」禁止「" + chosen.left.label +
                        "」格式（原文：" + leftSnippet + "），而「" + right.title + "」要求「" +
                        chosen.right.label + "」（原文：" + rightSnippet +
                        "）。格式指令直接互斥，模型通常更偏向较晚出现的那一条。"
                } else {
                    "输出格式冲突：「" + left.title + "」要求「" + chosen.left.label +
                        "」格式（原文：" + leftSnippet + "），而「" + right.title + "」禁止该格式（原文：" +
                        rightSnippet + "）。两者无法同时满足。"
                }
            }
            FormatConflictKind.PLAINTEXT ->
                "输出格式冲突：「" + left.title + "」要求整体输出为「" + chosen.left.label +
                    "」（原文：" + leftSnippet + "），而「" + right.title + "」要求「" +
                    chosen.right.label + "」（原文：" + rightSnippet +
                    "）。纯文本与富格式互斥，模型只能二选一。"
            FormatConflictKind.TOP_LEVEL ->
                "输出格式冲突：「" + left.title + "」要求「" + chosen.left.label +
                    "」（原文：" + leftSnippet + "），而「" + right.title + "」要求「" +
                    chosen.right.label + "」（原文：" + rightSnippet +
                    "）。两个顶层格式要求互斥，模型只能二选一。"
        }

        return listOf(
            ConflictCandidate(
                kind = PromptConflictKind.OUTPUT_FORMAT,
                theme = "format:" + chosen.left.format + "-" + chosen.right.format,
                severity = chosen.severity,
                leftLayerId = left.id,
                rightLayerId = right.id,
                message = message,
            ),
        )
    }
}

/** 内部候选。theme 参与去重但不进对外的 PromptConflict——外部只需要可读的 message。 */
private data class ConflictCandidate(
    val kind: PromptConflictKind,
    val theme: String,
    val severity: PromptConflictSeverity,
    val message: String,
    val leftLayerId: String,
    val rightLayerId: String,
)

