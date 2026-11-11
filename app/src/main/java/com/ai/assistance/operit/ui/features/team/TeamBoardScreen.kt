package com.ai.assistance.operit.ui.features.team

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.assistance.operit.data.model.GroupChatMember
import com.ai.assistance.operit.data.repository.GroupChatRepository
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.core.agent.AgentTeam
import com.ai.assistance.operit.core.agent.SubagentCapability
import com.ai.assistance.operit.core.agent.SubagentSpec
import com.ai.assistance.operit.core.agent.TeamStatus
import com.ai.assistance.operit.core.agent.TeamTicket
import com.ai.assistance.operit.core.agent.TicketStatus
import com.ai.assistance.operit.ui.components.CustomScaffold

/**
 * 团队看板视图：展示当前活跃智能体团队的目标、整体状态与工作项看板。
 *
 * 工作项按状态分组呈现；观察模式下（AWAITING_REVIEW）提供「确认放行 / 拒绝」操作。
 */
@Composable
fun TeamBoardScreen(
    onGoBack: () -> Unit,
    viewModel: TeamBoardViewModel = viewModel()
) {
    val context = LocalContext.current
    val teams: List<AgentTeam> by viewModel.teams.collectAsState()
    val activeTeamId: String? by viewModel.activeTeamId.collectAsState()
    val busy: Boolean by viewModel.busy.collectAsState()

    val activeTeam: AgentTeam? = teams.find { it.id == activeTeamId } ?: teams.firstOrNull()

    // ── 群聊成员管理区块 ────────────────────────────────────────────
    val chatHistoryManager = remember { ChatHistoryManager.getInstance(context) }
    val currentChatId by chatHistoryManager.currentChatIdFlow.collectAsState(initial = null)
    val chatHistories by chatHistoryManager.chatHistoriesFlow.collectAsState(initial = emptyList())
    // 委托属性（by collectAsState）无法 smart cast，先落到局部变量再判空比较
    val currentChatIdValue = currentChatId
    val isGroupChat = currentChatIdValue != null &&
        chatHistories.firstOrNull { it.id == currentChatIdValue }?.isGroupChat == true
    val groupChatRepository = remember { GroupChatRepository.getInstance(context) }
    var groupMembers by remember(currentChatId) { mutableStateOf<List<GroupChatMember>>(emptyList()) }
    LaunchedEffect(currentChatIdValue, isGroupChat) {
        if (currentChatIdValue != null && isGroupChat) {
            groupMembers = groupChatRepository.members(currentChatIdValue)
        } else {
            groupMembers = emptyList()
        }
    }

    CustomScaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onGoBack) {
                    Text("‹")
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "智能体团队",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (busy) {
                        Text(
                            text = "编排中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 群聊成员管理区块(仅群聊会话显示)
            if (isGroupChat) {
                GroupChatMemberPanel(
                    members = groupMembers,
                    onSummon = { viewModel.summonMember(context) },
                    onRemove = { memberId -> viewModel.removeMember(context, memberId) },
                )
            }

            // 团队看板
            if (activeTeam == null) {
                EmptyState(
                    modifier = Modifier.fillMaxSize(),
                    busy = busy
                )
            } else {
                TeamBoard(
                    team = activeTeam,
                    modifier = Modifier.fillMaxSize(),
                    onApprove = viewModel::approve,
                    onReject = viewModel::reject
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, busy: Boolean) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在拆解任务并派发子智能体…")
            } else {
                Text(
                    text = "暂无团队任务",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "团队任务由主智能体拆解后自动出现在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TeamBoard(
    team: AgentTeam,
    modifier: Modifier,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    val tickets by team.tickets.collectAsState()
    val status by team.status.collectAsState()

    Column(modifier = modifier) {
        // 目标与状态头
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = team.goal,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusLabel(status),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(status)
            )
        }

        // 工作项看板
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tickets, key = { it.id }) { ticket ->
                TicketCard(
                    ticket = ticket,
                    onApprove = { onApprove(ticket.id) },
                    onReject = { onReject(ticket.id) }
                )
            }
        }
    }
}

