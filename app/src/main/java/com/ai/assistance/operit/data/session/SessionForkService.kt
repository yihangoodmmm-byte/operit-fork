package com.ai.assistance.operit.data.session

import android.content.Context
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import java.time.LocalDateTime
import java.util.UUID

/**
 * 会话分叉与回退。
 *
 * 为什么用「复制消息」而不是引用：Operit 的消息是纯值对象（没有独立 id），
 * 复制一份是语义最清楚的做法——分叉出去之后两边互不影响，
 * 用户在分叉里删消息不会动到原会话。
 *
 * 为什么回退是「截断后另存」而不是直接删：聊天记录是用户资产，
 * 误回退的代价比多占一点空间大。回退只把当前会话的消息列表截短，
 * 原内容可通过分叉副本找回。
 */
object SessionForkService {

    /**
     * 从 [source] 分叉出新会话。
     *
     * @param upToMessageIndex 保留到第几条消息（含）；null 表示整份复制。
     */
    suspend fun fork(
        context: Context,
        source: ChatHistory,
        upToMessageIndex: Int? = null
    ): ChatHistory {
        val messages = if (upToMessageIndex == null) {
            source.messages.toList()
        } else {
            source.messages.take((upToMessageIndex + 1).coerceIn(0, source.messages.size))
        }
        val now = LocalDateTime.now()
        val forked = source.copy(
            id = UUID.randomUUID().toString(),
            title = source.title + " · 分叉",
            messages = messages,
            createdAt = now,
            updatedAt = now,
            parentChatId = source.id,
            pinned = false,
            locked = false
        )
        ChatHistoryManager.getInstance(context).saveChatHistory(forked)
        return forked
    }

    /**
     * 把会话回退到第 [keepMessageIndex] 条（含）为止。
     *
     * 调用方应先 [fork] 一份再回退，否则被截掉的内容只能靠备份找回。
     */
    suspend fun rewind(
        context: Context,
        source: ChatHistory,
        keepMessageIndex: Int
    ): ChatHistory {
        val messages = source.messages.take(
            (keepMessageIndex + 1).coerceIn(0, source.messages.size)
        )
        val rewound = source.copy(
            messages = messages,
            updatedAt = LocalDateTime.now()
        )
        ChatHistoryManager.getInstance(context).saveChatHistory(rewound)
        return rewound
    }
}
