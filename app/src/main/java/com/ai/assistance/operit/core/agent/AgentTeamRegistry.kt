package com.ai.assistance.operit.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 活跃智能体团队注册表（进程级单例）。
 *
 * 桥接两个世界：
 * - 工具执行器（[AgentTeamOrchestrator] 被主模型的工具调用触发）把创建的 [AgentTeam] 注册进来；
 * - 团队看板 ViewModel 从这里读取/订阅，实时显示团队进度。
 *
 * 这是「主智能体自动 spawn → 看板实时展示」的关键接线点。
 */
object AgentTeamRegistry {

    private val _teams = MutableStateFlow<List<AgentTeam>>(emptyList())
    val teams: StateFlow<List<AgentTeam>> = _teams.asStateFlow()

    private val _activeTeamId = MutableStateFlow<String?>(null)
    val activeTeamId: StateFlow<String?> = _activeTeamId.asStateFlow()

    /** 注册（或替换同 id）一个团队，并设为活跃。 */
    fun register(team: AgentTeam) {
        _teams.value = listOf(team) + _teams.value.filter { it.id != team.id }
        _activeTeamId.value = team.id
    }

    fun unregister(teamId: String) {
        _teams.value = _teams.value.filter { it.id != teamId }
        if (_activeTeamId.value == teamId) {
            _activeTeamId.value = _teams.value.firstOrNull()?.id
        }
    }

    fun get(teamId: String): AgentTeam? = _teams.value.find { it.id == teamId }

    fun activeTeam(): AgentTeam? = _activeTeamId.value?.let { get(it) }

    fun setActive(teamId: String?) {
        _activeTeamId.value = teamId
    }

    fun clear() {
        _teams.value = emptyList()
        _activeTeamId.value = null
    }
}
