package com.ai.assistance.operit.ui.theme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

val LocalLiquidGlassBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * 全局开关：true 时所有玻璃修饰符退化为纯色。
 *
 * 产品方向是"去玻璃、纯色"。这里集中一个开关而不是删除实现，理由是：
 * 一是改动可回滚；二是 glass 相关代码里有大量平台分支，直接删会牵连 28 个调用点。
 */
internal const val PlainSurfaceMode = true

/**
 * 纯色模式下的描边颜色：按容器明暗给一档对方色的弱描边。
 * 玻璃时代靠高光边缘分层，纯色时代必须有一根细线，否则块与块之间会糊在一起。
 */
@Composable
internal fun PlainSurfaceBorderColor(containerColor: Color): Color =
    if (containerColor.luminance() >= 0.5f) {
        Color.Black.copy(alpha = 0.10f)
    } else {
        Color.White.copy(alpha = 0.12f)
    }

private const val LiquidGlassMinApi = Build.VERSION_CODES.TIRAMISU

fun isLiquidGlassSupported(): Boolean = Build.VERSION.SDK_INT >= LiquidGlassMinApi

@Composable
fun Modifier.liquidGlass(
    enabled: Boolean,
    shape: CornerBasedShape = RoundedCornerShape(0.dp),
    containerColor: Color,
    shadowElevation: Dp = 14.dp,
    borderWidth: Dp = 1.dp,
    blurRadius: Dp = 10.dp,
    overlayAlphaBoost: Float = 0f,
    enableLens: Boolean = true,
): Modifier {
    if (!enabled) {
        return this
    }

    // ── 玻璃 → 纯色 ─────────────────────────────────────────────
    // 产品决策：整套界面去掉液态玻璃/折射/高光，一律改为纯色块。
    // 保留函数签名是为了不动 28 个调用点（抽屉、输入栏、气泡、Tab 等），
    // 只把实现换成"纯色填充 + 可选描边"，不再消费 backdrop、不再做模糊与折射。
    if (PlainSurfaceMode) {
        return this
            .background(color = containerColor, shape = shape)
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(
                        width = borderWidth,
                        color = PlainSurfaceBorderColor(containerColor),
                        shape = shape
                    )
                } else {
                    Modifier
                }
            )
    }

    val backdrop = if (isLiquidGlassSupported()) LocalLiquidGlassBackdrop.current else null
    val isLightGlass = containerColor.luminance() >= 0.5f
    if (backdrop == null) {
        val fallbackBorderColor =
            if (isLightGlass) {
                Color.White.copy(alpha = 0.28f)
            } else {
                Color.White.copy(alpha = 0.16f)
            }
        val fallbackShadow = shadowElevation.coerceAtLeast(10.dp)
        val fallbackSurfaceTint =
            if (isLightGlass) {
                containerColor.copy(alpha = 0.16f)
            } else {
                containerColor.copy(alpha = 0.24f)
            }
        val fallbackGloss =
            if (isLightGlass) {
                Color.White.copy(alpha = 0.12f)
            } else {
                Color.White.copy(alpha = 0.06f)
            }

        return this
            .shadow(
                elevation = fallbackShadow,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (isLightGlass) 0.10f else 0.18f),
                spotColor = Color.Black.copy(alpha = if (isLightGlass) 0.10f else 0.18f),
            )
            .border(width = borderWidth.coerceAtLeast(0.6.dp), color = fallbackBorderColor, shape = shape)
            .background(color = fallbackSurfaceTint, shape = shape)
            .drawWithContent {
                drawContent()
                drawRect(fallbackGloss)
            }
    }
    val baseTintAlpha = if (isLightGlass) 0.16f else 0.23f
    val surfaceTint =
        containerColor.copy(alpha = (baseTintAlpha + overlayAlphaBoost).coerceIn(0f, 0.48f))
    val edgeWidth = borderWidth.coerceAtLeast(0.2.dp)
    val shadowRadius = shadowElevation.coerceAtLeast(12.dp)
    val shadowColor =
        if (isLightGlass) {
            Color.Black.copy(alpha = 0.10f)
        } else {
            Color.Black.copy(alpha = 0.18f)
        }

    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            if (enableLens) {
                lens(
                    refractionHeight = 12.dp.toPx(),
                    refractionAmount = 18.dp.toPx(),
                    chromaticAberration = true,
                )
            }
        },
        highlight = {
            Highlight(
                width = edgeWidth,
                blurRadius = edgeWidth * 2.4f,
                alpha = if (isLightGlass) 0.62f else 0.50f,
            )
        },
        shadow = {
            Shadow(
                radius = shadowRadius,
                color = shadowColor,
            )
        },
        onDrawSurface = {
            drawRect(surfaceTint)
        },
    )
}
