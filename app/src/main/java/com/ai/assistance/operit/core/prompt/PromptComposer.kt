package com.ai.assistance.operit.core.prompt

/**
 * 组装输入。全部字段不可变；引擎不读偏好、不读文件、不发网络、不写日志。
 *
 * 为什么工具名分成 builtin / customPy / mcp 三组：Tool Context 只渲染前两组，
 * 而 MCP 服务器只影响「有没有工具」的判定（composer.ts:47-49、280-282）。
 * 合成一个 List 会丢掉这个区别，guidance 层的激活条件就会失真。
 */
data class PromptComposeContext(
    /** 输出语言，决定文案包。 */
    val useEnglish: Boolean = false,
    /** 用户写的系统级人格；空串表示没有，身份层取内置默认身份。 */
    val userPersona: String = "",
    val builtinTools: List<String> = emptyList(),
    val customPyTools: List<String> = emptyList(),
    /** 启用中的 MCP 服务器名。只参与「有没有工具」判定，不进 Tool Context 渲染。 */
    val mcpServers: List<String> = emptyList(),
    val skills: List<PromptSkill> = emptyList(),
    /** 应用私有可写目录；非空时 environment 层才激活。 */
    val sandboxPaths: List<String> = emptyList(),
    /** 记忆快照条目，保持调用方给的顺序。 */
    val memoryItems: List<String> = emptyList(),
    /**
     * 宿主每轮现场生成的内容（工作区指南、包列表、mood、waifu、proxy 角色、用户档案……）。
     * 它们与 14 层共用同一套开关、排序与冲突检测，但内容由宿主提供——
     * 引擎不生产内容，只决定什么内容、以什么顺序、在什么开关状态进入提示词
     * （docs/51-Operit提示词引擎移植.md 5.3）。
     */
    val hostExtras: List<PromptExtraLayer> = emptyList(),
    /** 模板占位符替换值，键见 PromptPlaceholders。 */
    val placeholders: Map<String, String> = emptyMap(),
    /** 文案包。与层目录解耦，新增语言不必改 14 层定义。 */
    val texts: PromptTexts = if (useEnglish) PromptTextsEn else PromptTextsZh,
) {
    /** composer.ts:280-282：任一来源有工具就算「有工具」。 */
    val hasAnyTool: Boolean
        get() = builtinTools.isNotEmpty() || customPyTools.isNotEmpty() || mcpServers.isNotEmpty()

    /** 身份文本来源，供诊断与冲突检测使用。 */
    val identitySource: IdentitySource
        get() = if (userPersona.isNotEmpty()) IdentitySource.USER_PERSONA else IdentitySource.DEFAULT
}

/**
 * 组装结果。保留逐层明细而不是只返回字符串，是因为设置页要展示「哪层被关了、哪层吃 token」，
 * 冲突检测也需要层对象；只留 finalPrompt 会逼调用方重新解析字符串。
 */
data class PromptComposeResult(
    val finalPrompt: String,
    val layers: List<PromptLayer>,
    val identitySource: IdentitySource,
    /** 对最终字符串（含层间空行与占位符替换结果）的估算。 */
    val totalTokenEstimate: Int,
    /** 纳入层估算之和，不含层间空行，因此通常略小于 totalTokenEstimate。 */
    val perLayerTokenSum: Int,
    val warnings: List<String>,
)

/**
 * Operit 模板占位符。
 *
 * 为什么引擎要认识这些名字：customSystemPromptTemplate 非空时最终字符串仍由模板拼接，
 * 缺占位符在旧实现里是 String.replace 静默无操作，用户会以为改了模板其实某段消失了。
 * 引擎把未填充的段落位写进 warnings，让「静默」变成可诊断（docs/51 4.3 / R4）。
 */
object PromptPlaceholders {
    const val SELF_INTRODUCTION = "BEGIN_SELF_INTRODUCTION_SECTION"
    const val WORKSPACE_GUIDELINES = "WORKSPACE_GUIDELINES_SECTION"
    const val TOOL_USAGE_GUIDELINES = "TOOL_USAGE_GUIDELINES_SECTION"
    const val PACKAGE_SYSTEM_GUIDELINES = "PACKAGE_SYSTEM_GUIDELINES_SECTION"
    const val ACTIVE_PACKAGES = "ACTIVE_PACKAGES_SECTION"
    const val AVAILABLE_TOOLS = "AVAILABLE_TOOLS_SECTION"

