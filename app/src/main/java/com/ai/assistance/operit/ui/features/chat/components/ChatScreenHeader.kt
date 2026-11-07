package com.ai.assistance.operit.ui.features.chat.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.floating.FloatingMode
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf

@Composable
fun useFloatingWindowLauncher(
    actualViewModel: ChatViewModel,
    permissionLauncher: ActivityResultLauncher<String>
): () -> Unit {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    return {
        actualViewModel.onFloatingButtonClick(
            FloatingMode.WINDOW,
            permissionLauncher,
            colorScheme,
            typography,
            moveTaskToBackOnReady = true
        )
    }
}

@Composable
fun ChatScreenHeader(
        modifier: Modifier = Modifier,
        actualViewModel: ChatViewModel,
        showChatHistorySelector: Boolean,
        chatHeaderHistoryIconColor: Int?,
        chatHeaderPipIconColor: Int?,
        onCharacterSwitcherClick: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    LaunchedEffect(actualViewModel, context) {
        actualViewModel.moveTaskToBackEvents.collect {
            (context as? android.app.Activity)?.moveTaskToBack(true)
        }
    }

    val characterCardManager = remember { CharacterCardManager.getInstance(context) }
    val characterGroupCardManager = remember { CharacterGroupCardManager.getInstance(context) }
    val activePromptManager = remember { ActivePromptManager.getInstance(context) }
    val userPreferencesManager = remember { UserPreferencesManager.getInstance(context) }
    val activePrompt by activePromptManager.activePromptFlow.collectAsState(
        initial = ActivePrompt.CharacterCard(CharacterCardManager.DEFAULT_CHARACTER_CARD_ID)
    )
    val activeCharacterCard by remember(activePrompt) {
        when (val prompt = activePrompt) {
            is ActivePrompt.CharacterCard -> characterCardManager.getCharacterCardFlow(prompt.id)
            is ActivePrompt.CharacterGroup -> flowOf(null)
        }
    }.collectAsState(initial = null)
    val activeCharacterGroup by remember(activePrompt) {
        when (val prompt = activePrompt) {
            is ActivePrompt.CharacterGroup -> characterGroupCardManager.getCharacterGroupCardFlow(prompt.id)
            is ActivePrompt.CharacterCard -> flowOf(null)
        }
    }.collectAsState(initial = null)
    val activeCardAvatarUri by remember(activeCharacterCard?.id) {
        activeCharacterCard?.id?.let { userPreferencesManager.getAiAvatarForCharacterCardFlow(it) }
            ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeGroupAvatarUri by remember(activeCharacterGroup?.id) {
        activeCharacterGroup?.id?.let { userPreferencesManager.getAiAvatarForCharacterGroupFlow(it) }
            ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeGroupFallbackMemberCardId = remember(activeCharacterGroup?.members) {
        val sortedMembers = activeCharacterGroup?.members?.sortedBy { it.orderIndex }.orEmpty()
        sortedMembers.firstOrNull()?.characterCardId
    }
    val activeGroupFallbackMemberAvatarUri by remember(activeGroupFallbackMemberCardId) {
        activeGroupFallbackMemberCardId?.let { userPreferencesManager.getAiAvatarForCharacterCardFlow(it) }
            ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeCharacterAvatarUri =
        when (activePrompt) {
            is ActivePrompt.CharacterGroup -> activeGroupAvatarUri ?: activeGroupFallbackMemberAvatarUri
            is ActivePrompt.CharacterCard -> activeCardAvatarUri
        }

    val activeStreamingChatIds by actualViewModel.activeStreamingChatIds.collectAsState()
    val isFloatingMode by actualViewModel.isFloatingMode.collectAsState()
    // iOS 化：Token 圆环已移除，这四个 collectAsState 不再有消费者，
    // 留着会让顶栏每帧都跟着 token 计数重组，一并删掉。

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                actualViewModel.launchWindowFloatingModeAfterMicPermissionGranted(
                    colorScheme = colorScheme,
                    typography = typography,
                    moveTaskToBackOnReady = true
                )
            } else {
                actualViewModel.showToast(context.getString(R.string.microphone_permission_denied))
            }
        }


    val launchFloatingWindow = useFloatingWindowLauncher(actualViewModel, permissionLauncher)

    Row(
            modifier =
                    modifier
                            .fillMaxWidth()
                            // iOS 化：顶部工具条不再自带一条浅灰底带，直接与页面同色
                            .background(Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChatHeader(
                showChatHistorySelector = showChatHistorySelector,
                onToggleChatHistorySelector = { actualViewModel.toggleChatHistorySelector() },
                modifier = Modifier.weight(1f),
                isFloatingMode = isFloatingMode,
                onLaunchFloatingWindow = launchFloatingWindow,
                historyIconColor = chatHeaderHistoryIconColor,
                pipIconColor = chatHeaderPipIconColor,
                runningTaskCount = activeStreamingChatIds.size,
                // 角色卡已停用：聊天头部不再展示角色名/头像入口
                activeCharacterName = "",
                activeCharacterAvatarUri = null,
                onCharacterClick = onCharacterSwitcherClick
        )

        // iOS 化：删除顶部 Token 圆环 + 下拉统计（Operit 特有，且极易被误认为加载中）
        // 统计信息仍可在会话设置里查看，这里不再占用顶栏。
    }
}

@Composable
fun StatItem(label: String, value: String, isHighlighted: Boolean = false) {
    Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
    ) {
        Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
                text = value,
                style =
                        MaterialTheme.typography.labelMedium.copy(
                                fontWeight =
                                        if (isHighlighted)
                                                androidx.compose.ui.text.font.FontWeight.Bold
                                        else androidx.compose.ui.text.font.FontWeight.Normal
                        ),
                color =
                        if (isHighlighted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
        )
    }
}
