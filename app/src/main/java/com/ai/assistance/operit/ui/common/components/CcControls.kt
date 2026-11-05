package com.ai.assistance.operit.ui.common.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CC 古典控件集。
 *
 * 形态取自 cccodroid 0.4.10（Apache-2.0）gateway/web/style.css：
 * `.btn` / `.btn.ghost` / `.btn.sm` / `.field input` / `.tabs` / `.chip` / `.icon-btn` / `input.sw`。
 * 只搬**控件形态**（描边、圆角、内边距、字重、动效时长），颜色一律走当前 [MaterialTheme] 的角色，
 * 因此换任何一套配色（iOS 白/黑、Claude、AiCode 蓝、CC 暖灰）都自动成立。
 *
 * 三条与 Material 默认控件的分歧：
 * 1. **层次靠线不靠影**：1dp 描边，全程不用 elevation 做分层；
 * 2. **层次靠色阶不靠透明度**：background → surface → surfaceVariant → capsule 递进，不叠 alpha；
 * 3. **反馈靠明暗不靠动效**：按下 alpha 0.85 + 150ms，没有水波纹、没有缩放、没有弹簧。
 */

private val CcRadiusControl = 9.dp
private val CcRadiusTrack = 10.dp
private val CcRadiusSegment = 8.dp
private val CcRadiusIcon = 10.dp
private const val CC_PRESS_MS = 150

/** 胶囊色（对应 cccodroid 的 --bg4）：在 surfaceVariant 基础上朝正文色压 7%。 */
private val capsuleColor: Color
    @Composable get() = lerp(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurface,
        0.07f
    )

private fun Modifier.ccOutline(shape: Shape, color: Color, width: Dp = 1.dp): Modifier =
    border(width = width, color = color, shape = shape)

enum class CcButtonVariant { Primary, Ghost, Danger }
enum class CcButtonSize { Normal, Small }

/**
 * 古典按钮。
 *
 * - Primary：强调色实底 + onPrimary 文字，无边框无阴影；
 * - Ghost：透明底 + 1dp 描边 + 次要文字色；
 * - Danger：幽灵底 + 错误色文字，描边取错误色 45%（等价 cc 的 color-mix）。
 */
@Composable
fun CcButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: CcButtonVariant = CcButtonVariant.Primary,
    size: CcButtonSize = CcButtonSize.Normal,
    enabled: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val container = if (variant == CcButtonVariant.Primary) cs.primary else Color.Transparent
    val content = when (variant) {
        CcButtonVariant.Primary -> cs.onPrimary
        CcButtonVariant.Ghost -> cs.onSurfaceVariant
        CcButtonVariant.Danger -> cs.error
    }
    val borderColor = when (variant) {
        CcButtonVariant.Primary -> null
        CcButtonVariant.Ghost -> cs.outlineVariant
        CcButtonVariant.Danger -> cs.error.copy(alpha = 0.45f)
    }
    val shape = RoundedCornerShape(CcRadiusControl)
    val padH = if (size == CcButtonSize.Small) 12.dp else 14.dp
    val padV = if (size == CcButtonSize.Small) 7.dp else 10.dp
    val fontSize = if (size == CcButtonSize.Small) 12.5.sp else 14.sp

    Box(
        modifier = modifier
            .alpha(if (!enabled) 0.4f else if (pressed) 0.85f else 1f)
            .clip(shape)
            .background(container, shape)
            .then(if (borderColor != null) Modifier.ccOutline(shape, borderColor) else Modifier)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = padH, vertical = padV),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = content, fontSize = fontSize, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 分段控件：轨道 10dp 圆角 + 3dp 内边距，选中段 8dp 圆角实色填充，
 * 150ms 线性位移——无弹簧、无投影。
 */