@Composable
private fun TicketCard(
    ticket: TeamTicket,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(ticket.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = ticket.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(4.dp))

            // 能力标签
            val caps = ticket.spec.capabilities
            if (caps.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    caps.forEach { cap ->
                        CapabilityChip(cap)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // 结果内容
            ticket.result?.let { r ->
                if (r.content.isNotBlank()) {
                    Text(
                        text = r.content,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            ticket.error?.let { err ->
                Text(
                    text = "错误：$err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
            }

            // 观察模式操作
            if (ticket.status == TicketStatus.AWAITING_REVIEW) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApprove) { Text("确认放行") }
                    TextButton(onClick = onReject) { Text("拒绝") }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: TicketStatus) {
    val bg = statusColor(status)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = ticketStatusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = bg,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun CapabilityChip(cap: SubagentCapability) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = capabilityLabel(cap),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

private fun ticketStatusLabel(status: TicketStatus): String = when (status) {
    TicketStatus.PENDING -> "排队"
    TicketStatus.RUNNING -> "执行中"
    TicketStatus.AWAITING_REVIEW -> "待确认"
    TicketStatus.BLOCKED -> "阻塞"
    TicketStatus.DONE -> "完成"
    TicketStatus.FAILED -> "失败"
}

private fun statusLabel(status: TeamStatus): String = when (status) {
    TeamStatus.IDLE -> "空闲"
    TeamStatus.RUNNING -> "执行中"
    TeamStatus.AWAITING_REVIEW -> "等待确认"
    TeamStatus.BLOCKED -> "有阻塞"
    TeamStatus.DONE -> "已完成"
    TeamStatus.FAILED -> "失败"
}

private fun statusColor(status: TeamStatus): androidx.compose.ui.graphics.Color = when (status) {
    TeamStatus.RUNNING -> androidx.compose.ui.graphics.Color(0xFF4A90D9)
    TeamStatus.AWAITING_REVIEW -> androidx.compose.ui.graphics.Color(0xFFE0A030)
    TeamStatus.BLOCKED -> androidx.compose.ui.graphics.Color(0xFFD94A4A)
    TeamStatus.DONE -> androidx.compose.ui.graphics.Color(0xFF3BA55D)
    TeamStatus.FAILED -> androidx.compose.ui.graphics.Color(0xFFD94A4A)
    TeamStatus.IDLE -> androidx.compose.ui.graphics.Color(0xFF888888)
}

private fun statusColor(status: TicketStatus): androidx.compose.ui.graphics.Color = when (status) {
    TicketStatus.PENDING -> androidx.compose.ui.graphics.Color(0xFF888888)
    TicketStatus.RUNNING -> androidx.compose.ui.graphics.Color(0xFF4A90D9)
    TicketStatus.AWAITING_REVIEW -> androidx.compose.ui.graphics.Color(0xFFE0A030)
    TicketStatus.BLOCKED -> androidx.compose.ui.graphics.Color(0xFFD94A4A)
    TicketStatus.DONE -> androidx.compose.ui.graphics.Color(0xFF3BA55D)
    TicketStatus.FAILED -> androidx.compose.ui.graphics.Color(0xFFD94A4A)
}

private fun capabilityLabel(cap: SubagentCapability): String = when (cap) {
    SubagentCapability.READ_ONLY -> "只读"
    SubagentCapability.USE_TOOLS -> "工具"
    SubagentCapability.WRITE_FILE -> "写文件"
    SubagentCapability.RUN_COMMAND -> "跑命令"
}


/**
 * 群聊成员管理面板:显示当前群聊成员,提供「召唤成员」与「删除成员」。
 */
@Composable
private fun GroupChatMemberPanel(
    members: List<GroupChatMember>,
    onSummon: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "群聊成员（" + members.size + "）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSummon) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("召唤成员")
                }
            }

            if (members.isEmpty()) {
                Text(
                    text = "暂无成员，点击「召唤成员」添加",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                members.forEach { member ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = member.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onRemove(member.id) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除成员",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
