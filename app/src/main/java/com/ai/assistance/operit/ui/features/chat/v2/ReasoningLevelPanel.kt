package com.ai.assistance.operit.ui.features.chat.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 推理档位「悬浮岛」滑块：一个浮起的深色胶囊，内部是一条可拖动的 6 档滑块。
 *
 * - 标题居中显示当前档位名 + 右侧小箭头；右上角白色细线环形重置图标
 * - 深灰圆角轨道 + 5 个浅灰刻度点 + 纯白实心滑块 + 左侧高亮填充
 * - 6 档：轻度(最左·无填充) / 中 / 高 / 极高 / 最高(蓝填充) / Ultra(蓝紫渐变+星光)
 */
@Composable
fun ReasoningLevelIsland(
    currentLevel: Int,
    onLevelChange: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titles = listOf("轻度", "中", "高", "极高", "最高", "Ultra")
    val title = titles.getOrElse(currentLevel) { titles.first() }
    val titleColor =
        if (currentLevel >= titles.lastIndex) LevelColors.TitleUltra else LevelColors.TitleBlue
    val starry = currentLevel >= titles.lastIndex

    Column(
        modifier =
            modifier
                .width(320.dp)
                .shadow(20.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(LevelColors.IslandBackground)
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
                .padding(horizontal = 22.dp, vertical = 18.dp)
    ) {
        // ── 标题行：居中当前档位 + 箭头；右上角重置 ──
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

        Spacer(modifier = Modifier.height(16.dp))

        ReasoningSlider(
            currentLevel = currentLevel,
            onLevelChange = onLevelChange,
            starry = starry,
        )
    }
}

private object LevelColors {
    val IslandBackground = Color(0xFF1C1C1E)
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
private val levelFractions = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 0.95f)

private fun fractionToLevel(f: Float): Int =
    levelFractions.indices.minByOrNull { kotlin.math.abs(levelFractions[it] - f) } ?: 0

@Composable
private fun ReasoningSlider(
    currentLevel: Int,
    onLevelChange: (Int) -> Unit,
    starry: Boolean,
) {
    val trackHeight = 6.dp
    val thumbRadius = 11.dp
    val tickRadius = 2.5.dp
    val fraction = levelFractions.getOrElse(currentLevel) { 0f }

    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onLevelChange(fractionToLevel(f))
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val f = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onLevelChange(fractionToLevel(f))
                    }
                }
    ) {
        val trackY = size.height / 2f
        val thumbPx = thumbRadius.toPx()
        val left = thumbPx
        val right = size.width - thumbPx
        val trackTop = trackY - trackHeight.toPx() / 2f
        val trackWidth = right - left
        val thumbX = left + fraction * trackWidth
        val corner = CornerRadius(trackHeight.toPx() / 2f, trackHeight.toPx() / 2f)

        // 轨道
        drawRoundRect(
            color = LevelColors.Track,
            topLeft = Offset(left, trackTop),
            size = Size(trackWidth, trackHeight.toPx()),
            cornerRadius = corner,
        )

        // 左侧高亮填充
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

        // 5 个刻度点
        for (i in 1..5) {
            val frac = i / 5f
            val x = left + frac * trackWidth
            drawCircle(color = LevelColors.Tick, radius = tickRadius.toPx(), center = Offset(x, trackY))
        }

        // 纯白滑块
        drawCircle(color = LevelColors.Thumb, radius = thumbPx, center = Offset(thumbX, trackY))
    }
}
