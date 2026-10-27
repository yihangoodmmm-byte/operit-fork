package com.ai.assistance.operit.core.tools

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工具输出落盘 + 按工具差异化的预览策略。
 *
 * 为什么需要它：很多工具（shell、read_file、网页抓取）的输出远超模型上下文预算，
 * 旧做法是直接截断——信息当场丢失，模型再想查只能重跑一次命令。
 * 落盘之后只回「预览 + 路径 + 字节数」，模型需要细节时用文件工具分页读，
 * 这是把「一次性消耗上下文」换成「按需取用」。
 *
 * 为什么预览要分方向：不同工具的关键信息位置不同——shell 日志的结论在尾部，
 * 文件读取的开头才是正文，搜索结果前几条最重要。一律截前 N 字节会让
 * 「命令跑完了但看不到结果」这种最痛的情况继续发生。
 *
 * 为什么还要标注完整性：截断后的文本看起来和正常结果没有区别，
 * 模型会当成完整输出继续推理。必须显式说明「这是预览，完整内容在文件里」。
 */
object ToolOutputSpillStore {

    /** 超过这个字节数就落盘。 */
    const val SPILL_THRESHOLD_BYTES: Int = 8 * 1024

    /** 回给模型的预览长度（头部）。 */
    const val PREVIEW_CHARS: Int = 2 * 1024

    private const val TAIL_CHARS: Int = 1024

    private const val DIR_NAME = "tool-output"

    private val stampFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    /** 预览方向。 */
    enum class PreviewStrategy {
        /** 头 + 尾，用于命令/终端/日志类：结论通常在最后。 */
        HEAD_AND_TAIL,
        /** 只保留头部，用于文件阅读、网页抓取、搜索结果。 */
        HEAD,
    }

    /**
     * 按工具名决定预览方向。
     *
     * 这里用名字前缀而不是工具枚举：工具集是动态注册的（内置/包/MCP），
     * 新工具加进来时宁可走默认 HEAD，也不要因为枚举对不上而崩。
     */
    fun previewStrategyFor(toolName: String): PreviewStrategy {
        val name = toolName.lowercase(Locale.US)
        val tailImportant = listOf("shell", "terminal", "exec", "command", "bash", "run", "build", "test")
        return if (tailImportant.any { name.contains(it) }) PreviewStrategy.HEAD_AND_TAIL else PreviewStrategy.HEAD
    }

    /** 落盘结果：调用方据此决定回给模型什么。 */
    data class Spilled(
        val file: File,
        val totalBytes: Int,
        val preview: String,
        val strategy: PreviewStrategy,
    ) {
        /** 回给模型的替换文本：预览 + 明确的完整性声明 + 路径。 */
        fun toModelText(toolName: String): String = buildString {
            append(preview)
            append("\n\n[不完整输出] 该工具输出共 ")
            append(totalBytes)
            append(" 字节，已超过单条工具结果的保留上限。")
            append("上面")
            append(
                if (strategy == PreviewStrategy.HEAD_AND_TAIL) "是开头与结尾的预览"
                else "只是开头部分"
            )
            append("，中间内容没有发送给你。")
            append("完整内容已落盘，用文件工具分页读取：")
            append(file.absolutePath)
            append("（工具：")
            append(toolName)
            append("）")
        }
    }

    /**
     * 超过阈值才落盘；否则返回 null，调用方沿用原文本。
     *
     * 不做任何吞异常的处理：磁盘写失败应该让调用方知道，
     * 否则模型会收到一个指向不存在文件的路径。
     */
    fun spillIfNeeded(raw: String, toolName: String, baseDir: File): Spilled? {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size <= SPILL_THRESHOLD_BYTES) return null

        val dir = File(baseDir, DIR_NAME).apply { mkdirs() }
        val safeTool = toolName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val file = File(dir, stampFormat.format(Date()) + "-" + safeTool + ".txt")
        file.writeBytes(bytes)

        val strategy = previewStrategyFor(toolName)
        val preview = when {
            strategy == PreviewStrategy.HEAD -> head(raw, PREVIEW_CHARS)
            raw.length <= PREVIEW_CHARS + TAIL_CHARS -> raw
            else -> buildString {
                append(head(raw, PREVIEW_CHARS))
                append("\n\n…（中间省略 ")
                append(raw.length - PREVIEW_CHARS - TAIL_CHARS)
                append(" 字符）…\n\n")
                append(raw.takeLast(TAIL_CHARS))
            }
        }
        return Spilled(file = file, totalBytes = bytes.size, preview = preview, strategy = strategy)
    }

    private fun head(text: String, limit: Int): String =
        if (text.length > limit) text.substring(0, limit) else text
}
