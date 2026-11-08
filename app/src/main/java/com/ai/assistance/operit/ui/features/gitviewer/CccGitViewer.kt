package com.ai.assistance.operit.ui.features.gitviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Git 页状态。 */
private sealed interface GitState {
    data object Idle : GitState
    data object Loading : GitState
    data class Loaded(val snapshot: GitRepositorySnapshot) : GitState
    data class Failed(val message: String) : GitState
}

/**
 * 本地 Git 查看器（只读）。
 *
 * 只做三件在只读场景里真正有用的事：当前分支、分支列表、提交历史。
 * 不做 status / diff / 检出——那些需要解析 index 与 packfile，
 * 与其放一堆点了没反应的入口，不如先只呈现确定能正确读出来的东西。
 */
@Composable
fun CccGitViewer(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var pathInput by remember { mutableStateOf("") }
    var requestedPath by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<GitState>(GitState.Idle) }

    LaunchedEffect(requestedPath) {
        if (requestedPath.isBlank()) {
            state = GitState.Idle
            return@LaunchedEffect
        }
        state = GitState.Loading
        // .git 解析是纯文件 IO + zlib，放到后台线程，避免阻塞首帧
        state =
            withContext(Dispatchers.IO) {
                GitRepositoryReader.open(requestedPath).fold(
                    onSuccess = { GitState.Loaded(it) },
                    onFailure = { GitState.Failed(it.message ?: "读取失败") }
                )
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .background(cs.onSurface.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (pathInput.isEmpty()) {
                    Text(
                        text = "仓库路径，例如 /sdcard/repo",
                        fontSize = 13.sp,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    singleLine = true,
                    textStyle = TextStyle(color = cs.onSurface, fontSize = 13.sp),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier =
                    Modifier
                        .background(cs.primary.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .clickable { requestedPath = pathInput.trim() },
                contentAlignment = Alignment.Center
            ) {
                Text("打开", fontSize = 13.sp, color = cs.primary, fontWeight = FontWeight.Medium)
            }
        }

        when (val current = state) {
            GitState.Idle -> GitHint("输入一个仓库路径，查看分支与提交历史")
            GitState.Loading -> GitHint("正在读取 .git …")
            is GitState.Failed -> GitHint(current.message)
            is GitState.Loaded -> {
                val snapshot = current.snapshot
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
                ) {
                    item {
                        GitSectionLabel("当前分支")
                        GitValueRow(
                            label = snapshot.currentBranch ?: "分离头指针",
                            value = snapshot.commits.firstOrNull()?.shortHash ?: "无提交"
                        )
                    }
                    item {
                        GitSectionLabel("分支 (" + snapshot.branches.size + ")")
                        if (snapshot.branches.isEmpty()) {
                            GitValueRow("没有分支", "")
                        } else {
                            snapshot.branches.forEach { branch ->
                                GitValueRow(
                                    label = branch,
                                    value = if (branch == snapshot.currentBranch) "当前" else ""
                                )
                            }
                        }
                    }
                    item {
                        GitSectionLabel("提交历史")
                        if (snapshot.commits.isEmpty()) {
                            GitValueRow("还没有提交", "")
                        }
                    }
                    items(count = snapshot.commits.size) { index ->
                        CommitRow(snapshot.commits[index])
                    }
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "只读视图：不显示工作区改动与 diff",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GitHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

@Composable
private fun GitSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun GitValueRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun CommitRow(commit: GitCommitInfo) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = commit.shortHash,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = commit.subject.ifBlank { "（无提交信息）" },
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = commit.author + " · " + commit.formattedTime(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    }
}
