package com.ai.assistance.operit.ui.features.team

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.core.agent.AgentTeam
import com.ai.assistance.operit.core.agent.AgentTeamOrchestrator
import com.ai.assistance.operit.core.agent.AgentTeamRegistry
import com.ai.assistance.operit.core.agent.SubagentSpec
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 团队看板 ViewModel。
 *
 * 数据源是 [AgentTeamRegistry]（进程级单例），这样：
 * - 主智能体通过工具调用触发编排（注册到 registry）→ 看板实时可见；
 * - 手动/演示入口（[orchestrate]）也走同一条链路。
 */
class TeamBoardViewModel : ViewModel() {

    /** 团队列表：直接订阅注册表，团队状态变化自动刷新。 */
    val teams: StateFlow<List<AgentTeam>> = AgentTeamRegistry.teams
    val activeTeamId: StateFlow<String?> = AgentTeamRegistry.activeTeamId

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * 手动发起一次团队编排（演示/兜底入口）。
     *
     * @param goal 团队目标。
     * @param specs 拆解出的子任务规格。
     * @param observeByDefault 是否默认观察模式。
     */
    fun orchestrate(
        context: Context,
        goal: String,
        specs: List<SubagentSpec>,
        observeByDefault: Boolean = false
    ) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                AgentTeamOrchestrator.orchestrate(
                    context = context,
                    goal = goal,
                    specs = specs,
                    observeByDefault = observeByDefault
                )
                // 注册表已由 orchestrator 更新，无需再手动写列表
            } catch (e: Exception) {
                AppLogger.e(TAG, "团队编排失败", e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun setActiveTeam(id: String?) {
        AgentTeamRegistry.setActive(id)
    }

    /** 观察模式：确认放行某工作项。 */
    fun approve(ticketId: String) {
        AgentTeamRegistry.activeTeam()?.approve(ticketId)
    }

    /** 观察模式：拒绝某工作项（转阻塞）。 */
    fun reject(ticketId: String) {
        AgentTeamRegistry.activeTeam()?.reject(ticketId)
    }

    companion object {
        private const val TAG = "TeamBoardViewModel"
    }
}