    /** 会话级占位符，由 ConversationService 在组装后替换（ConversationService.kt:622）。 */
    const val USER_NAME = "{{user}}"
    const val CHARACTER_NAME = "{{char}}"

    /** 内置模板里的 6 个段落位，顺序与 SYSTEM_PROMPT_TEMPLATE 一致。 */
    val SECTION_SLOTS: List<String> = listOf(
        SELF_INTRODUCTION,
        WORKSPACE_GUIDELINES,
        TOOL_USAGE_GUIDELINES,
        PACKAGE_SYSTEM_GUIDELINES,
        ACTIVE_PACKAGES,
        AVAILABLE_TOOLS,
    )
}

/** 追加层没写 order 时的默认序位（composer.ts:543-547）。 */
private const val EXTRA_LAYER_ORDER_BASE: Int = 1000

/**
 * 宿主现场层的默认序位，刻意排在追加层之后：
 * 追加层是用户持久化的自定义层，现场层每轮变化，把后者放在后面可以避免
 * 现场内容插进用户自定义层的相对顺序中间。
 */
private const val HOST_EXTRA_ORDER_BASE: Int = 2000

/**
 * 分层提示词组装器。
 *
 * 六个步骤与 composer.ts:570-642 一一对应：
 * 归一化输入 → 建层目录 → 逐层求值 → 判定纳入 → 逐层估算 → 按 order 稳定排序后 join。
 *
 * 三条必须原样保留的语义：
 * 1. 身份是替换不是拼接：人格非空时内置默认身份完全不出现；
 * 2. 内容覆盖不绕过激活条件：给 tool_context 写了 content，没有工具时该层仍然不纳入；
 * 3. 排序只看 order，同 order 看目录下标，绝不依赖 Map 迭代顺序。
 */
object PromptComposer {
    fun compose(
        context: PromptComposeContext,
        config: PromptLayerConfig = PromptLayerConfig.Empty,
    ): PromptComposeResult {
        val warnings = mutableListOf<String>()
        val ctx = normalizeContext(context)
        val definitions = buildDefinitions(config, ctx.hostExtras)

        // 未知 id 只 warning 不抛：配置可能来自旧版本偏好，整份提示词不该因此不可用。
        // id 重复则抛 IllegalArgumentException：它会让 overrides 的语义产生歧义（composer.ts:552-556）。
        val knownIds = definitions.mapTo(HashSet()) { it.id }
        for (id in config.overrides.keys) {
            if (!knownIds.contains(id)) {
                warnings += "未知层 id「" + id + "」，已忽略该层配置。"
            }
        }

        val layers = definitions
            .mapIndexed { index, definition ->
                resolveLayer(definition, index, ctx, config.overrides[definition.id])
            }
            .sortedWith(compareBy<IndexedLayer>({ it.layer.order }, { it.index }))
            .map { it.layer }

        val included = layers.filter { it.included }
        val joined = included.joinToString("\n\n") { it.content }
        val finalPrompt = replacePlaceholders(joined, ctx.placeholders, warnings)

        if (included.isEmpty()) {
            warnings += "没有任何层被纳入最终提示词，finalPrompt 为空。"
        }

        return PromptComposeResult(
            finalPrompt = finalPrompt,
            layers = layers,
            identitySource = ctx.identitySource,
            totalTokenEstimate = TokenEstimator.estimate(finalPrompt),
            perLayerTokenSum = included.sumOf { it.tokenEstimate },
            warnings = warnings.toList(),
        )
    }
}

/** 排序需要 layer 与目录下标配对；单独放进 PromptLayer 会污染对外的数据模型。 */
private data class IndexedLayer(
    val layer: PromptLayer,
    val index: Int,
)

private fun resolveLayer(
    definition: PromptLayerDefinition,
    index: Int,
    ctx: PromptComposeContext,
    override: PromptLayerOverride?,
): IndexedLayer {
    val enabled = override?.enabled ?: definition.defaultEnabled
    val order = override?.order ?: definition.defaultOrder

    val overrideTitle = override?.title?.trim()
    val title = if (overrideTitle.isNullOrEmpty()) definition.title else overrideTitle

    val active = definition.isActive(ctx)

    // 覆盖内容优先于渲染，但 active 仍然是硬门槛：覆盖内容不等于强制启用（composer.ts:587-595）。
    val overrideContent = override?.content
    val rendered = when {
        overrideContent != null -> overrideContent
        active -> definition.render(ctx)
        else -> ""
    }
    val content = rendered.trim()

    return IndexedLayer(
        index = index,
        layer = PromptLayer(
            id = definition.id,
            title = title,
            kind = definition.kind,
            origin = definition.resolveOrigin?.invoke(ctx) ?: definition.origin,
            order = order,
            enabled = enabled,
            active = active,
            content = content,
            tokenEstimate = TokenEstimator.estimate(content),
        ),
    )
}

