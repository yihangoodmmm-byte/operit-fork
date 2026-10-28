package com.ai.assistance.operit.core.tools

import java.io.File
import org.json.JSONObject

/**
 * 失败记忆：同类失败重复出现时给出提示。
 *
 * 为什么按「工具 + 错误签名」记：同一个工具可能因为很多原因失败，
 * 只有错误特征相同才算「同一个坑」。签名取归一化后的错误前缀
 * （去掉数字、时间戳、路径里的随机段），否则每次报错都因为行号/ID 不同而被当成新问题。
 *
 * 为什么只在第二次及以后提示：第一次失败时模型本来就该自己读错误；
 * 上来就提示「你以前失败过」会干扰首轮判断。
 */
object FailureMemoryStore {

    private const val FILE_NAME = "failure-memory.json"
    private const val SIGNATURE_LENGTH = 120
    private const val HINT_THRESHOLD = 2

    private val lock = Any()

    /** 记录一次失败；返回提示文本（未达阈值时为 null）。 */
    fun observe(baseDir: File, toolName: String, errorText: String): String? {
        if (errorText.isBlank()) return null
        val signature = signatureOf(toolName, errorText)
        synchronized(lock) {
            val file = File(baseDir, FILE_NAME)
            val root = runCatching {
                if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
            }.getOrElse { JSONObject() }

            val record = root.optJSONObject(signature) ?: JSONObject()
            val count = record.optInt("count", 0) + 1
            record.put("count", count)
            record.put("tool", toolName)
            record.put("lastError", errorText.take(200))
            record.put("lastAt", System.currentTimeMillis())
            root.put(signature, record)

            file.parentFile?.mkdirs()
            file.writeText(root.toString(), Charsets.UTF_8)

            if (count < HINT_THRESHOLD) return null
            return "[重复失败提醒] 这个工具用相同方式已经失败 " + count +
                " 次。不要原样重试；先检查参数、路径或权限，必要时换一种做法。"
        }
    }

    /**
     * 归一化错误文本：去掉数字、十六进制地址和连续空白，
     * 让「File not found: /a/b1.txt」和「/a/b2.txt」落进同一个签名。
     */
    private fun signatureOf(toolName: String, errorText: String): String {
        val normalized = errorText
            .replace(Regex("0x[0-9a-fA-F]+"), "0xADDR")
            .replace(Regex("[0-9]+"), "N")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
            .take(SIGNATURE_LENGTH)
        return toolName + "|" + normalized
    }
}
