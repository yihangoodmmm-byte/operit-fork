package com.ai.assistance.operit.core.prompt

/**
 * 层的语义类别。只影响 UI 分组、诊断导出与将来的开关权限矩阵，
 * 不参与组装顺序——顺序永远只看 order，同序再看目录下标。
 */
enum class PromptLayerKind {
    IDENTITY,
    ENVIRONMENT,
    TOOLS,
    SKILLS,
    GUIDANCE,
    MEMORY,
    POLICY,
    CUSTOM,
}

/**
 * 层来源。冲突检测按来源划分两侧：USER 是用户自己写的内容（人格、角色卡），
 * BUILTIN 是引擎自带的文案，SESSION 是运行时事实（记忆快照、人格优先声明）。
 *
 * 为什么 SESSION 必须独立于 USER：人格优先声明本身就是为了「支持用户身份」，
 * 若算成用户侧就会和任何人的人格互报冲突；记忆快照是数据而不是主张，
 * 拿它去比对只会产生噪声（composer.ts:386-398、conflict.ts:1236-1244）。
 */
enum class LayerOrigin {
    USER,
    BUILTIN,
    SESSION,
}

/** 层没有进入最终提示词的原因；null 表示已纳入。 */
enum class LayerSkipReason {
    DISABLED,
    INACTIVE,
    EMPTY,
}

/**
 * 身份文本的来源。DEFAULT 表示用户没有写人格，身份层取内置默认身份
 * （composer.ts:134-137、256 的规格语义）。
 */
enum class IdentitySource {
    USER_PERSONA,
    DEFAULT,
}

/**
 * 组装后的一层，不可变，供 UI、诊断与冲突检测消费。
 *
 * 为什么 included / skipReason 是计算属性：它们只由 enabled、active、content 三者决定。
 * 若做成构造参数，调用方就能构造出「content 非空却 included=false」这种自相矛盾的状态，
 * 而冲突检测恰恰依赖这个一致性。
 */
data class PromptLayer(
    val id: String,
    val title: String,
    val kind: PromptLayerKind,
    val origin: LayerOrigin,
    val order: Int,
    val enabled: Boolean,
    val active: Boolean,
    val content: String,
    val tokenEstimate: Int,
) {
    /** 真正进入最终提示词的判定，与 composer.ts:595 完全一致。 */
    val included: Boolean
        get() = enabled && active && content.isNotEmpty()

    /** 未纳入的原因；已纳入时为 null。判定顺序也与 composer.ts:596-602 一致。 */
    val skipReason: LayerSkipReason?
        get() = when {
            !enabled -> LayerSkipReason.DISABLED
            !active -> LayerSkipReason.INACTIVE
            content.isEmpty() -> LayerSkipReason.EMPTY
            else -> null
        }
}

/**
 * 层目录项：静态元数据 + 两个求值函数。
 *
 * 为什么不用 data class：equals 会把 lambda 当字段比较，两个语义相同的目录项永远不相等，
 * 给测试断言和集合操作埋坑；目录项只作为常量使用，不需要值相等语义。
 */
class PromptLayerDefinition(
    val id: String,
    val title: String,
    val kind: PromptLayerKind,
    val origin: LayerOrigin,
    val defaultOrder: Int,
    val defaultEnabled: Boolean,
    /** 渲染条件是否成立（有没有工具、路径、记忆……）。 */
    val isActive: (PromptComposeContext) -> Boolean,
    /** 渲染正文；只在 active 为 true 时被调用，内部不必再判断条件。 */
    val render: (PromptComposeContext) -> String,
    /**
     * 身份层的来源随输入变化：用户写了人格属于 USER，否则属于 BUILTIN。
     * 其余层留空，直接使用 origin。
     */
    val resolveOrigin: ((PromptComposeContext) -> LayerOrigin)? = null,
)

/**
 * 单层覆盖。null 表示「沿用目录默认值」，
 * 这样持久化时只需要写真正被改动过的字段，也不必随目录默认值变化而迁移。
 */
data class PromptLayerOverride(
    val enabled: Boolean? = null,
    val order: Int? = null,
    val content: String? = null,
    val title: String? = null,
)

/**
 * 宿主或皮肤注入的额外层，没有输入条件，因此恒为 active（composer.ts:546）；
 * 只能靠 enabled 关闭，开关与顺序仍可被 PromptLayerConfig.overrides 覆盖。
 */
data class PromptExtraLayer(
    val id: String,
    val title: String,
    val content: String,
    val kind: PromptLayerKind = PromptLayerKind.CUSTOM,
    val origin: LayerOrigin = LayerOrigin.BUILTIN,
    /** null 表示由引擎按追加顺序分配（composer.ts:543-547）。 */
    val order: Int? = null,
    val enabled: Boolean = true,
)

/**
 * 引擎配置：按层 id 的覆盖 + 追加层。
 * 空配置是合法且最常用的状态（用户没动过任何开关），因此提供 Empty 常量而不是可空参数。
 */
data class PromptLayerConfig(
    val overrides: Map<String, PromptLayerOverride> = emptyMap(),
    val extraLayers: List<PromptExtraLayer> = emptyList(),
) {
    companion object {
        val Empty: PromptLayerConfig = PromptLayerConfig()
    }
}

/**
 * 技能元数据。三个文本字段都来自第三方写的 SKILL.md，
 * 渲染时必须 XML 转义，否则可以用闭合标签伪造提示词段落（composer.ts:302-308）。
 */
data class PromptSkill(
    val id: String,
    val name: String = "",
    val description: String = "",
    val absoluteDir: String = "",
)

