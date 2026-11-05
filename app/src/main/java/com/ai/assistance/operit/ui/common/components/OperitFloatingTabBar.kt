package com.ai.assistance.operit.ui.common.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底部悬浮胶囊 Tab 的一条。
 *
 * 出处：视觉层移植自 AiCode（jieapi/aicode，GPL-3.0）core/ui/FloatingTabBar.kt。
 * 去掉了它与 PagerState 的联动（Operit 是路由式导航，没有 pager），
 * 保留：胶囊外形、渐变玻璃底、三段渐变描边、按下缩放、选中指示。
 */
data class OperitTabItem(
    val icon: ImageVector,
    val label: String,
    val key: String
)

/**
 * 底部悬浮液态玻璃 Tab 栏（路由版）。
 *
 * 形态要点（对齐 AiCode）：
 * - 胶囊外形，宽度收在 420dp 内、水平居中、距底 12dp；
 * - 底色是主面→次面的竖向渐变（浅色 0.94/0.90，深色 0.92/0.88），**不是纯色**；
 * - 1dp 描边：浅色用黑 0.12/0.06/0.14 三段渐变，深色用白 0.10/0.06；
 * - 选中项有一枚淡淡的强调色胶囊，图标与文案同时变色；
 * - 未选中项缩到 0.97（AiCode 的按下缩放语义）。
 */
@Composable
fun OperitFloatingTabBar(
    items: List<OperitTabItem>,
    currentKey: String,
    onSelect: (OperitTabItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val isLight = cs.background.luminance() > 0.5f
    val capsule = RoundedCornerShape(999.dp)

    val glassBrush = remember(isLight, cs.surface, cs.surfaceVariant) {
        if (isLight) {
            Brush.verticalGradient(
                listOf(cs.surface.copy(alpha = 0.94f), cs.surfaceVariant.copy(alpha = 0.90f))
            )
        } else {
            Brush.verticalGradient(
                listOf(cs.surfaceVariant.copy(alpha = 0.92f), cs.surface.copy(alpha = 0.88f))
            )
        }
    }
    val borderBrush = remember(isLight) {
        if (isLight) {
            Brush.verticalGradient(
                listOf(
                    Color.Black.copy(alpha = 0.12f),
                    Color.Black.copy(alpha = 0.06f),
                    Color.Black.copy(alpha = 0.14f)
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.06f))
            )
        }
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .widthIn(max = 420.dp)
                .height(56.dp)
                .shadow(elevation = 10.dp, shape = capsule, clip = false)
                .clip(capsule)
                .background(glassBrush)
                .border(width = 1.dp, brush = borderBrush, shape = capsule)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val selected = item.key == currentKey
                val tint by animateColorAsState(
                    targetValue = if (selected) cs.primary else cs.onSurfaceVariant,
                    animationSpec = tween(150),
                    label = "operit-tab-tint"
                )
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.97f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                    label = "operit-tab-scale"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(capsule)
                        .background(if (selected) cs.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = tint,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = item.label,
                            color = tint,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
