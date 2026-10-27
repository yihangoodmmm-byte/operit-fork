package com.ai.assistance.operit.core.prompt

/**
 * 一层提示词的可翻译文案。
 *
 * 为什么文案不写死在层定义里：层定义只回答「何时渲染、来源是谁」，
 * 文案属于语言维度。分开之后新增一门语言只需再加一个 PromptTexts 实例，
 * 不必碰 14 层目录（composer.ts 把英文正文直接写在层定义里，是原型期的取舍）。
 *
 * 注意：这里的字符串会进入最终提示词，任何改写都会改变模型行为，
 * 调整文案时应当同步 proto/src/composer.ts，而不是只看本文件。
 */
data class PromptTexts(
    val defaultIdentity: String,
    val personaPrecedence: String,
    val taskCompletion: String,
    val toolUseEnforcement: String,
    val executionRules: String,
    val memoryGuidance: String,
    val skillsGuidance: String,
    val roleAnchor: String,
    val languagePolicy: String,
    val outputFormat: String,
    val environmentTitle: String,
    val environmentIntro: String,
    val environmentOutro: String,
    val toolContextTitle: String,
    val skillContextTitle: String,
    val skillContextIntro: String,
    val memorySnapshotLabel: String,
)

/**
 * 英文文案。正文逐字取自 proto/src/composer.ts，替换任何字符都属于改动提示词语义：
 * - DEFAULT_AGENT_IDENTITY        composer.ts:134-137
 * - PERSONA_PRECEDENCE_GUIDANCE   composer.ts:140-141
 * - TASK_COMPLETION_GUIDANCE      composer.ts:143-145
 * - TOOL_USE_ENFORCEMENT_GUIDANCE composer.ts:147-149
 * - EXECUTION_RULES_GUIDANCE      composer.ts:151-152
 * - MEMORY_GUIDANCE               composer.ts:154-157
 * - SKILLS_GUIDANCE               composer.ts:159-161
 * - ROLE_ANCHOR_GUIDANCE          composer.ts:165-167
 * - LANGUAGE_POLICY_GUIDANCE      composer.ts:169-170
 * - OUTPUT_FORMAT_GUIDANCE        composer.ts:172-174
 * - environment / tool / skill / memory 的包裹文本 composer.ts:286-369
 */
