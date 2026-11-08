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
            CccMessageItem(
                message = messages[index],
                enableDialogs = enableDialogs
            )
        }
    }
}
