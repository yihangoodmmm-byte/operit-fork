package com.ai.assistance.operit.ui.theme

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.net.toFile
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import java.io.File

/**
 * 字重与字号节奏（对齐 AiCode）。
 *
 * AiCode 的排版基线：标题类一律 SemiBold（不用 Normal，也不用 Bold），
 * 正文 bodyLarge 行高 24sp、字距 0（不是 Operit 原来的 0.5sp——字距一宽就显得松散），
 * 标签类 Medium 且字距归零。
 *
 * 字体家族用 [CcFontFamily]（cccodroid 的 Inter + Hei）。
 * 若用户在偏好里选了系统字体或导入字体，applyFontFamilyToTypography 会在运行时覆盖。
 */
/**
 * CC 字体（移植自 cccodroid 的 fonts/ 目录）。
 *
 * cccodroid 的字体栈是 \"CC Inter\",\"CC Hei\"（界面）+ \"CC Spectral\",\"CC Song\"（正文）。
 * 这里只移植界面用的两套：Inter 负责拉丁字符、Hei 负责中文，Compose 按字形自动回退，
 * 所以中英混排时西文走 Inter、汉字走 Hei，与网页端一致。
 *
 * 原始文件是 woff2（Android 不支持），已用 fontTools 转成 ttf 放进 res/font。
 */
val CcFontFamily = FontFamily(
    Font(R.font.cc_inter_regular, FontWeight.Normal),
    Font(R.font.cc_inter_medium, FontWeight.Medium),
    Font(R.font.cc_inter_bold, FontWeight.Bold),
    Font(R.font.cc_hei_regular, FontWeight.Normal),
    Font(R.font.cc_hei_medium, FontWeight.Medium),
    Font(R.font.cc_hei_bold, FontWeight.Bold),
)

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = CcFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = CcFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = CcFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = CcFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = CcFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = CcFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = CcFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )
)

/**
 * 根据系统字体名称获取 FontFamily
 */
fun getSystemFontFamily(systemFontName: String): FontFamily {
    return when (systemFontName) {
        UserPreferencesManager.SYSTEM_FONT_SERIF -> FontFamily.Serif
        UserPreferencesManager.SYSTEM_FONT_SANS_SERIF -> FontFamily.SansSerif
        UserPreferencesManager.SYSTEM_FONT_MONOSPACE -> FontFamily.Monospace
        UserPreferencesManager.SYSTEM_FONT_CURSIVE -> FontFamily.Cursive
        else -> FontFamily.Default
    }
}

/**
 * 从文件路径加载自定义字体
 */
fun loadCustomFontFamily(context: Context, fontPath: String): FontFamily? {
    return try {
        // - 修复了 file:// URI 路径无法被 File 正确解析的问题
        val file = if (fontPath.startsWith("file://")) {
            Uri.parse(fontPath).toFile()
        } else {
            File(fontPath)
        }
        
        if (!file.exists()) {
            AppLogger.e("TypeKt", "Font file does not exist: $fontPath")
            return null
        }
        
        FontFamily(
            Font(file)
        )
    } catch (e: Exception) {
        AppLogger.e("TypeKt", "Error loading custom font from $fontPath", e)
        null
    }
}

/**
 * 根据配置解析可选 FontFamily
 */
fun resolveConfiguredFontFamily(
    context: Context,
    useCustomFont: Boolean,
    fontType: String,
    systemFontName: String,
    customFontPath: String?,
): FontFamily? {
    if (!useCustomFont) {
        return null
    }

    return when (fontType) {
        UserPreferencesManager.FONT_TYPE_SYSTEM -> getSystemFontFamily(systemFontName)
        UserPreferencesManager.FONT_TYPE_FILE ->
            customFontPath
                ?.takeIf { it.isNotBlank() }
                ?.let { loadCustomFontFamily(context, it) }
        else -> null
    }
}

/**
 * 将指定 FontFamily 应用于整套 Typography
 */
fun applyFontFamilyToTypography(
    baseTypography: Typography,
    fontFamily: FontFamily?,
): Typography {
    if (fontFamily == null) {
        return baseTypography
    }

    return Typography(
        displayLarge = baseTypography.displayLarge.copy(fontFamily = fontFamily),
        displayMedium = baseTypography.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = baseTypography.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = baseTypography.headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = baseTypography.headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = baseTypography.headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = baseTypography.titleLarge.copy(fontFamily = fontFamily),
        titleMedium = baseTypography.titleMedium.copy(fontFamily = fontFamily),
        titleSmall = baseTypography.titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = baseTypography.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = baseTypography.bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = baseTypography.bodySmall.copy(fontFamily = fontFamily),
        labelLarge = baseTypography.labelLarge.copy(fontFamily = fontFamily),
        labelMedium = baseTypography.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = baseTypography.labelSmall.copy(fontFamily = fontFamily),
    )
}

/**
 * 根据用户设置创建自定义 Typography
 */
fun createCustomTypography(
    context: Context,
    useCustomFont: Boolean,
    fontType: String,
    systemFontName: String,
    customFontPath: String?,
    fontScale: Float
): Typography {
    // 如果不使用自定义字体且字体大小为默认值，则直接返回默认Typography
    if (!useCustomFont && fontScale == 1.0f) {
        return Typography
    }

    val fontFamily =
        resolveConfiguredFontFamily(
            context = context,
            useCustomFont = useCustomFont,
            fontType = fontType,
            systemFontName = systemFontName,
            customFontPath = customFontPath,
        ) ?: FontFamily.Default
    val baseTypography = applyFontFamilyToTypography(Typography, fontFamily)

    // Helper to apply scale. It will be applied to every style.
    fun TextStyle.withScale(): TextStyle = if (fontScale != 1.0f) {
        copy(fontSize = fontSize * fontScale, lineHeight = lineHeight * fontScale)
    } else {
        this
    }

    // 创建带有自定义字体的 Typography
    return Typography(
        displayLarge = baseTypography.displayLarge.withScale(),
        displayMedium = baseTypography.displayMedium.withScale(),
        displaySmall = baseTypography.displaySmall.withScale(),
        headlineLarge = baseTypography.headlineLarge.withScale(),
        headlineMedium = baseTypography.headlineMedium.withScale(),
        headlineSmall = baseTypography.headlineSmall.withScale(),
        titleLarge = baseTypography.titleLarge.withScale(),
        titleMedium = baseTypography.titleMedium.withScale(),
        titleSmall = baseTypography.titleSmall.withScale(),
        bodyLarge = baseTypography.bodyLarge.withScale(),
        bodyMedium = baseTypography.bodyMedium.withScale(),
        bodySmall = baseTypography.bodySmall.withScale(),
        labelLarge = baseTypography.labelLarge.withScale(),
        labelMedium = baseTypography.labelMedium.withScale(),
        labelSmall = baseTypography.labelSmall.withScale()
    )
}