/**
 * 输入归一化：trim、去空、去重。
 *
 * 为什么工具名不在这里去重：去重发生在渲染层（renderNameBlock），
 * 提前去重会让 token 估算和渲染看到两份不同的列表，出问题时无法判断是哪一步吃掉的名字。
 * 路径与记忆条目则在归一化阶段去重——它们是「事实清单」，重复项没有语义（composer.ts:250-273）。
 */
private fun normalizeContext(context: PromptComposeContext): PromptComposeContext = context.copy(
    userPersona = context.userPersona.trim(),
    builtinTools = normalizeToolNames(context.builtinTools),
    customPyTools = normalizeToolNames(context.customPyTools),
    skills = context.skills.mapNotNull { normalizeSkill(it) },
    sandboxPaths = context.sandboxPaths.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    memoryItems = context.memoryItems.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
)

private fun normalizeToolNames(names: List<String>): List<String> =
    names.map { it.trim() }.filter { it.isNotEmpty() }

private fun normalizeSkill(skill: PromptSkill): PromptSkill? {
    val id = skill.id.trim()
    if (id.isEmpty()) return null
    return skill.copy(
        id = id,
        name = skill.name.trim(),
        description = skill.description.trim(),
        absoluteDir = skill.absoluteDir.trim(),
    )
}

/**
 * 建层目录：14 层 + 持久化追加层 + 宿主现场层。
 *
 * 为什么在这里做 id 唯一性检查：overrides 以 id 为键，重复 id 会让「改哪一层」变得不可判定，
 * 属于配置错误而不是运行时状况，因此直接抛 IllegalArgumentException（composer.ts:552-556）。
 */
private fun buildDefinitions(
    config: PromptLayerConfig,
    hostExtras: List<PromptExtraLayer>,
): List<PromptLayerDefinition> {
    val configured = config.extraLayers.mapIndexed { index, extra ->
        extra.toDefinition(EXTRA_LAYER_ORDER_BASE + index)
    }
    val hosted = hostExtras.mapIndexed { index, extra ->
        extra.toDefinition(HOST_EXTRA_ORDER_BASE + index)
    }
    val definitions = PromptLayers.ALL + configured + hosted

    val seen = HashSet<String>()
    for (definition in definitions) {
        if (definition.id.isEmpty()) throw IllegalArgumentException("层 id 不能为空。")
        if (!seen.add(definition.id)) throw IllegalArgumentException("层 id 重复：" + definition.id)
    }
    return definitions
}

private fun PromptExtraLayer.toDefinition(defaultOrder: Int): PromptLayerDefinition {
    if (id.isEmpty()) throw IllegalArgumentException("层 id 不能为空。")
    val trimmedTitle = title.trim()
    return PromptLayerDefinition(
        id = id,
        title = if (trimmedTitle.isEmpty()) id else trimmedTitle,
        kind = kind,
        origin = origin,
        defaultOrder = order ?: defaultOrder,
        defaultEnabled = enabled,
        isActive = { true },
        render = { content },
    )
}

/**
 * 占位符替换与残留诊断。
 *
 * 为什么替换后还要检查残留：模板可能引用了引擎不认识的段落位，
 * 或者调用方只填了一部分。静默留下大写占位符会原样发给模型，属于最难排查的一类「提示词变短」。
 */
private fun replacePlaceholders(
    input: String,
    values: Map<String, String>,
    warnings: MutableList<String>,
): String {
    var result = input
    for ((key, value) in values) {
        if (key.isEmpty()) continue
        result = result.replace(key, value)
    }

    val unresolved = PromptPlaceholders.SECTION_SLOTS.filter { result.contains(it) }
    if (unresolved.isNotEmpty()) {
        warnings += "最终提示词仍含未填充的模板占位符：" + unresolved.joinToString("、")
    }
    return result
}

