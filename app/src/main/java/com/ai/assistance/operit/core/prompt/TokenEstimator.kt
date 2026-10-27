package com.ai.assistance.operit.core.prompt

import kotlin.math.ceil

/**
 * CJK 感知的 token 粗估。
 *
 * 为什么不用真 tokenizer：引擎每轮要对十几层各算一次，接真 tokenizer 会把 provider
 * 依赖拖进纯 Kotlin 模块，还会带来毫秒级开销；这里的数字只用来「比较各层体积」和
 * 「发现某一层突然膨胀」，不能当计费依据，也不能替代 provider 的 calculateInputTokens。
 *
 * 口径与 proto/src/composer.ts:188-199 完全一致，方便和 Persona 侧的旧数据对照：
 * - code point >= 0x2E80（CJK 部首、假名、谚文、全角标点）记 1 token/字；
 * - 其余字符（拉丁、数字、半角标点）记 4 字符/token；
 * - 最后向上取整。
 *
 * 为什么按 code point 而不是 UTF-16 char 计数：emoji 与扩展区汉字是代理对，
 * 按 char 计会把一个字算成两个 token，中英混排的层体积比较就会失真。
 */
object TokenEstimator {
    private const val CJK_START_CODE_POINT = 0x2E80

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjkCount = 0
        var otherCount = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint >= CJK_START_CODE_POINT) {
                cjkCount++
            } else {
                otherCount++
            }
            index += Character.charCount(codePoint)
        }
        return ceil(cjkCount + otherCount / 4.0).toInt()
    }
}

