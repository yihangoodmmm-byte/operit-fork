package com.ai.assistance.operit.ui.features.chat.webview.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.ui.features.chat.components.AndroidExportDialog
import com.ai.assistance.operit.ui.features.chat.components.ExportCompleteDialog
import com.ai.assistance.operit.ui.features.chat.components.ExportPlatformDialog
import com.ai.assistance.operit.ui.features.chat.components.ExportProgressDialog
import com.ai.assistance.operit.ui.features.chat.components.WindowsExportDialog
import com.ai.assistance.operit.ui.features.chat.components.exportAndroidApp
import com.ai.assistance.operit.ui.features.chat.components.exportWindowsApp
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 独立「工作区」页面：从聊天浮层拆出来的独立路由页。
 *
 * 复用 Activity 级共享的 ChatViewModel（与聊天页同一实例），
 * 展示当前会话绑定的工作区（文件管理 / Web 预览），并承载工作区导出流程。
 */
@Composable
fun WorkspacePage() {
    val context = LocalContext.current
    val actualViewModel: ChatViewModel = viewModel { ChatViewModel(context.applicationContext) }

    val chatHistories by actualViewModel.chatHistories.collectAsState()
    val currentChatId by actualViewModel.currentChatId.collectAsState()
    val currentChat = chatHistories.find { it.id == currentChatId }

    val coroutineScope = rememberCoroutineScope()

    // ── 导出相关状态（原聊天页内的工作区导出流程整体迁入） ──
    var webContentDir by remember { mutableStateOf<File?>(null) }
    var showExportPlatformDialog by remember { mutableStateOf(false) }
    var showAndroidExportDialog by remember { mutableStateOf(false) }
    var showWindowsExportDialog by remember { mutableStateOf(false) }
    var showExportProgressDialog by remember { mutableStateOf(false) }
    var showExportCompleteDialog by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportStatus by remember { mutableStateOf("") }
    var exportSuccess by remember { mutableStateOf(false) }
    var exportFilePath by remember { mutableStateOf<String?>(null) }
    var exportErrorMessage by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        WorkspaceScreen(
            actualViewModel = actualViewModel,
            currentChat = currentChat,
            isVisible = true,
            onExportClick = { workDir ->
                webContentDir = workDir
                showExportPlatformDialog = true
            }
        )
    }

    // 导出平台选择
    if (showExportPlatformDialog) {
        ExportPlatformDialog(
            onDismiss = { showExportPlatformDialog = false },
            onSelectAndroid = {
                showExportPlatformDialog = false
                showAndroidExportDialog = true
            },
            onSelectWindows = {
                showExportPlatformDialog = false
                showWindowsExportDialog = true
            }
        )
    }

    // Android 导出
    if (showAndroidExportDialog && webContentDir != null) {
        AndroidExportDialog(
            workDir = webContentDir!!,
            onDismiss = { showAndroidExportDialog = false },
            onExport = { packageName, appName, iconUri, versionName, versionCode ->
                showAndroidExportDialog = false
                showExportProgressDialog = true
                exportProgress = 0f
                exportStatus = context.getString(R.string.export_starting)
                coroutineScope.launch {
                    exportAndroidApp(
                        context = context,
                        packageName = packageName,
                        appName = appName,
                        versionName = versionName,
                        versionCode = versionCode,
                        iconUri = iconUri,
                        webContentDir = webContentDir!!,
                        onProgress = { progress, status ->
                            exportProgress = progress
                            exportStatus = status
                        },
                        onComplete = { success, filePath, errorMessage ->
                            showExportProgressDialog = false
                            exportSuccess = success
                            exportFilePath = filePath
                            exportErrorMessage = errorMessage
                            showExportCompleteDialog = true
                        }
                    )
                }
            }
        )
    }

    // Windows 导出
    if (showWindowsExportDialog && webContentDir != null) {
        WindowsExportDialog(
            workDir = webContentDir!!,
            onDismiss = { showWindowsExportDialog = false },
            onExport = { appName, iconUri ->
                showWindowsExportDialog = false
                showExportProgressDialog = true
                exportProgress = 0f
                exportStatus = context.getString(R.string.export_starting)
                coroutineScope.launch {
                    exportWindowsApp(
                        context = context,
                        appName = appName,
                        iconUri = iconUri,
                        webContentDir = webContentDir!!,
                        onProgress = { progress, status ->
                            exportProgress = progress
                            exportStatus = status
                        },
                        onComplete = { success, filePath, errorMessage ->
                            showExportProgressDialog = false
                            exportSuccess = success
                            exportFilePath = filePath
                            exportErrorMessage = errorMessage
                            showExportCompleteDialog = true
                        }
                    )
                }
            }
        )
    }

    // 导出进度
    if (showExportProgressDialog) {
        ExportProgressDialog(
            progress = exportProgress,
            status = exportStatus,
            onCancel = { showExportProgressDialog = false }
        )
    }

    // 导出完成
    if (showExportCompleteDialog) {
        ExportCompleteDialog(
            success = exportSuccess,
            filePath = exportFilePath,
            errorMessage = exportErrorMessage,
            onDismiss = { showExportCompleteDialog = false },
            onOpenFile = { path ->
                val tool =
                    AITool(
                        name = if (path.endsWith(".apk", ignoreCase = true)) "install_app" else "open_file",
                        parameters = listOf(ToolParameter("path", path))
                    )
                AIToolHandler.getInstance(context).executeTool(tool)
            }
        )
    }
}
