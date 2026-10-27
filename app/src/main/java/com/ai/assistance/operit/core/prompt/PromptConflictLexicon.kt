package com.ai.assistance.operit.core.prompt

import kotlin.math.abs

/**
 * 冲突检测的词表与文本工具。
 *
 * 为什么独立成文件：词表是「可人工维护的数据」，检测逻辑是「算法」。
 * 混在一起会让「加一个主题词」看起来像「改算法」，评审时难以判断风险。
 * 每条词表都能指回 proto/src/conflict.ts 的行号，改动必须同步那边，否则两套检测器
 * 会对同一份提示词给出不同结论。
 */

internal enum class MarkerStrength {
    STRONG,
    SOFT,
}

internal enum class MarkerPolarity {
    MUST,
    FORBID,
}

/** 语言/格式指令的极性。与情态词极性一一对应，但语义更贴近各自规则。 */
internal enum class DirectivePolarity {
    REQUIRE,
    FORBID,
}

internal enum class IdentityPolarity {
    POSITIVE,
    NEGATIVE,
}

internal data class MarkerRule(
    val keyword: String,
    val strength: MarkerStrength,
    val polarity: MarkerPolarity,
)

internal data class MarkerOccurrence(
    val start: Int,
    val end: Int,
)

internal data class Marker(
    val start: Int,
    val end: Int,
    val strength: MarkerStrength,
    val polarity: MarkerPolarity,
)

internal data class PolarityClassification(
    val polarity: MarkerPolarity,
    val strength: MarkerStrength,
)

/** 行为主题。检测「强制 vs 禁止」时只看同一主题上的相反极性（conflict.ts:367-440）。 */
internal data class BehaviorTheme(
    val id: String,
    val label: String,
    val keywords: List<String>,
)

internal data class IdentityPattern(
    val regex: Regex,
    val polarity: IdentityPolarity,
)

internal data class LanguageSpec(
    val code: String,
    val label: String,
    val keywords: List<String>,
)

internal data class FormatSpec(
    val id: String,
    val label: String,
    val keywords: List<String>,
)

internal object ConflictLexicon {

    // -----------------------------------------------------------------------
    // 文本工具
    // -----------------------------------------------------------------------

    private val SENTENCE_END: Regex = Regex("""[。！？!?；;\n\r]+""")

    /**
     * 英文句号只在「. + 空白 + 大写/引号/#」时才切，避免切开小数（3.5）、
     * 缩写（e.g.）与文件名（SKILL.md）。conflict.ts:62-74 的取舍，移植时不要放宽。
     */
    private val ENGLISH_SENTENCE_END: Regex = Regex("""\.\s+(?=[A-Z#*"'“])""")

    private val COMMA_SEPARATOR: Regex = Regex("""[，,]+""")

    private val FORGET_PATTERN: Regex = Regex(
        """不要忘记|别忘了|勿忘|do not forget|don't forget""",
        RegexOption.IGNORE_CASE,
    )

    private val EXEMPT_CLAUSE_PATTERN: Regex = Regex(
        """除非|除外|unless|except|如果用户|用户(?:明确)?(?:要求|指定|想要|需要)|以用户为准|用户另有要求""",
        RegexOption.IGNORE_CASE,
    )

