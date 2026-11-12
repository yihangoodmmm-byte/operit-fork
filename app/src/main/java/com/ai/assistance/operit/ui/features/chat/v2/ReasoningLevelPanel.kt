package com.ai.assistance.operit.ui.features.chat.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 推理档位面板：纯黑底、垂直均匀排列 6 组同规格滑块行，极简原生设置质感。
 *
 * 每组结构（自上而下）：
 *   1. 居中标题 + 右侧小箭头；右上角白色细线环形重置图标
 *   2. 深灰圆角滑块轨道 + 5 个浅灰刻度点 + 纯白实心滑块 + 左侧高亮填充
 *
 * 档位（标题 / 滑块位置 / 填充）：
 *   轻度  最左端   无填充
 *   中    刻度1    明亮纯蓝
 *   高    刻度2    明亮纯蓝
 *   极高  刻度3    明亮纯蓝
 *   最高  刻度4    明亮纯蓝
 *   Ultra 接近右端  蓝紫渐变 + 白色星光
 */
@Composable
fun ReasoningLevelPanel(
    currentLevel: Int = 0,
    onSelectLevel: (Int) -> Unit = {},
    onResetLevel: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val levels =
        listOf(
            "轻度" to LevelStyle.BLUE,
            "中" to LevelStyle.BLUE,
            "高" to LevelStyle.BLUE,
            "极高" to LevelStyle.BLUE,
            "最高" to LevelStyle.BLUE,
            "Ultra" to LevelStyle.ULTRA,
        )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(LevelColors.PanelBackground)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        levels.forEachIndexed { index, (title, style) ->
            ReasoningLevelSliderRow(
                title = title,
                style = style,
                levelIndex = index,
                onClick = { onSelectLevel(index) },
                onReset = { onResetLevel(index) },
            )
        }
    }
}

private enum class LevelStyle { BLUE, ULTRA }

private object LevelColors {
    val PanelBackground = Color(0xFF000000)
    val Track = Color(0xFF2C2C2E)
    val Tick = Color(0xFF8E8E93)
    val Thumb = Color(0xFFFFFFFF)
    val TitleBlue = Color(0xFF4DA6FF)
    val TitleUltra = Color(0xFFBF5AF2)
    val FillBlue = Color(0xFF0A84FF)
    val FillGradientStart = Color(0xFF0A84FF)
    val FillGradientEnd = Color(0xFFBF5AF2)
}

/** 6 档对应的滑块位置（占轨道可滑动区间的比例）。 */
private fun thumbFraction(levelIndex: Int): Float =
    when (levelIndex) {
        0 -> 0f
        1 -> 0.2f
        2 -> 0.4f
        3 -> 0.6f
        4 -> 0.8f
        else -> 0.95f
    }

@Composable
private fun ReasoningLevelSliderRow(
    title: String,
    style: LevelStyle,
    levelIndex: Int,
    onClick: () -> Unit,
    onReset: () -> Unit,
) {
    val titleColor =
        when (style) {
            LevelStyle.BLUE -> LevelColors.TitleBlue
            LevelStyle.ULTRA -> LevelColors.TitleUltra
        }

    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        // ── 标题行：居中标题 + 右侧小箭头；右上角重置图标 ──
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    color = titleColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = titleColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(26.dp)
                        .clip(CircleShape)
                        .border(width = 1.dp, color = Color.White.copy(alpha = 0.4f), shape = CircleShape)
                        .clickable(onClick = onReset),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        ReasoningTrack(levelIndex = levelIndex, style = style)
    }
}

@Composable
private fun ReasoningTrack(levelIndex: Int, style: LevelStyle) {
    val trackHeight = 6.dp
    val thumbRadius = 11.dp
    val tickRadius = 2.5.dp
    val fraction = thumbFraction(levelIndex)
    val starry = style == LevelStyle.ULTRA

    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(28.dp)
    ) {
        val trackY = size.height / 2f
        val thumbPx = thumbRadius.toPx()
        val left = thumbPx
        val right = size.width - thumbPx
        val trackTop = trackY - trackHeight.toPx() / 2f
        val trackWidth = right - left
        val thumbX = left + fraction * trackWidth
        val corner = CornerRadius(trackHeight.toPx() / 2f, trackHeight.toPx() / 2f)

        // 轨道（深灰圆角长条）
        drawRoundRect(
            color = LevelColors.Track,
            topLeft = Offset(left, trackTop),
            size = Size(trackWidth, trackHeight.toPx()),
            cornerRadius = corner,
        )

        // 滑块左侧高亮填充
        if (fraction > 0f) {
            val fillWidth = thumbX - left
            if (starry) {
                drawRoundRect(
                    brush =
                        Brush.horizontalGradient(
                            colors = listOf(LevelColors.FillGradientStart, LevelColors.FillGradientEnd),
                            startX = left,
                            endX = thumbX,
                        ),
                    topLeft = Offset(left, trackTop),
                    size = Size(fillWidth, trackHeight.toPx()),
                    cornerRadius = corner,
                )
                // 星光点：渐变区域内散布的细碎白点，固定种子保证稳定不闪烁
                val random = kotlin.random.Random(42)
                repeat(30) {
                    val x = left + random.nextFloat() * fillWidth
                    val y = trackTop + random.nextFloat() * trackHeight.toPx()
                    val r = 0.6f + random.nextFloat() * 1.3f
                    val a = 0.35f + random.nextFloat() * 0.6f
                    drawCircle(Color.White.copy(alpha = a), radius = r, center = Offset(x, y))
                }
            } else {
                drawRoundRect(
                    color = LevelColors.FillBlue,
                    topLeft = Offset(left, trackTop),
                    size = Size(fillWidth, trackHeight.toPx()),
                    cornerRadius = corner,
                )
            }
        }

        // 5 个浅灰刻度点（均匀分布）
        for (i in 1..5) {
            val frac = i / 5f
            val x = left + frac * trackWidth
            drawCircle(color = LevelColors.Tick, radius = tickRadius.toPx(), center = Offset(x, trackY))
        }

        // 纯白实心滑块
        drawCircle(color = LevelColors.Thumb, radius = thumbPx, center = Offset(thumbX, trackY))
    }
}