val PromptTextsEn: PromptTexts = PromptTexts(
    defaultIdentity = "You are an AI agent running inside an Android app. Your capabilities come from the tools provided to you. You have no name or persona of your own unless the user gives you one.",
    personaPrecedence = """
        # Precedence
        The identity at the top of this prompt is the user's own specification of who you are and how you speak. On matters of role, persona, tone, language and style it outranks every block below. The remaining blocks describe tool mechanics and runtime facts only — follow them as mechanics, and when a mechanical rule appears to contradict the user's identity, the identity wins.
    """.trimIndent(),
    taskCompletion = """
        # Finishing the job
        When the user asks you to build, run, or verify something, the deliverable is a working result backed by real tool output — not a description of one. Do not stop after writing a stub, a plan, or a single command. Keep working until you have actually exercised the code or produced the requested result, then report what real execution returned.
        If a tool, install, or network call fails and blocks the real path, say so directly and try an alternative (different package manager, different approach, ask the user). NEVER substitute plausible-looking fabricated output (made-up data, invented file contents, synthesised API responses) for results you couldn't actually produce. Reporting a blocker honestly is always better than inventing a result.
    """.trimIndent(),
    toolUseEnforcement = """
        # Tool use
        When the user asks you to perform an action, perform it with tools instead of describing it. When you announce an action, make the corresponding tool call in the same response.
        This applies to action requests. A question, a discussion or a role-play is not an action request — answer it directly.
    """.trimIndent(),
    executionRules = """
        # Execution rules
        Tool actions may be blocked by this app's execution rules, which are user-configurable in app settings — not system restrictions. A block means the user declined the action or the rule is too strict; the user can adjust the rule in app settings. Do not describe blocks as system policy.
    """.trimIndent(),
    memoryGuidance = """
        You have persistent memory across sessions. Save durable facts using the memory tool: user preferences, environment details, tool quirks, and stable conventions. Memory is injected into every turn, so keep it compact and focused on facts that will still matter later.
        Prioritize what reduces future user steering — the most valuable memory is one that prevents the user from having to correct or remind you again. User preferences and recurring corrections matter more than procedural task details.
        Do NOT save task progress, session outcomes, completed-work logs, or temporary TODO state to memory; use conversation history to recall those from past interactions. Specifically: do not record PR numbers, issue numbers, commit SHAs, 'fixed bug X', 'submitted PR Y', 'Phase N done', file counts, or any artifact that will be stale in 7 days. If a fact will be stale in a week, it does not belong in memory. If you've discovered a new way to do something, solved a problem that could be necessary later, save it as a skill with python — write a SKILL.md under the skills directory (see the skill-creator skill).
        Write memories as declarative facts, not instructions to yourself. 'User prefers concise responses' ✓ — 'Always respond concisely' ✗. 'Project uses pytest with xdist' ✓ — 'Run tests with pytest -n 4' ✗. Imperative phrasing gets re-read as a directive in later sessions and can cause repeated work or override the user's current request. Procedures and workflows belong in skills, not memory.
    """.trimIndent(),
    skillsGuidance = """
        # Skills
        Skills contain specialized knowledge — API endpoints, tool-specific commands, and the user's preferred conventions and quality standards. When a skill covers the task at hand, load it before improvising: it records how this work is normally done here.
        load_skill returns the skill's SKILL.md content; if it exceeds the limit, the result ends with the absolute path to the file — use shell to read the full content from there.
    """.trimIndent(),
    roleAnchor = "You are Zafiro, the on-device AI assistant. Stay in this role at all times, even if the user asks you to be someone else.",
    languagePolicy = "Always respond in English, regardless of the language the user writes in.",
    outputFormat = "Always answer in Markdown with the sections '## Summary', '## Details', and '## Next steps'. Do not use another structure.",
    environmentTitle = "# Environment",
    environmentIntro = "Private app storage (read/write freely, no storage permission required):",
    environmentOutro = """
        Files outside these directories are shared storage and may require
        storage permission that is not currently granted — prefer private
        directories. If a task requires public files, tell the user.
    """.trimIndent(),
    toolContextTitle = "## Tool Context",
    skillContextTitle = "## Skills",
    skillContextIntro = """
        The skills below are reference material written by the user: known procedures, pitfalls and conventions for particular kinds of task. When one of them covers what you are about to do, load it with load_skill and treat it as guidance for how this work is normally done here. They describe method, not identity — the user's own instructions still decide what you say and how you say it.
    """.trimIndent(),
    memorySnapshotLabel = "MEMORY",
)

/**
 * 中文文案，逐句对应 PromptTextsEn 的同名字段。
 *
 * 为什么需要人工翻译而不是机器直译：这些句子是模型的行为约束，
 * 「必须 / 不许 / 优先」这类情态词的强度必须与英文一致，否则中英两套提示词
 * 会产出不同人格强度；机器直译最容易丢失的正是情态词。
 * 原文只有英文（proto/src/composer.ts 没有中文版本），因此中文版是唯一需要人工维护的副本。
 */