    private val MISUSE_PATTERN: Regex = Regex(
        """滥用|过度|盲目|misuse|abuse|overuse""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 中英分句。检测粒度是「分句」而不是「整层」：
     * 一层里同时写着「用中文回答」和引用别人的英文要求时，按层判断必然误报。
     */
    fun splitClauses(text: String): List<String> {
        val clauses = mutableListOf<String>()
        for (rough in text.split(SENTENCE_END)) {
            for (sentence in rough.split(ENGLISH_SENTENCE_END)) {
                for (part in sentence.split(COMMA_SEPARATOR)) {
                    val clause = part.trim()
                    if (clause.isNotEmpty()) clauses += clause
                }
            }
        }
        return clauses
    }

    /** 把「不要忘记 / 别忘了」归一成「必须」，减少双重否定造成的极性反转误判（conflict.ts:76-79）。 */
    fun normalizeClause(clause: String): String = FORGET_PATTERN.replace(clause, "必须")

    /** 条件句豁免：「除非用户要求」「以用户为准」这类句子本身就是在让位给用户，不该报冲突。 */
    fun isExemptClause(clause: String): Boolean = EXEMPT_CLAUSE_PATTERN.containsMatchIn(clause)

    /** 「不要滥用工具」不是在禁止工具，只是约束使用方式（conflict.ts:451-454）。 */
    fun isMisuseContext(clause: String, index: Int): Boolean {
        val before = clause.substring(maxOf(0, index - 6), index)
        return MISUSE_PATTERN.containsMatchIn(before)
    }

    /**
     * 关键词出现位置。
     *
     * 英文按词边界匹配并允许简单复数（tool → tools / section → sections），
     * 中文直接子串匹配。这里每次调用都新建 Regex：检测只在保存/预览时跑
     * （docs/51 6.1），不在请求路径上，因此用可读性换这点分配是划算的。
     */
    fun findKeywordOccurrences(text: String, keyword: String): List<MarkerOccurrence> {
        val lowerText = text.lowercase()
        val lowerKeyword = keyword.lowercase()
        if (lowerKeyword.isEmpty()) return emptyList()

        if (isAscii(lowerKeyword)) {
            val suffix = if (lowerKeyword.endsWith("s")) "" else "(?:s|es)?"
            val pattern = Regex("""\b""" + Regex.escape(lowerKeyword) + suffix + """\b""")
            return pattern.findAll(lowerText).map { match ->
                MarkerOccurrence(match.range.first, match.range.last + 1)
            }.toList()
        }

        val occurrences = mutableListOf<MarkerOccurrence>()
        var from = 0
        while (true) {
            val index = lowerText.indexOf(lowerKeyword, from)
            if (index < 0) break
            occurrences += MarkerOccurrence(index, index + lowerKeyword.length)
            from = index + lowerKeyword.length
        }
        return occurrences
    }

    private fun isAscii(text: String): Boolean = text.all { it.code <= 0x7F }

    /** 原文片段截断：冲突证据只用于人工复核，长段落截断到 180 字并压成一行（conflict.ts:81-84）。 */
    fun truncateSnippet(snippet: String, maxLength: Int = 180): String {
        val oneLine = snippet.replace(Regex("""\s+"""), " ").trim()
        return if (oneLine.length > maxLength) oneLine.substring(0, maxLength) + "…" else oneLine
    }

    // -----------------------------------------------------------------------
    // 情态词表（conflict.ts:143-227）
    // -----------------------------------------------------------------------

    private fun rules(
        polarity: MarkerPolarity,
        vararg entries: Pair<String, MarkerStrength>,
    ): List<MarkerRule> = entries.map { MarkerRule(it.first, it.second, polarity) }

    /**
     * 表里的顺序不影响结果：collectMarkers 会剔除被更长短语包含的短标记
     * （must not 里的 must、千万不要里的 不要），再按出现位置排序。
     */
    private val MUST_MARKERS: List<MarkerRule> = rules(
        MarkerPolarity.MUST,
        "必须" to MarkerStrength.STRONG,
        "务必" to MarkerStrength.STRONG,
        "请务必" to MarkerStrength.STRONG,
        "一定要" to MarkerStrength.STRONG,
        "只能" to MarkerStrength.STRONG,
        "只准" to MarkerStrength.STRONG,
        "只可" to MarkerStrength.STRONG,
        "只允许" to MarkerStrength.STRONG,
        "只输出" to MarkerStrength.STRONG,
        "只回答" to MarkerStrength.STRONG,
        "只回复" to MarkerStrength.STRONG,
        "只用" to MarkerStrength.STRONG,
        "只使用" to MarkerStrength.STRONG,
        "仅使用" to MarkerStrength.STRONG,
        "仅输出" to MarkerStrength.STRONG,
        "一律" to MarkerStrength.STRONG,
        "只得" to MarkerStrength.STRONG,
        "仅限" to MarkerStrength.STRONG,
        "应该" to MarkerStrength.SOFT,
        "应当" to MarkerStrength.SOFT,
        "请" to MarkerStrength.SOFT,
        "尽量" to MarkerStrength.SOFT,
        "建议" to MarkerStrength.SOFT,
        "最好" to MarkerStrength.SOFT,
        "默认" to MarkerStrength.SOFT,
        "优先" to MarkerStrength.SOFT,
        "must" to MarkerStrength.STRONG,
        "always" to MarkerStrength.STRONG,
        "required" to MarkerStrength.STRONG,
        "mandatory" to MarkerStrength.STRONG,
        "only" to MarkerStrength.STRONG,
        "shall" to MarkerStrength.STRONG,
        "have to" to MarkerStrength.STRONG,
        "need to" to MarkerStrength.STRONG,
        "please" to MarkerStrength.SOFT,
        "should" to MarkerStrength.SOFT,
        "prefer" to MarkerStrength.SOFT,
        "try to" to MarkerStrength.SOFT,
        "default" to MarkerStrength.SOFT,
    )

    private val FORBID_MARKERS: List<MarkerRule> = rules(
        MarkerPolarity.FORBID,
        "严禁" to MarkerStrength.STRONG,
        "禁止" to MarkerStrength.STRONG,
        "不许" to MarkerStrength.STRONG,
        "不准" to MarkerStrength.STRONG,
        "不得" to MarkerStrength.STRONG,
        "绝不" to MarkerStrength.STRONG,
        "绝对不" to MarkerStrength.STRONG,
        "切勿" to MarkerStrength.STRONG,
        "千万不要" to MarkerStrength.STRONG,
        "不要" to MarkerStrength.SOFT,
        "无需" to MarkerStrength.SOFT,
        "不用" to MarkerStrength.SOFT,
        "不必" to MarkerStrength.SOFT,
        "不需要" to MarkerStrength.SOFT,
        "不能" to MarkerStrength.SOFT,
        "不可以" to MarkerStrength.SOFT,
        "避免" to MarkerStrength.SOFT,
        "never" to MarkerStrength.STRONG,
        "must not" to MarkerStrength.STRONG,
        "mustn't" to MarkerStrength.STRONG,
        "do not" to MarkerStrength.STRONG,
        "don't" to MarkerStrength.STRONG,
        "don’t" to MarkerStrength.STRONG,
        "forbidden" to MarkerStrength.STRONG,
        "prohibited" to MarkerStrength.STRONG,
        "under no circumstances" to MarkerStrength.STRONG,
        "should not" to MarkerStrength.SOFT,
        "shouldn't" to MarkerStrength.SOFT,
        "avoid" to MarkerStrength.SOFT,
        "refrain from" to MarkerStrength.SOFT,
    )

    private val ALL_MARKERS: List<MarkerRule> = MUST_MARKERS + FORBID_MARKERS

    /**
     * 收集一个分句里的情态标记。
     *
     * 关键细节：先剔除被更长标记完全包含的短标记，否则「must not」会同时被读成
     * must（必须）与 forbid（禁止），极性判定变成掷硬币。
     */
    fun collectMarkers(clause: String): List<Marker> {
        val markers = mutableListOf<Marker>()
        for (rule in ALL_MARKERS) {
            for (occurrence in findKeywordOccurrences(clause, rule.keyword)) {
                markers += Marker(occurrence.start, occurrence.end, rule.strength, rule.polarity)
            }
        }

        val filtered = markers.filterIndexed { index, marker ->
            markers.withIndex().none { indexed ->
                val other = indexed.value
                indexed.index != index &&
                    (other.end - other.start) > (marker.end - marker.start) &&
                    other.start <= marker.start &&
                    other.end >= marker.end
            }
        }
        return filtered.sortedBy { it.start }
    }

    private fun distanceToMarkers(markers: List<Marker>, index: Int): Int? {
        if (markers.isEmpty()) return null
        var best = Int.MAX_VALUE
        for (marker in markers) {
            best = minOf(best, abs(marker.start - index))
        }
        return best
    }

    private fun strengthAtDistance(markers: List<Marker>, index: Int, distance: Int): MarkerStrength =
        if (markers.any { abs(it.start - index) == distance && it.strength == MarkerStrength.STRONG }) {
            MarkerStrength.STRONG
        } else {
            MarkerStrength.SOFT
        }

    /**
     * 取离主题词最近的情态词作为该主题的极性。
     *
     * 距离相等时返回 null：同一句里「必须」和「禁止」一样近，说明句子本身有歧义，
     * 与其猜一个极性，不如不报（conflict.ts:294-295）。
     */
    fun classifyPolarity(markers: List<Marker>, index: Int): PolarityClassification? {
        val mustMarkers = markers.filter { it.polarity == MarkerPolarity.MUST }
        val forbidMarkers = markers.filter { it.polarity == MarkerPolarity.FORBID }
        val mustDistance = distanceToMarkers(mustMarkers, index)
        val forbidDistance = distanceToMarkers(forbidMarkers, index)

        if (mustDistance == null && forbidDistance == null) return null
        if (mustDistance != null && forbidDistance == null) {
            return PolarityClassification(
                MarkerPolarity.MUST,
                strengthAtDistance(mustMarkers, index, mustDistance),
            )
        }
        if (forbidDistance != null && mustDistance == null) {
            return PolarityClassification(
                MarkerPolarity.FORBID,
                strengthAtDistance(forbidMarkers, index, forbidDistance),
            )
        }
        if (mustDistance == null || forbidDistance == null) return null
        if (mustDistance == forbidDistance) return null

        return if (mustDistance < forbidDistance) {
            PolarityClassification(MarkerPolarity.MUST, strengthAtDistance(mustMarkers, index, mustDistance))
        } else {
            PolarityClassification(MarkerPolarity.FORBID, strengthAtDistance(forbidMarkers, index, forbidDistance))
        }
    }

    /** 严重度映射，与 conflict.ts:306-311 完全一致。 */
    fun severityFrom(
        left: MarkerStrength,
        right: MarkerStrength,
        direct: Boolean,
    ): PromptConflictSeverity {
        if (left == MarkerStrength.STRONG && right == MarkerStrength.STRONG) {
            return PromptConflictSeverity.HIGH
        }
        if (direct && (left == MarkerStrength.STRONG || right == MarkerStrength.STRONG)) {
            return PromptConflictSeverity.HIGH
        }
        if (left == MarkerStrength.STRONG || right == MarkerStrength.STRONG) {
            return PromptConflictSeverity.MEDIUM
        }
        return if (direct) PromptConflictSeverity.MEDIUM else PromptConflictSeverity.LOW
    }

    // -----------------------------------------------------------------------
    // 行为主题（conflict.ts:367-440）
    // -----------------------------------------------------------------------

    val BEHAVIOR_THEMES: List<BehaviorTheme> = listOf(
        BehaviorTheme(
            "tool_use",
            "工具调用",
            listOf("工具", "tool", "function calling", "函数调用", "执行命令", "调用命令"),
        ),
        BehaviorTheme("apology", "道歉认错", listOf("道歉", "认错", "apologize", "apologise", "say sorry")),
        BehaviorTheme("refusal", "拒绝请求", listOf("拒绝", "refuse", "decline")),
        BehaviorTheme(
            "disclaimer",
            "免责声明与风险提示",
            listOf("免责声明", "免责", "安全警告", "风险提示", "disclaimer", "safety warning"),
        ),
        BehaviorTheme(
            "verbosity",
            "回答详略",
            listOf("简洁", "简短", "啰嗦", "罗嗦", "详细", "详尽", "concise", "brief", "verbose", "elaborate", "detailed"),
        ),
        BehaviorTheme("memory", "记忆读写", listOf("记忆", "记住", "memory")),
        BehaviorTheme("emoji", "表情符号", listOf("emoji", "表情符号", "颜文字")),
        BehaviorTheme("code_comment", "代码注释", listOf("代码注释", "code comment")),
        BehaviorTheme("citation", "引用来源", listOf("引用来源", "标注来源", "citation", "cite sources")),
        BehaviorTheme("roleplay", "角色扮演 / 出戏", listOf("角色扮演", "出戏", "roleplay", "role-play")),
        BehaviorTheme("plan_first", "先规划再执行", listOf("先规划", "先列计划", "制定计划", "plan first")),
        BehaviorTheme(
            "ask_clarify",
            "反问澄清",
            listOf("反问", "澄清问题", "向用户确认", "clarifying question"),
        ),
    )

    // -----------------------------------------------------------------------
    // 身份断言（conflict.ts:577-634）
    // -----------------------------------------------------------------------

    /**
     * 13 条身份正则。英文只认首字母大写，中文最多 16 字——这是刻意的保守边界：
     * 放宽会把「你是用什么工具」这类普通句子认成身份断言（conflict.ts:128 的已知局限）。
     */
    val IDENTITY_PATTERNS: List<IdentityPattern> = listOf(
        IdentityPattern(
            Regex("""\b[Yy]ou are\s+(?:an?\s+|the\s+)?([A-Z][A-Za-z0-9_.-]*(?:\s+[A-Z][A-Za-z0-9_.-]*){0,2})"""),
            IdentityPolarity.POSITIVE,
        ),
        IdentityPattern(
            Regex("""\b[Yy]ou are not\s+(?:an?\s+|the\s+)?([A-Z][A-Za-z0-9_.-]*(?:\s+[A-Z][A-Za-z0-9_.-]*){0,2})"""),
            IdentityPolarity.NEGATIVE,
        ),
        IdentityPattern(
            Regex("""\b[Dd]o not (?:call yourself|claim to be|pretend to be|act as)\s+([A-Z][A-Za-z0-9_.-]*)"""),
            IdentityPolarity.NEGATIVE,
        ),
        IdentityPattern(
            Regex("""你就是\s*([^\s，。,.！!？?、；;：:的]{1,16})"""),
            IdentityPolarity.POSITIVE,
        ),
        IdentityPattern(
            Regex("""你是\s*(?!不|没|并非)([^\s，。,.！!？?、；;：:的]{1,16})"""),
            IdentityPolarity.POSITIVE,
        ),
        IdentityPattern(
            Regex("""你叫\s*([^\s，。,.！!？?、；;：:的]{1,16})"""),
            IdentityPolarity.POSITIVE,
        ),
        IdentityPattern(
            Regex("""你的名字(?:是|叫)\s*([^\s，。,.！!？?、；;：:的]{1,16})"""),
            IdentityPolarity.POSITIVE,
        ),
        IdentityPattern(
            Regex("""你的身份(?:是|叫)\s*([^\s，。,.！!？?、；;：:的]{1,16})"""),
            IdentityPolarity.POSITIVE,
        ),
        IdentityPattern(
            Regex("""你不是\s*([^\s，。,.！!？?、；;：:的]{1,16})"""),
            IdentityPolarity.NEGATIVE,
        ),
        IdentityPattern(
            Regex("""^(?:你)?不是\s*([A-Z][A-Za-z0-9_.-]*(?:\s+[A-Z][A-Za-z0-9_.-]*){0,2})"""),
            IdentityPolarity.NEGATIVE,
        ),
        IdentityPattern(
            Regex("""你不叫\s*([^\s，。,.！!？?、；;：:的]{1,16})"""),
            IdentityPolarity.NEGATIVE,
        ),
        IdentityPattern(
            Regex("""(?:不要|不许|不准|严禁|禁止|别)(?:再)?自称(?:为|是)?\s*([^\s，。,.！!？?、；;：:的]{1,16})"""),
            IdentityPolarity.NEGATIVE,
        ),
        IdentityPattern(
            Regex("""(?:不要|别)(?:再)?扮演\s*([^\s，。,.！!？?、；;：:的]{1,16})"""),
            IdentityPolarity.NEGATIVE,
        ),
    )

    /**
     * 泛化身份名直接丢弃：几乎每份提示词都会写「你是一个 AI 助手」，
     * 把它当身份断言会让 identity 冲突刷屏（conflict.ts:611-634）。
     */
    val GENERIC_IDENTITY_NAMES: Set<String> = setOf(
        "ai",
        "assistant",
        "ai assistant",
        "intelligent ai assistant",
        "helpful assistant",
        "人工智能",
        "助手",
        "智能助手",
        "通用助手",
        "语言模型",
        "大语言模型",
        "大模型",
        "模型",
        "机器人",
        "bot",
        "chatbot",
        "system",
        "程序",
        "system prompt",
        "device assistant",
        "on-device ai assistant",
        "device ai assistant",
    )

    private val LEADING_QUOTE_PATTERN: Regex = Regex("""^[\s"'“”‘’「」『』]+""")
    private val TRAILING_JUNK_PATTERN: Regex = Regex("""[\s"'“”‘’「」『』。，,、；;：:！!？?]+\z""")
    private val COUNTING_PREFIX_PATTERN: Regex = Regex("""^(?:一个|一名|一位|个)""")
    private val ARTICLE_PREFIX_PATTERN: Regex = Regex("""^(?:a|an|the)\s+""", RegexOption.IGNORE_CASE)
    private val IDENTITY_NAME_PUNCTUATION: Regex = Regex("""[，。,.!?！？]""")
    private val IDENTITY_NAME_SEPARATORS: Regex = Regex("""[^\p{L}\p{N}]""")

    fun cleanIdentityName(raw: String): String? {
        var name = raw.trim()
        name = LEADING_QUOTE_PATTERN.replace(name, "")
        name = TRAILING_JUNK_PATTERN.replace(name, "")
        if (name.isEmpty()) return null
        if (name.length > 24) return null
        if (COUNTING_PREFIX_PATTERN.containsMatchIn(name)) return null
        if (ARTICLE_PREFIX_PATTERN.containsMatchIn(name)) return null
        if (GENERIC_IDENTITY_NAMES.contains(name.lowercase())) return null
        if (IDENTITY_NAME_PUNCTUATION.containsMatchIn(name)) return null
        return name
    }

    /** 名字归一：大小写与标点不参与比较，「乔乔」与「乔乔！」视为同名。 */
    fun normalizeIdentityName(name: String): String =
        IDENTITY_NAME_SEPARATORS.replace(name.lowercase(), "")

    // -----------------------------------------------------------------------
    // 语言（conflict.ts:795-820）
    // -----------------------------------------------------------------------

    /**
     * 只覆盖 zh/en/ja/ko。Operit 支持 10 种语言，但提示词只有中英两套，
     * 其余语言识别不到就不报——保持保守比乱认语言安全（docs/51 6.3c）。
     */
    val LANGUAGES: List<LanguageSpec> = listOf(
        LanguageSpec("zh", "中文", listOf("中文", "汉语", "简体中文", "繁体中文", "普通话")),
        LanguageSpec("en", "英文", listOf("英文", "英语", "english")),
        LanguageSpec("ja", "日文", listOf("日文", "日语", "japanese")),
        LanguageSpec("ko", "韩文", listOf("韩文", "韩语", "korean")),
    )

    private val PERMISSIVE_LANGUAGE_PATTERN: Regex = Regex(
        """都可以|均可|都行|不限|随意|任意|either|any language""",
        RegexOption.IGNORE_CASE,
    )

    private val LANGUAGE_REQUIRE_BEFORE_PATTERN: Regex = Regex(
        """(?:用|以|说|讲|使用|采用|in|into|using|use)\s*\z""",
        RegexOption.IGNORE_CASE,
    )

    private val LANGUAGE_REQUIRE_AFTER_PATTERN: Regex = Regex(
        """^\s*(?:回答|回复|作答|交流|reply|respond|answer)""",
        RegexOption.IGNORE_CASE,
    )

    fun isPermissiveLanguageClause(clause: String): Boolean =
        PERMISSIVE_LANGUAGE_PATTERN.containsMatchIn(clause)

    /**
     * 没有情态词时，看语言词前后有没有「用/以/说」或「回答/回复」，
     * 这是「in English」「用中文回答」这类无标记句的唯一识别手段（conflict.ts:814-820）。
     * 用 \z 而不是 $：分句已经 trim，\z 精确对应「输入结尾」，不会被结尾换行影响。
     */
    fun inferLanguageStrength(clause: String, index: Int, end: Int): MarkerStrength? {
        val before = clause.substring(maxOf(0, index - 16), index)
        val after = clause.substring(end, minOf(clause.length, end + 16))
        if (LANGUAGE_REQUIRE_BEFORE_PATTERN.containsMatchIn(before)) return MarkerStrength.SOFT
        if (LANGUAGE_REQUIRE_AFTER_PATTERN.containsMatchIn(after)) return MarkerStrength.SOFT
        return null
    }

    // -----------------------------------------------------------------------
    // 输出格式（conflict.ts:981-1022）
    // -----------------------------------------------------------------------

    val FORMATS: List<FormatSpec> = listOf(
        FormatSpec("plaintext", "纯文本", listOf("纯文本", "plain text", "自然段", "散文", "prose")),
        FormatSpec("markdown", "Markdown", listOf("markdown")),
        FormatSpec("json", "JSON", listOf("json")),
        FormatSpec("yaml", "YAML", listOf("yaml", "yml")),
        FormatSpec("xml", "XML", listOf("xml")),
        FormatSpec("html", "HTML", listOf("html")),
        FormatSpec("table", "表格", listOf("表格", "table")),
        FormatSpec(
            "list",
            "列表 / 分点",
            listOf("列表", "分点", "项目符号", "bullet point", "bullet list", "bulleted list"),
        ),
        FormatSpec(
            "sections",
            "分节结构",
            listOf(
                "分节",
                "章节",
                "标题",
                "结构",
                "section",
                "heading",
                "summary",
                "details",
                "next steps",
                "摘要",
                "详情",
                "下一步",
            ),
        ),
        FormatSpec("code_block", "代码块", listOf("代码块", "code block")),
    )

    /**
     * 顶层格式：两种顶层格式同时被要求时模型只能二选一。
     * 表格/列表/分节属于「富格式内部结构」，可以共存，因此不在这个集合里。
     */
    val TOP_LEVEL_FORMATS: Set<String> = setOf("plaintext", "markdown", "json", "yaml", "xml", "html")

    private val PERMISSIVE_FORMAT_PATTERN: Regex = Regex(
        """任意格式|不限格式|任何格式|either format|any format""",
        RegexOption.IGNORE_CASE,
    )

    private val FORMAT_REQUIRE_BEFORE_PATTERN: Regex = Regex(
        """(?:用|以|采用|格式(?:为|是)|输出(?:为|成)|answer\s+(?:in|with)|respond\s+(?:in|with)|reply\s+(?:in|with)|format(?:ted)?\s+as|use)\s*\z""",
        RegexOption.IGNORE_CASE,
    )

    private val FORMAT_REQUIRE_AFTER_PATTERN: Regex = Regex(
        """^\s*(?:格式|形式|回答|回复|输出)""",
    )

    fun isPermissiveFormatClause(clause: String): Boolean =
        PERMISSIVE_FORMAT_PATTERN.containsMatchIn(clause)

    fun inferFormatStrength(clause: String, index: Int, end: Int): MarkerStrength? {
        val before = clause.substring(maxOf(0, index - 28), index)
        val after = clause.substring(end, minOf(clause.length, end + 16))
        if (FORMAT_REQUIRE_BEFORE_PATTERN.containsMatchIn(before)) return MarkerStrength.SOFT
        if (FORMAT_REQUIRE_AFTER_PATTERN.containsMatchIn(after)) return MarkerStrength.SOFT
        return null
    }
}

