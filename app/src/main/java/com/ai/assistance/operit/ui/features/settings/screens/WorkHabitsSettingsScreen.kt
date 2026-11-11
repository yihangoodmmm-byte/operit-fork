package com.ai.assistance.operit.ui.features.settings.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.ui.components.CustomScaffold
import com.ai.assistance.operit.ui.common.components.CcButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 工作习惯编辑页。
 *
 * 工作习惯是用户自定义的持续性行为约定（可几百行），每轮以最高优先级锚定在
 * 系统提示词末尾注入（见 ConversationService 的 <work_habits> 块）。留空 = 不注入。
 * 保存后下一条消息生效。
 */
@Composable
fun WorkHabitsSettingsScreen(onBackPressed: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiPreferences = remember { ApiPreferences.getInstance(context) }

    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        text = apiPreferences.workHabitsPromptFlow.first()
        loaded = true
    }

    CustomScaffold() { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "工作习惯",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "这里是你要 AI 长期遵守的行为约定，可写几百行。每轮都会以最高优先级注入到系统提示词末尾；留空 = 不注入。",
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp),
                enabled = loaded,
                minLines = 14,
                maxLines = 60,
                placeholder = { Text("例如：\n1. 每次动手前先列计划步骤。\n2. 结尾用 Markdown 三节收尾。\n3. …") }
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { text = "" }) { Text("清空") }
                Spacer(Modifier.width(8.dp))
                CcButton(onClick = {
                    scope.launch {
                        apiPreferences.saveWorkHabitsPrompt(text)
                        Toast.makeText(context, "已保存，下一条消息生效", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保存") }
            }
        }
    }
}
