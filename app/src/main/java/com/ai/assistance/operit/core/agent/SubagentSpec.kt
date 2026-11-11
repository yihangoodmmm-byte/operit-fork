package com.ai.assistance.operit.core.agent

/**
 * 子智能体的能力边界与执行规格。
 *
 * 设计原则（对齐 dsh-superagent 的「授权 + 静默工作」语义，但用 Kotlin 重写）：
 * - 每个子智能体是独立的受控执行单元，能力由 [capabilities] 显式授予，默认最小权限。
 * - 是否允许写文件 / 跑命令是显式开关，不在 SubagentRunner 里硬编码。
 * - [observeOnly] 对应 dsh-superagent 的观察模式：只产出「计划与结论」，不真正执行副作用。
 */
data class SubagentSpec(
    /** 子任务描述。 */
    val task: String,

    /** 需要阅读的绝对路径（可空，纯文本任务不传）。 */
    val filePaths: List<String> = emptyList(),

    /** 允许的能力清单；空列表 = 纯只读分析（向后兼容旧 run_subagent）。 */
    val capabilities: Set<SubagentCapability> = emptySet(),

    /** 单文件字节上限。 */
    val maxBytesPerFile: Int = SubagentRunner.MAX_BYTES_PER_FILE,

    /** 总注入字符上限。 */
    val maxTotalChars: Int = SubagentRunner.MAX_TOTAL_CHARS,

    /** 观察模式：只返回计划与结论，不落任何写副作用。 */
    val observeOnly: Boolean = false,

    /** 子任务模型配置覆写（configId）；null = 复用调用方传入的模型。 */
    val modelConfigIdOverride: String? = null,

    /** 子任务模型 index 覆写。 */
    val modelIndexOverride: Int? = null,

    /** 工作区路径（写文件/跑命令时作为 cwd 基准）。 */
    val workspacePath: String? = null
) {
    /** 是否允许执行 shell 命令。 */
    val canRunCommand: Boolean get() = SubagentCapability.RUN_COMMAND in capabilities

    /** 是否允许写文件。 */
    val canWriteFile: Boolean get() = SubagentCapability.WRITE_FILE in capabilities

    /** 是否允许调用其它工具（读文件、搜索、计算等受控工具）。 */
    val canUseTools: Boolean get() = SubagentCapability.USE_TOOLS in capabilities

    /** 该子智能体是否有任何需要工具循环的能力（READ_ONLY 与空集都算纯只读）。 */
    val requiresToolLoop: Boolean
        get() = capabilities.any {
            it == SubagentCapability.USE_TOOLS ||
                it == SubagentCapability.WRITE_FILE ||
                it == SubagentCapability.RUN_COMMAND
        }
}

/**
 * 子智能体能力枚举。
 *
 * 最小权限：默认只有 [READ_ONLY]（不写、不跑命令、纯分析）。
 * 写文件与跑命令是两个独立位，调用方必须显式授予。
 */
enum class SubagentCapability {
    /** 只读分析（默认）。 */
    READ_ONLY,

    /** 允许调用受控工具清单（读文件、搜索、计算等）。 */
    USE_TOOLS,

    /** 允许写文件。 */
    WRITE_FILE,

    /** 允许执行 shell 命令。 */
    RUN_COMMAND
}

/**
 * 单个子智能体的执行结果。
 */
data class SubagentResult(
    val spec: SubagentSpec,
    val content: String,
    val success: Boolean,
    val error: String? = null,
    val toolInvocationCount: Int = 0
)

/**
 * 并行子智能体编排的汇总结果。
 */
data class SubagentBatchResult(
    val results: List<SubagentResult>
) {
    val successCount: Int get() = results.count { it.success }
    val failureCount: Int get() = results.count { !it.success }

    val combined: String
        get() = results.joinToString("\n\n") { r ->
            val header = if (r.success) "[子任务完成]" else "[子任务失败]"
            header + " " + r.content.ifBlank { r.error ?: "无输出" }
        }
}
