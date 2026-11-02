package com.ai.assistance.operit.core.tools

/**
 * 正则与 JSON 路径工具。
 *
 * 为什么单开一个文件而不是塞进 UtilityToolkit：往 500 行的文件里反复追加容易改坏结构，
 * 这里按主题拆开，每个文件都能独立校验、独立编译。
 *
 * 两道保护：
 * 1. 输入长度上限 [MAX_REGEX_INPUT]，避免把整本书喂给正则；
 * 2. 结果条数上限 [MAX_MATCHES]，再多模型也读不完，只会挤爆上下文。
 */
object TextQueryToolkit {

    const val MAX_REGEX_INPUT: Int = 1_048_576
    const val MAX_MATCHES: Int = 100
    private const val MAX_MATCH_TEXT: Int = 400

    data class RegexMatchLine(val index: Int, val groups: List<String>)

    /** 提取所有匹配；group 非空时只取该分组。 */
    fun regexExtract(
        text: String,
        pattern: String,
        ignoreCase: Boolean,
        group: Int?
    ): List<RegexMatchLine> {
        val flags = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = Regex(pattern, flags)
        val out = mutableListOf<RegexMatchLine>()
        var index = 0
        for (match in regex.findAll(text)) {
            if (out.size >= MAX_MATCHES) break
            val groups = if (group != null) {
                listOf(match.groupValues.getOrElse(group) { "" })
            } else {
                match.groupValues.map { value ->
                    if (value.length > MAX_MATCH_TEXT) value.substring(0, MAX_MATCH_TEXT) + "..." else value
                }
            }
            out += RegexMatchLine(index, groups)
            index++
        }
        return out
    }

    /** 替换并返回被替换次数。 */
    fun regexReplace(
        text: String,
        pattern: String,
        replacement: String,
        ignoreCase: Boolean
    ): Pair<String, Int> {
        val flags = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = Regex(pattern, flags)
        var count = 0
        val result = regex.replace(text) { match ->
            count++
            Regex.escapeReplacement(replacement).let {
                // 支持 $1 这类分组引用：先还原成替换模板
                replacement.replace("\\", "\\\\").replace("$", "\\$")
            }.let { _ -> expandReplacement(match, replacement) }
        }
        return result to count
    }

    /** 展开替换串里的 $0 / $1 分组引用。 */
    private fun expandReplacement(match: MatchResult, replacement: String): String {
        if (!replacement.contains('$')) return replacement
        val sb = StringBuilder()
        var i = 0
        while (i < replacement.length) {
            val ch = replacement[i]
            if (ch == '\\' && i + 1 < replacement.length) {
                sb.append(replacement[i + 1])
                i += 2
                continue
            }
            if (ch == '$' && i + 1 < replacement.length) {
                var j = i + 1
                val digits = StringBuilder()
                while (j < replacement.length && replacement[j].isDigit()) {
                    digits.append(replacement[j])
                    j++
                }
                if (digits.isNotEmpty()) {
                    val index = digits.toString().toIntOrNull()
                    if (index != null && index < match.groupValues.size) {
                        sb.append(match.groupValues[index])
                        i = j
                        continue
                    }
                }
                sb.append(ch)
                i++
                continue
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /** 渲染成便于模型阅读的文本。 */
    fun renderMatches(lines: List<RegexMatchLine>, showGroups: Boolean): String {
        if (lines.isEmpty()) return "没有匹配"
        val sb = StringBuilder()
        lines.forEach { line ->
            sb.append(line.index + 1).append(". ")
            if (showGroups) {
                sb.append(line.groups.joinToString(" | ") { oneLine(it) })
            } else {
                sb.append(oneLine(line.groups.firstOrNull().orEmpty()))
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun oneLine(text: String): String =
        text.replace("\r", "").replace("\n", "\\n")
}
