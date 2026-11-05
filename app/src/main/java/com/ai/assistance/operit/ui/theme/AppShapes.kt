package com.ai.assistance.operit.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 全局形状（CC 古典控件尺度）。
 *
 * 圆角在整个界面里成体系：小控件、卡片、对话框按同一套比例递增，而不是各写各的 dp。
 * Material3 的组件全部读 Shapes 语义名，这里定义一次就作用于全项目的
 * Card / Dialog / BottomSheet / OutlinedTextField。
 *
 * 从原来的 6/10/14/18/24 收紧到 8/9/10/12/14：CC 的控件圆角集中在 8–10dp，
 * 配合 1dp 细描边，圆角一大就显得圆胖。
 *
 * 注意：M3 1.4 的 Button 默认形状在库里硬编码为 CircleShape
 * （`ShapeKeyTokens.CornerFull` → `getCircleShape()`），**不受 Shapes 影响**；
 * 改按钮圆角只能逐个传 `shape`。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(14.dp),
)
