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
import androidx.compose.material3.Button
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 系统级提示词设置页。
 *
 * 这是人设的唯一来源：留空表示不注入任何人设，交由模型自身训练默认；
 * 填写后作为系统级提示词注入，在下一轮对话生效。
 */
@Composable
fun SystemPromptSettingsScreen(onBackPressed: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiPreferences = remember { ApiPreferences.getInstance(context) }

    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        text = apiPreferences.systemPersonaPromptFlow.first()
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
                text = "系统提示词",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "留空 = 不注入任何人设，使用模型自身训练默认；填写后作为系统级提示词注入，下一条消息生效。",
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp),
                enabled = loaded,
                minLines = 10,
                maxLines = 40,
                placeholder = { Text("例如：你说话必须简短直接，不说废话。") }
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { text = "" }) { Text("清空") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    scope.launch {
                        apiPreferences.saveSystemPersonaPrompt(text)
                        Toast.makeText(context, "已保存，下一条消息生效", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保存") }
            }
        }
    }
}
