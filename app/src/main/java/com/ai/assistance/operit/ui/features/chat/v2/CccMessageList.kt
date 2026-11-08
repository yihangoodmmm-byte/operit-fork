package com.ai.assistance.operit.ui.features.chat.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.model.ChatMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 消息列表（照 cccodroid 的 `#chat` 容器移植）。
 *
 * cccodroid 的列表是**文档流**：`#chat{padding:14px 12px 8px}`，
 * 每条 `.msg` 之间固定 16px，没有卡片、没有分组容器、没有外层边框。
 *
 * Operit 原来的列表外面套了好几层（分段窗口 + 空状态 + 加载态 + 边缘渐隐），
 * 消息之间还有名字/时间/气泡/操作条的堆叠，导致整体松散。
 *
 * 这里只保留：列表 + 统一间距 + 首尾内边距。
 */
@Composable
fun CccMessageList(
    messages: List<ChatMessage>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    enableDialogs: Boolean = true,
    /**
     * 输入栏高度。列表是垫在输入栏下面的，必须自己让出这块高度，
     * 否则最后一条消息会被输入栏盖住（这是替换 ChatScreenContent 后丢掉的职责）。
     */
    bottomInset: Dp = 0.dp,
    horizontalPadding: Dp = CccTokens.ListPaddingHorizontal,
    topPadding: Dp = CccTokens.ListPaddingTop,
    bottomPadding: Dp = CccTokens.ListPaddingBottom,
) {
    if (messages.isEmpty()) {
        // 空会话不能是一片纯空白：那是最容易被识别成「没做完」的状态。
        // cccodroid 在这里放一个居中的欢迎块；这里保持同样克制——一行标题 + 一行说明。
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 80.dp)
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "开始新的对话",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "直接提问，或把文件拖进来",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding =
            PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = topPadding,
                bottom = bottomPadding + bottomInset
            ),
        verticalArrangement = Arrangement.spacedBy(CccTokens.MessageGap)
    ) {
        // 用 count 重载而不是 items(list)：避免依赖 List<ChatMessage> 的类型推断，
        // 同时保持 key 稳定（timestamp 唯一），滚动位置不会因重组跳变。
        items(
            count = messages.size,
            key = { index -> messages[index].timestamp }
        ) { index ->
            val message = messages[index]
            // 连续的同角色消息只在第一条画角色行（否则长对话里每段都顶一个「● Operit」）
            val previous = messages.getOrNull(index - 1)
            val showRole = previous == null || previous.sender != message.sender
            CccMessageItem(
                message = message,
                enableDialogs = enableDialogs,
                showRole = showRole
            )
        }
    }
}