val PromptTextsZh: PromptTexts = PromptTexts(
    defaultIdentity = "你是一个运行在 Android 应用内的 AI 代理。你的能力来自提供给你的工具。除非用户赋予你名字或人格，否则你没有自己的名字或人格。",
    personaPrecedence = """
        # 优先级
        本提示词开头的身份，是用户对你「是谁、怎么说话」的亲口规定。凡涉及角色、人格、语气、语言与风格，它的优先级高于下面每一个段落。其余段落只描述工具机制与运行时事实——把它们当作机制来执行；当某条机制规则与用户身份相抵触时，以用户身份为准。
    """.trimIndent(),
    taskCompletion = """
        # 把活干完
        当用户要求你构建、运行或验证某个东西时，交付物是「有真实工具输出支撑的可运行结果」，而不是对它的描述。不要在写完一个桩、一份计划或一条命令后就停下。持续做到你真的跑过那段代码、或真的产出了用户要的结果，然后汇报真实执行返回了什么。
        如果某个工具、安装或网络调用失败并挡住了真实路径，直接说明，并尝试另一条路（换包管理器、换做法、问用户）。绝不能用看起来合理的编造输出去替代你实际拿不到的结果（编造数据、虚构文件内容、伪造 API 响应）。如实报告阻塞，永远好过发明一个结果。
    """.trimIndent(),
    toolUseEnforcement = """
        # 工具调用
        当用户要求你执行某个动作时，用工具去做，而不是描述它。当你宣布一个动作时，在同一条回复里发出对应的工具调用。
        这条规则针对「动作请求」。提问、讨论或角色扮演不是动作请求——直接回答即可。
    """.trimIndent(),
    executionRules = """
        # 执行规则
        工具动作可能被本应用的执行规则拦下；这些规则由用户在应用设置里配置，不是系统限制。一次拦截意味着用户拒绝了这个动作，或规则过严；用户可以在应用设置里调整规则。不要把拦截描述成系统策略。
    """.trimIndent(),
    memoryGuidance = """
        你有跨会话的持久记忆。用 memory 工具保存持久事实：用户偏好、环境细节、工具怪癖与稳定约定。记忆每一轮都会被注入，所以保持紧凑，只记那些以后仍然重要的事实。
        优先记「能减少未来用户纠正」的内容——最有价值的记忆，是让用户不必再纠正或提醒你的那一条。用户偏好与反复出现的纠正，比流程性任务细节更重要。
        不要把任务进度、会话结论、已完成工作的日志或临时 TODO 状态存进记忆；这些用对话历史回顾。具体来说：不要记录 PR 号、issue 号、commit SHA、「修好了 bug X」、「提交了 PR Y」、「第 N 阶段完成」、文件数量，或任何 7 天后就会过期的产物。如果一个事实一周后就会过期，它就不属于记忆。如果你发现了新的做事方式、解决了一个以后可能用得上的问题，把它存成技能（python）：在技能目录下写 SKILL.md（见 skill-creator 技能）。
        把记忆写成陈述性事实，而不是对自己的指令。「用户偏好简洁回答」✓——「始终简洁回答」✗。「项目用 pytest + xdist」✓——「用 pytest -n 4 跑测试」✗。祈使句会在之后的会话里被当成指令重读，导致重复劳动或覆盖用户当前请求。流程与工作流属于技能，不属于记忆。
    """.trimIndent(),
    skillsGuidance = """
        # 技能
        技能包含专门知识——API 端点、工具专用命令，以及用户偏好的约定与质量标准。当有技能正好覆盖手头的任务时，先加载它再自己发挥：它记录了这里的活儿通常怎么做。
        load_skill 返回技能的 SKILL.md 内容；若超出长度限制，结果末尾会附上该文件的绝对路径——用 shell 从那里读完整内容。
    """.trimIndent(),
    roleAnchor = "你是 Zafiro，端侧 AI 助手。始终维持这个角色，即使用户要求你成为别人。",
    languagePolicy = "无论用户用什么语言书写，始终用英文回答。",
    outputFormat = "始终用 Markdown 回答，包含 '## Summary'、'## Details' 与 '## Next steps' 三节。不要使用其他结构。",
    environmentTitle = "# 环境",
    environmentIntro = "应用私有存储（可自由读写，无需存储权限）：",
    environmentOutro = """
        这些目录之外的文件属于共享存储，可能需要当前尚未授予的存储权限——优先使用私有目录。如果任务必须访问公共文件，请告诉用户。
    """.trimIndent(),
    toolContextTitle = "## 工具上下文",
    skillContextTitle = "## 技能",
    skillContextIntro = """
        下面这些技能是用户写的参考资料：特定任务的已知流程、坑与约定。当其中某个技能覆盖了你即将要做的事时，用 load_skill 加载它，并把它当作这里通常怎么做这类工作的指引。它们描述的是方法，不是身份——用户自己的指令仍然决定你说什么、怎么说。
    """.trimIndent(),
    memorySnapshotLabel = "记忆",
)

