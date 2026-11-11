package com.ai.assistance.operit.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.model.GroupChatMember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.groupChatDataStore: DataStore<Preferences> by preferencesDataStore(name = "group_chat_members")

/**
 * 群聊成员持久化仓库。
 *
 * 用轻量 DataStore(JSON 序列化)存每个群聊会话的成员列表,不新增 Room 表。
 * 键格式:group_chat_members_<chatId> -> JSON 数组。
 *
 * 为什么不用 Room:成员列表是动态小集合,增删频繁但总量小;
 * 单独建表 + DAO + 迁移的代价远大于收益。DataStore 足够。
 */
class GroupChatRepository private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: GroupChatRepository? = null

        fun getInstance(context: Context): GroupChatRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: GroupChatRepository(context.applicationContext).also { INSTANCE = it }
            }

        private const val KEY_PREFIX = "group_chat_members_"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun key(chatId: String) = stringPreferencesKey(KEY_PREFIX + chatId)

    /** 观察某群聊的成员列表(实时)。 */
    fun membersFlow(chatId: String): Flow<List<GroupChatMember>> =
        context.groupChatDataStore.data.map { prefs ->
            decode(prefs[key(chatId)])
        }

    /** 读取某群聊当前成员(一次性)。 */
    suspend fun members(chatId: String): List<GroupChatMember> =
        membersFlow(chatId).first()

    /** 覆盖写入成员列表。 */
    suspend fun setMembers(chatId: String, members: List<GroupChatMember>) {
        context.groupChatDataStore.edit { prefs ->
            prefs[key(chatId)] = json.encodeToString(ListSerializer(GroupChatMember.serializer()), members)
        }
    }

    /** 追加一个成员(群聊里「召唤成员」)。 */
    suspend fun addMember(chatId: String, member: GroupChatMember) {
        val current = members(chatId)
        setMembers(chatId, current + member)
    }

    /** 删除一个成员(群聊里「删除成员」)。 */
    suspend fun removeMember(chatId: String, memberId: String) {
        val current = members(chatId)
        setMembers(chatId, current.filterNot { it.id == memberId })
    }

    private fun decode(raw: String?): List<GroupChatMember> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(GroupChatMember.serializer()), raw)
        }.getOrElse { emptyList() }
    }
}
