package com.ai.assistance.operit.core.tools

/**
 * 极简 JSON 路径求值，支持 a.b[0].c 与 $.a.b 两种写法。
 *
 * 为什么不引第三方库：项目已在用 org.json，这里只做「取子对象」一件事，
 * 引入 JSONPath 库会多一个依赖，而实际需求只覆盖点号与数组下标。
 */
object JsonQueryToolkit {

    sealed interface Token {
        data class Field(val name: String) : Token
        data class Index(val index: Int) : Token
    }

    /**
     * 求值。命中返回 (值, null)，未命中返回 (null, 原因)。
     *
     * 返回值而不是抛异常：调用方是工具层，要把原因直接回给模型，
     * 异常栈对模型没有意义。
     */
    fun query(json: String, path: String): Pair<String?, String?> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return null to "JSON 为空"

        val tokens = parsePath(path)
            ?: return null to "路径格式不对，示例：a.b[0].c 或 \$.a.b"

        val root: Any? = runCatching { parseNode(trimmed) }
            .getOrElse { return null to ("JSON 解析失败：" + it.message) }

        var current: Any? = root
        for (token in tokens) {
            current = when (token) {
                is Token.Field -> {
                    @Suppress("UNCHECKED_CAST")
                    val obj = current as? Map<String, Any?>
                        ?: return null to ("路径中 " + token.name + " 处不是对象")
                    if (!obj.containsKey(token.name)) return null to ("没有字段 " + token.name)
                    obj[token.name]
                }
                is Token.Index -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = current as? List<Any?>
                        ?: return null to ("路径中 [" + token.index + "] 处不是数组")
                    if (token.index !in list.indices) return null to ("数组下标越界：" + token.index)
                    list[token.index]
                }
            }
        }
        return render(current) to null
    }

    fun parsePath(path: String): List<Token>? {
        var body = path.trim()
        if (body.startsWith("$")) body = body.substring(1)
        if (body.startsWith(".")) body = body.substring(1)
        if (body.isEmpty()) return emptyList()

        val tokens = mutableListOf<Token>()
        val buffer = StringBuilder()
        var i = 0
        fun flush() {
            if (buffer.isNotEmpty()) {
                tokens += Token.Field(buffer.toString())
                buffer.clear()
            }
        }
        while (i < body.length) {
            val ch = body[i]
            if (ch == '.') {
                flush()
                i++
            } else if (ch == '[') {
                flush()
                val close = body.indexOf(']', i)
                if (close < 0) return null
                val index = body.substring(i + 1, close).trim().toIntOrNull() ?: return null
                tokens += Token.Index(index)
                i = close + 1
            } else {
                buffer.append(ch)
                i++
            }
        }
        flush()
        return tokens
    }

    private fun parseNode(text: String): Any? {
        if (text.startsWith("{")) {
            val obj = org.json.JSONObject(text)
            val map = LinkedHashMap<String, Any?>()
            obj.keys().forEach { key -> map[key] = toPlain(obj.get(key)) }
            return map
        }
        if (text.startsWith("[")) {
            val arr = org.json.JSONArray(text)
            return (0 until arr.length()).map { toPlain(arr.get(it)) }
        }
        return toPlain(org.json.JSONTokener(text).nextValue())
    }

    private fun toPlain(value: Any?): Any? = when (value) {
        is org.json.JSONObject -> {
            val map = LinkedHashMap<String, Any?>()
            value.keys().forEach { key -> map[key] = toPlain(value.get(key)) }
            map
        }
        is org.json.JSONArray -> (0 until value.length()).map { toPlain(value.get(it)) }
        is org.json.JSONTokener -> value.nextValue()
        else -> value
    }

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Map<*, *> -> {
            val obj = org.json.JSONObject()
            value.forEach { (k, v) -> obj.put(k.toString(), v) }
            obj.toString()
        }
        is List<*> -> org.json.JSONArray(value).toString()
        else -> value.toString()
    }
}
