package com.ai.assistance.operit.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 群聊成员:一个子智能体。
 *
 * 设计原则(对齐「成员提示词与主智能体相同」的约定):
 * - 成员不绑定角色卡、不携带独立提示词,只是「主提示词下的一个执行单元」;
 * - 因此这里只有 id + 显示名,没有任何人格/提示词字段;
 * - 执行时由 [com.ai.assistance.operit.core.agent.SubagentSpec] 承接任务,
 *   成员名仅用于看板展示与结果归属。
 */
@Serializable
data class GroupChatMember(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "成员",
) {
    companion object {
        /** 按序号生成默认成员名。 */
        fun defaultName(index: Int): String = "成员${index + 1}"
    }
}
