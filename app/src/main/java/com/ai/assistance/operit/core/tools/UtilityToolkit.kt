package com.ai.assistance.operit.core.tools

import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.security.MessageDigest

/**
 * 纯计算类实用工具。
 *
 * 为什么 agent 需要它们：常见验证回路是「改完 - 读回来 - 确认」。
 * 没有 diff 工具时，只能把两段文本都塞给模型用眼睛比对，既烧 token 又容易漏；
 * 没有版本比较时，1.10.0 会被当成比 1.9.0 小。
 * 这些都是纯 JVM、无 IO、无权限的确定性计算，适合做成工具直接调用。
 *
 * 算法与取舍参考 Ultra-Guru/Android-Guru-Agent 的 builtin 实现，
 * 按本项目 registerTool 的形式重写，不引入对方框架。
 */
object UtilityToolkit {

    // ================= 文本 diff =================

    sealed interface DiffOp {
        val lines: List<String>
        data class Equal(override val lines: List<String>) : DiffOp
        data class Insert(override val lines: List<String>) : DiffOp
        data class Delete(override val lines: List<String>) : DiffOp
    }

    /** 统一行尾，并丢掉「末尾换行」造成的那条幽灵空行（a\n 与 a 不算差异）。 */
    fun lineSplit(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n')
        return if (normalized.endsWith('\n') && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    /** Myers 差分；改几个字符时接近线性。 */
    fun diff(a: List<String>, b: List<String>): List<DiffOp> {
        val n = a.size
        val m = b.size
        val max = n + m
        val v = IntArray(2 * max + 1)
        val rounds = mutableListOf<IntArray>()

        if (max > 0) {
            outer@ for (d in 0..max) {
                rounds += v.copyOf()
                var k = -d
                while (k <= d) {
                    val idx = k + max
                    val x = if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                        v[idx + 1]
                    } else {
                        v[idx - 1] + 1
                    }
                    var xx = x
                    var yy = x - k
                    while (xx < n && yy < m && a[xx] == b[yy]) {
                        xx++
                        yy++
                    }
                    v[idx] = xx
                    if (xx >= n && yy >= m) break@outer
                    k += 2
                }
            }
        } else {
            rounds += v.copyOf()
        }

        val steps = ArrayDeque<Triple<Char, Int, Int>>()
        var x = n
        var y = m
        for (d in rounds.indices.reversed()) {
            if (x == 0 && y == 0) break
            val vv = rounds[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && at(vv, k - 1 + max) < at(vv, k + 1 + max))) k + 1 else k - 1
            val prevX = at(vv, prevK + max)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY && x > 0 && y > 0) {
                steps.addLast(Triple('=', x - 1, y - 1))
                x--
                y--
            }
            if (d > 0 && (prevX != x || prevY != y)) {
                if (prevX == x && y > 0) {
                    steps.addLast(Triple('+', 0, y - 1))
                } else if (prevY == y && x > 0) {
                    steps.addLast(Triple('-', x - 1, 0))
                }
            }
            x = prevX
            y = prevY
        }

