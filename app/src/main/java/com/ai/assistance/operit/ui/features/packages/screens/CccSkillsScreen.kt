package com.ai.assistance.operit.ui.features.packages.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.core.tools.skill.SkillManager
import com.ai.assistance.operit.data.preferences.SkillVisibilityPreferences
import com.ai.assistance.operit.ui.common.components.CcSwitch

/**
 * 扩展与技能。
 *
 * cccodroid 的侧栏把"能力"当作一等公民：一个列表，每行是名字 + 一句话说明 + 开关，
 * 顶部一个搜索框。Operit 的技能管理原来埋在「包管理 → 技能配置」里，
 * 入口深、且是卡片堆叠。这里抽成独立页面，走本项目的行式排版。
 *
 * 数据来源：
 * - 列表：SkillManager.getAvailableSkills()（扫描技能目录得到的 SkillPackage）
 * - 开关：SkillVisibilityPreferences.isSkillVisibleToAi / setSkillVisibleToAi
 * 这两者都是进程内单例，读一次即可，不需要 Flow。
 */
@Composable
fun CccSkillsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val skillManager = remember(context) { SkillManager.getInstance(context) }
    val visibilityPrefs = remember(context) { SkillVisibilityPreferences.getInstance(context) }

    // refreshAvailableSkills 会重新扫描目录；进入页面时刷一次，保证列表是磁盘现状
    remember(skillManager) { skillManager.refreshAvailableSkills() }
    val skills =
        remember(skillManager) {
            skillManager.getAvailableSkills().values.sortedBy { it.name.lowercase() }
        }
    val loadErrors = remember(skillManager) { skillManager.getSkillLoadErrors() }

    var query by remember { mutableStateOf("") }
    // 开关状态放进 state，勾选后立刻反映在行上
    var visibility by remember {
        mutableStateOf(skills.associate { it.name to visibilityPrefs.isSkillVisibleToAi(it.name) })
    }

    val filtered =
        remember(skills, query) {
            if (query.isBlank()) {
                skills
            } else {
                val q = query.trim().lowercase()
                skills.filter {
                    it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
                }
            }
        }

    Column(modifier = modifier.fillMaxSize()) {
        SkillSearchField(
            value = query,
            onValueChange = { query = it },
        )

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (skills.isEmpty()) "还没有安装任何技能" else "没有匹配的技能",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text =
                            if (skills.isEmpty()) {
                                "把技能目录放到 " + skillManager.getSkillsDirectoryPath()
                            } else {
                                "换个关键词试试"
                            },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            items(count = filtered.size) { index ->
                val skill = filtered[index]
                SkillRow(
                    name = skill.name,
                    description = skill.description,
                    visible = visibility[skill.name] ?: true,
                    onVisibleChange = { next ->
                        visibility = visibility + (skill.name to next)
                        visibilityPrefs.setSkillVisibleToAi(skill.name, next)
                    }
                )
            }
            if (loadErrors.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "有 " + loadErrors.size + " 个技能加载失败",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    loadErrors.entries.take(3).forEach { (name, error) ->
                        Text(
                            text = name + "：" + error,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillSearchField(value: String, onValueChange: (String) -> Unit) {
    // 搜索框走和设置页一致的形态：浅填充 + 圆角，不用描边
    val cs = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle =
                androidx.compose.ui.text.TextStyle(
                    color = cs.onSurface,
                    fontSize = 14.sp,
                ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(cs.onSurface.copy(alpha = 0.06f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = "搜索技能",
                        fontSize = 14.sp,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                inner()
            }
        )
    }
}

@Composable
private fun SkillRow(
    name: String,
    description: String,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            CcSwitch(
                checked = visible,
                onCheckedChange = onVisibleChange,
                modifier = Modifier.size(width = 32.dp, height = 20.dp)
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    }
}