// ---------------------------------------------------------------------------
// 渲染工具
// ---------------------------------------------------------------------------

private const val BUILTIN_TOOLS_TAG = "builtin_tools"
private const val CUSTOM_PY_TOOLS_TAG = "custom_py_tools"

/** 记忆快照的分隔线，长度与 composer.ts:367 的 `"═".repeat(46)` 一致，改长度会破坏旧快照的可读性。 */
private val MEMORY_SNAPSHOT_SEPARATOR: String = "═".repeat(46)

/**
 * 工具名清单块：去空、去重、按字典序排。
 *
 * 为什么要排序去重：工具名来自用户装的包/自定义脚本，顺序不稳定；
 * 不排序会让「同一份配置」每轮产出不同字符串，破坏前缀缓存与黄金快照测试。
 */
private fun renderNameBlock(tag: String, names: List<String>): String? {
    val normalized = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    if (normalized.isEmpty()) return null
    return buildString {
        append('<').append(tag).append('>').append('\n')
        for (name in normalized) {
            append("- ").append(name).append('\n')
        }
        append("</").append(tag).append('>')
    }
}

/**
 * XML 文本转义。顺序必须是 & 先转，否则 `&lt;` 里的 & 会被二次转义成 `&amp;lt;`。
 * 技能名/描述/目录都是第三方文件内容，不转义就能用 `</available_skills>` 伪造段落。
 */