        val ops = mutableListOf<DiffOp>()
        for (step in steps.reversed()) {
            val op: DiffOp = when (step.first) {
                '=' -> DiffOp.Equal(listOf(a[step.second]))
                '+' -> DiffOp.Insert(listOf(b[step.third]))
                else -> DiffOp.Delete(listOf(a[step.second]))
            }
            val last = ops.lastOrNull()
            val merged = when {
                last is DiffOp.Equal && op is DiffOp.Equal -> DiffOp.Equal(last.lines + op.lines)
                last is DiffOp.Insert && op is DiffOp.Insert -> DiffOp.Insert(last.lines + op.lines)
                last is DiffOp.Delete && op is DiffOp.Delete -> DiffOp.Delete(last.lines + op.lines)
                else -> null
            }
            if (merged != null) ops[ops.lastIndex] = merged else ops += op
        }
        return ops
    }

    private fun at(v: IntArray, index: Int): Int = v[index.coerceIn(0, v.size - 1)]

    /** 统计式输出：只看改了几行时比 unified 更省 token。 */
    fun renderDiffStat(ops: List<DiffOp>): String {
        val inserted = ops.filterIsInstance<DiffOp.Insert>().sumOf { it.lines.size }
        val deleted = ops.filterIsInstance<DiffOp.Delete>().sumOf { it.lines.size }
        if (inserted == 0 && deleted == 0) return "无差异"
        return inserted.toString() + " 行新增，" + deleted.toString() + " 行删除"
    }

    /** 统一 diff 输出。 */
    fun renderDiffUnified(ops: List<DiffOp>, label1: String, label2: String, context: Int): String {
        if (ops.all { it is DiffOp.Equal }) return "无差异"
        val sb = StringBuilder()
        sb.append("--- ").append(label1).append('\n')
        sb.append("+++ ").append(label2).append('\n')
        var i = 0
        val n = ops.size
        while (i < n) {
            while (i < n && ops[i] is DiffOp.Equal) i++
            if (i >= n) break
            val start = (i - context).coerceAtLeast(0)
            var j = i
            while (j < n && ops[j] !is DiffOp.Equal) j++
            val end = (j + context).coerceAtMost(n)

            var aLine = 1
            var bLine = 1
            for (idx in 0 until start) {
                when (ops[idx]) {
                    is DiffOp.Equal -> { aLine++; bLine++ }
                    is DiffOp.Delete -> aLine++
                    is DiffOp.Insert -> bLine++
                }
            }
            var aCount = 0
            var bCount = 0
            for (idx in start until end) {
                when (val op = ops[idx]) {
                    is DiffOp.Equal -> { aCount += op.lines.size; bCount += op.lines.size }
                    is DiffOp.Delete -> aCount += op.lines.size
                    is DiffOp.Insert -> bCount += op.lines.size
                }
            }
            sb.append("@@ -").append(aLine).append(',').append(aCount)
                .append(" +").append(bLine).append(',').append(bCount).append(" @@\n")
            for (idx in start until end) {
                val marker = when (ops[idx]) {
                    is DiffOp.Equal -> ' '
                    is DiffOp.Delete -> '-'
                    is DiffOp.Insert -> '+'
                }
                ops[idx].lines.forEach { line ->
                    sb.append(marker).append(line).append('\n')
                }
            }
            i = end
        }
        return sb.toString().trimEnd()
    }

    // ================= 版本比较 =================

    data class ParsedVersion(val core: List<Long>, val pre: List<String>)

    /**
     * 解析 v?X.Y.Z(-pre)?(+build)?。
     * 非数字段（如 1.2.3ubuntu1）退化成预发布标识，保证比较总是有结果而不是抛错。
     */
    fun parseVersion(raw: String): ParsedVersion {
        var body = raw.trim()
        if (body.startsWith("v") || body.startsWith("V")) body = body.substring(1)
        val plus = body.indexOf('+')
        if (plus >= 0) body = body.substring(0, plus)
        val dash = body.indexOf('-')
        val coreText = if (dash >= 0) body.substring(0, dash) else body
        val preText = if (dash >= 0) body.substring(dash + 1) else ""

        val segments = coreText.split('.', '/').filter { it.isNotBlank() }
        val numbers = mutableListOf<Long>()
        for ((index, seg) in segments.withIndex()) {
            val num = seg.toLongOrNull()
            if (num == null) {
                val tail = segments.subList(index, segments.size).joinToString("-")
                return ParsedVersion(
                    core = padVersion(numbers),
                    pre = buildList {
                        if (preText.isNotBlank()) add(preText)
                        add(tail)
                    }
                )
            }
            numbers += num
            if (numbers.size >= 4) break
        }
        val pre = if (preText.isBlank()) emptyList() else preText.split('.', '-').filter { it.isNotBlank() }
        return ParsedVersion(padVersion(numbers), pre)
    }

    private fun padVersion(v: List<Long>): List<Long> {
        val out = v.toMutableList()
        while (out.size < 3) out += 0L
        return out
    }

    fun compareVersion(a: ParsedVersion, b: ParsedVersion): Int {
        for (i in 0 until maxOf(a.core.size, b.core.size)) {
            val av = a.core.getOrElse(i) { 0L }
            val bv = b.core.getOrElse(i) { 0L }
            if (av != bv) return if (av < bv) -1 else 1
        }
        // 预发布版本低于正式版本
        if (a.pre.isEmpty() && b.pre.isEmpty()) return 0
        if (a.pre.isEmpty()) return 1
        if (b.pre.isEmpty()) return -1
        for (i in 0 until maxOf(a.pre.size, b.pre.size)) {
            val av = a.pre.getOrNull(i) ?: return -1
            val bv = b.pre.getOrNull(i) ?: return 1
            val an = av.toLongOrNull()
            val bn = bv.toLongOrNull()
            val cmp = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null && bn == null -> -1
                an == null && bn != null -> 1
                else -> av.compareTo(bv)
            }
            if (cmp != 0) return if (cmp < 0) -1 else 1
        }
        return 0
    }

    // ================= 单位换算 =================

    private val LENGTH = mapOf(
        "mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
        "in" to 0.0254, "ft" to 0.3048, "yd" to 0.9144, "mi" to 1609.344, "nmi" to 1852.0
    )
    private val MASS = mapOf(
        "mg" to 1.0e-6, "g" to 1.0e-3, "kg" to 1.0, "t" to 1000.0,
        "oz" to 0.028349523125, "lb" to 0.45359237, "st" to 6.35029318
    )
    private val DATA = mapOf(
        "B" to 1.0, "KB" to 1.0e3, "MB" to 1.0e6, "GB" to 1.0e9, "TB" to 1.0e12,
        "KiB" to 1024.0, "MiB" to 1048576.0, "GiB" to 1073741824.0, "TiB" to 1099511627776.0
    )
    private val SPEED = mapOf("mps" to 1.0, "kmh" to 1.0 / 3.6, "mph" to 0.44704, "kn" to 1852.0 / 3600.0)

    private val FACTOR_TABLES = mapOf("length" to LENGTH, "mass" to MASS, "data" to DATA, "speed" to SPEED)

    private fun categoriesOf(symbol: String): List<String> =
        FACTOR_TABLES.filterValues { it.containsKey(symbol) }.keys.toList()

    fun formatNumber(v: Double): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
        if (v == 0.0) return "0"
        var text = BigDecimal(v).round(MathContext(6)).toPlainString()
        if (text.contains(".")) {
            text = text.trimEnd('0').trimEnd('.')
            if (text.isEmpty() || text == "-") text = "0"
        }
        return text
    }

    /** 换算单位；返回 null 表示符号未知、类别不兼容或跨类别歧义。 */
    fun convertUnit(value: Double, from: String, to: String, category: String?): String? {
        val cat = category?.lowercase()
        if (cat != null && cat !in FACTOR_TABLES && cat != "temperature") return null

        val fromUpper = from.uppercase()
        val toUpper = to.uppercase()
        val tempUnits = setOf("C", "F", "K")

        if (cat == "temperature" || (cat == null && fromUpper in tempUnits && toUpper in tempUnits)) {
            if (fromUpper !in tempUnits || toUpper !in tempUnits) return null
            val celsius = when (fromUpper) {
                "C" -> value
                "F" -> (value - 32.0) * 5.0 / 9.0
                else -> value - 273.15
            }
            val result = when (toUpper) {
                "C" -> celsius
                "F" -> celsius * 9.0 / 5.0 + 32.0
                else -> celsius + 273.15
            }
            return formatNumber(value) + " " + from + " = " + formatNumber(result) + " " + to
        }

        val table: Map<String, Double> = if (cat != null) {
            FACTOR_TABLES[cat] ?: return null
        } else {
            val common = categoriesOf(from).intersect(categoriesOf(to))
            if (common.size != 1) return null
            FACTOR_TABLES.getValue(common.first())
        }
        val fromFactor = table[from] ?: return null
        val toFactor = table[to] ?: return null
        val result = value * fromFactor / toFactor
        return formatNumber(value) + " " + from + " = " + formatNumber(result) + " " + to +
            "\nfactor: " + formatNumber(fromFactor / toFactor)
    }

    // ================= 文件哈希 =================

    private val HEX = "0123456789abcdef"

    fun hexOf(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { b ->
            append(HEX[(b.toInt() shr 4) and 0x0F])
            append(HEX[b.toInt() and 0x0F])
        }
    }

    /** 一次性算多个摘要；文件不存在或读失败返回 null。 */
    fun hashFile(file: File, algorithms: List<String>): Map<String, String>? {
        if (!file.isFile) return null
        val digests = algorithms.associateWith { MessageDigest.getInstance(it) }
        return runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digests.values.forEach { it.update(buffer, 0, read) }
                }
            }
            digests.mapValues { (_, d) -> hexOf(d.digest()) }
        }.getOrNull()
    }
}
