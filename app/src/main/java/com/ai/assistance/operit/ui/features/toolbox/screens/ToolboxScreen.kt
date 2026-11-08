package com.ai.assistance.operit.ui.features.toolbox.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ai.assistance.operit.ui.components.CustomScaffold
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.toolbox.screens.apppermissions.AppPermissionsScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.ffmpegtoolbox.FFmpegToolboxScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.filemanager.FileManagerScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.logcat.LogcatScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.shellexecutor.ShellExecutorScreen
import com.ai.assistance.operit.terminal.main.TerminalScreen as TerminalViewScreen
// import com.ai.assistance.operit.ui.features.toolbox.screens.terminalconfig.TerminalAutoConfigScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.uidebugger.UIDebuggerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.rememberTerminalEnv
import com.ai.assistance.operit.ui.main.LocalAppNavigationModel
import com.ai.assistance.operit.ui.main.navigation.NavigationEntrySpec
import com.ai.assistance.operit.ui.main.navigation.NavigationSurface

data class Tool(
        val id: String,
        val name: String,
        val icon: ImageVector,
        val description: String? = null,
        val onClick: () -> Unit
)

/** 工具箱屏幕，展示可用的各种工具 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun ToolboxScreen(
        navController: NavController,
        onNavigationEntrySelected: (NavigationEntrySpec) -> Unit
) {
        val navigationModel = LocalAppNavigationModel.current
        val toolboxEntries =
                remember(navigationModel) {
                        navigationModel
                                ?.navigationEntries
                                .orEmpty()
                                .filter { entry ->
                                        entry.surface == NavigationSurface.TOOLBOX
                                }
                }
        val tools =
                remember(toolboxEntries) {
                        toolboxEntries.map { entry ->
                                Tool(
                                        id = entry.entryId,
                                        name = entry.title,
                                        icon = entry.icon,
                                        description = entry.description,
                                        onClick = {
                                                onNavigationEntrySelected(entry)
                                        }
                                )
                        }
                }

        Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 156.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                ) {
                        items(
                                items = tools,
                                key = { tool -> tool.id }
                        ) { tool ->
                                ToolCard(tool = tool)
                        }
                }
        }
}

/** 工具项卡片 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolCard(tool: Tool) {
    // cccodroid 的行式工具项：左边图标（不用彩色圆底，用中性色）、
    // 中间「名称 + 一行说明」、右边一个 chevron，行高 56dp，底部 1dp 细线。
    // 原来是 156dp 高的方形卡片 + 按压缩放动画 + 彩色圆底图标，一屏只能放两三个。
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { tool.onClick() }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                tool.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    }
}


/** 显示文件管理器工具屏幕 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerToolScreen(navController: NavController) {
        CustomScaffold() { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                        FileManagerScreen(navController = navController)
                }
        }
}

/** 显示终端工具屏幕 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalToolScreen(navController: NavController, forceShowSetup: Boolean = false) {
        val context = LocalContext.current
        val terminalManager = remember { TerminalManager.getInstance(context) }
        val terminalEnv = rememberTerminalEnv(terminalManager = terminalManager, forceShowSetup = forceShowSetup)
        CustomScaffold() { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) { TerminalViewScreen(env = terminalEnv) }
        }
}

/** 显示终端自动配置工具屏幕 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalAutoConfigToolScreen(navController: NavController) {
        CustomScaffold() { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                        // TODO: 需要重构以适配新的终端架构
                        // TerminalAutoConfigScreen(navController = navController)
                        Text(
                            text = stringResource(R.string.tool_terminal_auto_config_under_construction),
                            modifier = Modifier.padding(16.dp)
                        )
                }
        }
}

/** 显示应用权限管理工具屏幕 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPermissionsToolScreen(navController: NavController) {
        CustomScaffold() { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                        AppPermissionsScreen(navController = navController)
                }
        }
}

/** 显示UI调试工具屏幕 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UIDebuggerToolScreen(navController: NavController) {
        CustomScaffold() { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                        UIDebuggerScreen(navController = navController)
                }
        }
}

/** 显示FFmpeg工具箱屏幕 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FFmpegToolboxToolScreen(navController: NavController) {
        CustomScaffold() { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                        FFmpegToolboxScreen(navController = navController)
                }
        }
}

/** 显示Shell命令执行器屏幕 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellExecutorToolScreen(navController: NavController) {
        CustomScaffold() { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                        ShellExecutorScreen(navController = navController)
                }
        }
}

/** 显示日志查看器屏幕 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatToolScreen(navController: NavController) {
        CustomScaffold() { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                        LogcatScreen(navController = navController)
                }
        }
}

/** 显示工具测试屏幕 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolTesterToolScreen(navController: NavController) {
        CustomScaffold() { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                        com.ai.assistance.operit.ui.features.toolbox.screens.tooltester
                                .ToolTesterScreen(navController = navController)
                }
        }
}

/** 显示默认助手设置引导屏幕 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultAssistantGuideToolScreen(navController: NavController) {
        com.ai.assistance.operit.ui.features.toolbox.screens.defaultassistant
                .DefaultAssistantGuideScreen(navController = navController)
}