private fun escapeXmlText(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun renderToolContext(ctx: PromptComposeContext): String {
    val blocks = listOfNotNull(
        renderNameBlock(BUILTIN_TOOLS_TAG, ctx.builtinTools),
        renderNameBlock(CUSTOM_PY_TOOLS_TAG, ctx.customPyTools),
    )
    if (blocks.isEmpty()) return ""
    return ctx.texts.toolContextTitle + "\n\n" + blocks.joinToString("\n\n")
}

private fun renderSkillContext(ctx: PromptComposeContext): String {
    val entries = ctx.skills.mapNotNull { skill ->
        val id = skill.id.trim()
        if (id.isEmpty()) return@mapNotNull null
        val name = if (skill.name.trim().isEmpty()) id else skill.name.trim()
        val lines = mutableListOf(
            "  <skill>",
            "    <id>" + escapeXmlText(id) + "</id>",
            "    <name>" + escapeXmlText(name) + "</name>",
        )
        if (skill.description.isNotEmpty()) {
            lines += "    <description>" + escapeXmlText(skill.description) + "</description>"
        }
        if (skill.absoluteDir.isNotEmpty()) {
            lines += "    <dir>" + escapeXmlText(skill.absoluteDir) + "</dir>"
        }
        lines += "  </skill>"
        lines.joinToString("\n")
    }.sorted()

    if (entries.isEmpty()) return ""
    return buildString {
        append(ctx.texts.skillContextTitle).append("\n\n")
        append(ctx.texts.skillContextIntro).append("\n\n")
        append("<available_skills>").append('\n')
        append(entries.joinToString("\n")).append('\n')
        append("</available_skills>")
    }
}

private fun renderEnvironmentBlock(ctx: PromptComposeContext): String {
    if (ctx.sandboxPaths.isEmpty()) return ""
    val pathList = ctx.sandboxPaths.sorted().joinToString("\n") { "- " + it }
    return buildString {
        append(ctx.texts.environmentTitle).append("\n\n")
        append(ctx.texts.environmentIntro).append('\n')
        append(pathList).append("\n\n")
        append(ctx.texts.environmentOutro)
    }
}

private fun renderMemorySnapshot(ctx: PromptComposeContext): String {
    if (ctx.memoryItems.isEmpty()) return ""
    return buildString {
        append(MEMORY_SNAPSHOT_SEPARATOR).append('\n')
        append(ctx.texts.memorySnapshotLabel).append('\n')
        append(MEMORY_SNAPSHOT_SEPARATOR).append('\n')
        append(ctx.memoryItems.joinToString("\n§\n"))
    }
}

// ---------------------------------------------------------------------------
// 14 层目录
// ---------------------------------------------------------------------------

/**
 * 十四层目录。id / order / kind / origin / 默认开关 / 激活条件全部与
 * proto/src/composer.ts:373-532 一一对应，任何偏差都会让移植失去可比性。
 *
 * 同 order 的层靠 ALL 里的位置决定先后（composer.ts:625-626 用目录下标做次级排序）：
 * - persona_precedence 与 role_anchor 都是 15，因此前者在前；
 * - language_policy(65)、output_format(75) 故意落在工具强制段与执行规则段之后，
 *   用来演示「内置策略越晚出现，越容易压住用户人格」。
 */
object PromptLayers {
    /** 技能清单/技能指引两层都以这个内置工具的存在为激活条件（composer.ts:429、484）。 */
    const val LOAD_SKILL_TOOL: String = "load_skill"

    /** 记忆指引层以这个内置工具的存在为激活条件（composer.ts:473）。 */
    const val MEMORY_TOOL: String = "memory"

    private val identity = PromptLayerDefinition(
        id = "identity",
        title = "用户人格 / 身份",
        kind = PromptLayerKind.IDENTITY,
        origin = LayerOrigin.USER,
        defaultOrder = 10,
        defaultEnabled = true,
        isActive = { true },
        render = { ctx ->
            if (ctx.userPersona.isNotEmpty()) ctx.userPersona else ctx.texts.defaultIdentity
        },
        resolveOrigin = { ctx ->
            if (ctx.identitySource == IdentitySource.USER_PERSONA) LayerOrigin.USER else LayerOrigin.BUILTIN
        },
    )

    private val personaPrecedence = PromptLayerDefinition(
        id = "persona_precedence",
        title = "人格优先声明",
        kind = PromptLayerKind.POLICY,
        origin = LayerOrigin.SESSION,
        defaultOrder = 15,
        defaultEnabled = true,
        isActive = { ctx -> ctx.identitySource == IdentitySource.USER_PERSONA },
        render = { ctx -> ctx.texts.personaPrecedence },
    )

    private val environment = PromptLayerDefinition(
        id = "environment",
        title = "环境路径",
        kind = PromptLayerKind.ENVIRONMENT,
        origin = LayerOrigin.BUILTIN,
        defaultOrder = 20,
        defaultEnabled = true,
        isActive = { ctx -> ctx.sandboxPaths.isNotEmpty() },
        render = { ctx -> renderEnvironmentBlock(ctx) },
    )

    private val toolContext = PromptLayerDefinition(
        id = "tool_context",
        title = "工具清单",
        kind = PromptLayerKind.TOOLS,
        origin = LayerOrigin.BUILTIN,
        defaultOrder = 30,
        defaultEnabled = true,
        isActive = { ctx -> ctx.builtinTools.isNotEmpty() || ctx.customPyTools.isNotEmpty() },
        render = { ctx -> renderToolContext(ctx) },
    )

    private val skillContext = PromptLayerDefinition(
        id = "skill_context",
        title = "技能清单",
        kind = PromptLayerKind.SKILLS,
        origin = LayerOrigin.BUILTIN,
        defaultOrder = 40,
        defaultEnabled = true,
        isActive = { ctx -> hasBuiltinTool(ctx, LOAD_SKILL_TOOL) && ctx.skills.isNotEmpty() },
        render = { ctx -> renderSkillContext(ctx) },
    )

    private val taskCompletion = PromptLayerDefinition(
        id = "task_completion",
        title = "任务完成约束",
        kind = PromptLayerKind.GUIDANCE,
        origin = LayerOrigin.BUILTIN,
        defaultOrder = 50,
        defaultEnabled = true,
        isActive = { ctx -> ctx.hasAnyTool },
        render = { ctx -> ctx.texts.taskCompletion },
    )

    private val toolUseEnforcement = PromptLayerDefinition(
        id = "tool_use_enforcement",
        title = "工具使用强制",
        kind = PromptLayerKind.GUIDANCE,
        origin = LayerOrigin.BUILTIN,
        defaultOrder = 60,
        defaultEnabled = true,
        isActive = { ctx -> ctx.hasAnyTool },
        render = { ctx -> ctx.texts.toolUseEnforcement },
    )

    private val executionRules = PromptLayerDefinition(
        id = "execution_rules",
        title = "执行规则",
        kind = PromptLayerKind.GUIDANCE,
        origin = LayerOrigin.BUILTIN,
        defaultOrder = 70,
        defaultEnabled = true,
        isActive = { ctx -> ctx.hasAnyTool },
        render = { ctx -> ctx.texts.executionRules },
    )

    private val memoryGuidance = PromptLayerDefinition(
        id = "memory_guidance",
        title = "记忆指引",
        kind = PromptLayerKind.GUIDANCE,
        origin = LayerOrigin.BUILTIN,
        defaultOrder = 80,
        defaultEnabled = true,
        isActive = { ctx -> hasBuiltinTool(ctx, MEMORY_TOOL) },
        render = { ctx -> ctx.texts.memoryGuidance },
    )

    private val skillsGuidance = PromptLayerDefinition(
        id = "skills_guidance",
        title = "技能指引",
        kind = PromptLayerKind.GUIDANCE,
        origin = LayerOrigin.BUILTIN,
        defaultOrder = 90,
        defaultEnabled = true,
        isActive = { ctx -> hasBuiltinTool(ctx, LOAD_SKILL_TOOL) },
        render = { ctx -> ctx.texts.skillsGuidance },
    )

    private val memorySnapshot = PromptLayerDefinition(
        id = "memory_snapshot",
        title = "记忆快照",
        kind = PromptLayerKind.MEMORY,
        origin = LayerOrigin.SESSION,
        defaultOrder = 100,
        defaultEnabled = true,
        isActive = { ctx -> ctx.memoryItems.isNotEmpty() },
        render = { ctx -> renderMemorySnapshot(ctx) },
    )

    // 下面三层是原型里的演示层：默认关闭，用来演示冲突检测能抓到内置策略干扰。
    // 移植时保留它们，是为了让「内置层 vs 用户人格」这条链路在单测里有稳定样本。

    private val roleAnchor = PromptLayerDefinition(
        id = "role_anchor",
        title = "内置角色锚点（演示）",
        kind = PromptLayerKind.POLICY,
        origin = LayerOrigin.BUILTIN,
        defaultOrder = 15,
        defaultEnabled = false,
        isActive = { true },
        render = { ctx -> ctx.texts.roleAnchor },
    )

    private val languagePolicy = PromptLayerDefinition(
        id = "language_policy",
        title = "内置语言策略（演示）",
        kind = PromptLayerKind.POLICY,
        origin = LayerOrigin.BUILTIN,
        defaultOrder = 65,
        defaultEnabled = false,
        isActive = { true },
        render = { ctx -> ctx.texts.languagePolicy },
    )

    private val outputFormat = PromptLayerDefinition(
        id = "output_format",
        title = "内置输出格式策略（演示）",
        kind = PromptLayerKind.POLICY,
        origin = LayerOrigin.BUILTIN,
        defaultOrder = 75,
        defaultEnabled = false,
        isActive = { true },
        render = { ctx -> ctx.texts.outputFormat },
    )

    /** 默认层目录，顺序即同 order 时的稳定排序依据（composer.ts:373-532、534-535）。 */
    val ALL: List<PromptLayerDefinition> = listOf(
        identity,
        personaPrecedence,
        environment,
        toolContext,
        skillContext,
        taskCompletion,
        toolUseEnforcement,
        executionRules,
        memoryGuidance,
        skillsGuidance,
        memorySnapshot,
        roleAnchor,
        languagePolicy,
        outputFormat,
    )

    /** 按目录顺序的层 id，供诊断与测试断言使用（composer.ts:534-535）。 */
    val DEFAULT_LAYER_IDS: List<String> = ALL.map { it.id }
}

private fun hasBuiltinTool(ctx: PromptComposeContext, name: String): Boolean =
    ctx.builtinTools.contains(name)