@Composable
fun CcSegmentedTabs(
    selected: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (labels.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val trackShape = RoundedCornerShape(CcRadiusTrack)
    val segmentShape = RoundedCornerShape(CcRadiusSegment)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(trackShape)
            .background(cs.background)
            .padding(3.dp)
            .alpha(if (enabled) 1f else 0.45f)
    ) {
        val segmentWidth = maxWidth / labels.size
        val offsetX by animateDpAsState(
            targetValue = segmentWidth * selected,
            animationSpec = tween(durationMillis = CC_PRESS_MS),
            label = "cc-segment-offset"
        )
        Box(
            modifier = Modifier
                .offset(x = offsetX)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(segmentShape)
                .background(capsuleColor)
        )
        Row(modifier = Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                val isSelected = index == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(segmentShape)
                        .then(
                            if (enabled) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onSelect(index) }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) cs.onSurface else cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 等宽标签：1dp 描边 + 8dp 圆角，用于路径、模型名、状态短标签。 */
@Composable
fun CcChip(
    text: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(CcRadiusSegment)
    Box(
        modifier = modifier
            .clip(shape)
            .background(cs.background, shape)
            .ccOutline(
                shape,
                if (highlight) cs.primary.copy(alpha = 0.45f) else cs.outlineVariant
            )
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            color = if (highlight) cs.primary else cs.onSurfaceVariant
        )
    }
}

/** 图标按钮：38dp 方形、10dp 圆角、透明底，按下铺胶囊色。 */
@Composable
fun CcIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) capsuleColor else Color.Transparent,
        animationSpec = tween(durationMillis = CC_PRESS_MS),
        label = "cc-icon-bg"
    )
    val shape = RoundedCornerShape(CcRadiusIcon)
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .size(38.dp)
            .clip(shape)
            .background(background, shape)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}

/**
 * 古典开关：42×24、滑块 18、行程 3→21、150ms 线性。
 *
 * 对比 Material3 默认 Switch：更窄（42 vs 52）、滑块更小、无过冲。
 *
 * **签名刻意做成 M3 `Switch` 的超集**（多出 `thumbContent` / `colors` / `interactionSource`，
 * 三者都接受但忽略），这样全项目的 `Switch(...)` 可以机械替换成 `CcSwitch(...)` 而不必逐个改参数。
 * 忽略 `colors` 是有意的：CC 风格要求颜色统一从主题 role 取，个别页面自定义的开关配色会被主题色取代。
 */
@Composable
fun CcSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null
) {
    val cs = MaterialTheme.colorScheme
    val trackColor by animateColorAsState(
        targetValue = if (checked) cs.primary else capsuleColor,
        animationSpec = tween(durationMillis = CC_PRESS_MS),
        label = "cc-switch-track"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 21.dp else 3.dp,
        animationSpec = tween(durationMillis = CC_PRESS_MS),
        label = "cc-switch-thumb"
    )
    // 开态滑块用 onPrimary：强调色越浅滑块越深，任何配色下都看得见
    val thumbColor = if (checked) cs.onPrimary else Color.White

    val toggle = if (onCheckedChange != null && enabled) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onCheckedChange(!checked) }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .then(toggle)
            .size(width = 42.dp, height = 24.dp)
            .clip(CircleShape)
            .background(trackColor),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(18.dp)
                .shadow(elevation = 1.dp, shape = CircleShape)
                .background(thumbColor, CircleShape)
        )
    }
}
/**
 * 圆角 9dp 的 M3 按钮（**直通**，只改默认形状）。
 *
 * 与上面那个自绘的 `CcButton(text, …)` 是两个重载：这里从 `onClick` 起头，签名与 M3 `Button` 一致，
 * 内部原样委托，只把默认 `shape` 从库里的 CircleShape 换成 9dp。
 * 因此全项目 `Button(...)` → `CcButton(...)` 是**语义等价**的机械替换：
 * 自定义的 colors / elevation / border / contentPadding 全部照传，不会丢。
 *
 * 为什么需要它：M3 1.4 把 `ShapeKeyTokens.CornerFull` 硬编码成 `getCircleShape()`，
 * 改 `MaterialTheme.shapes` 对按钮无效，只能逐个传 shape——这个重载就是那次替换的落点。
 */
@Composable
fun CcButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(CcRadiusControl),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

/** 圆角 9dp 的 M3 OutlinedButton（直通，只改默认形状）。 */
@Composable
fun CcOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(CcRadiusControl),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

/** 圆角 9dp 的 M3 FilledTonalButton（直通，只改默认形状）。 */
@Composable
fun CcFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(CcRadiusControl),
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}
