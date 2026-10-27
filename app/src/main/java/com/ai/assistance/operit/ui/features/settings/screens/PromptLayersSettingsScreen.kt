package com.ai.assistance.operit.ui.features.settings.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.core.prompt.ConflictDetectOptions
import com.ai.assistance.operit.core.prompt.ConflictDetector
import com.ai.assistance.operit.core.prompt.PromptComposeContext
import com.ai.assistance.operit.core.prompt.PromptComposer
import com.ai.assistance.operit.core.prompt.PromptLayerConfig
import com.ai.assistance.operit.core.prompt.PromptLayerOverride
import com.ai.assistance.operit.core.prompt.PromptLayers
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.ui.components.CustomScaffold
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 提示词分层设置页。
 *
 * 人设正文仍然来自【系统提示词】；这里负责三件事：
 * 1. 开关内置层；
 * 2. 覆盖任意一层的正文（留空即用内置默认）；
 * 3. 导入/导出整份人格配置，方便在设备之间搬运。
 */
@Composable
fun PromptLayersSettingsScreen(onBackPressed: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiPreferences = remember { ApiPreferences.getInstance(context) }

    var persona by remember { mutableStateOf("") }
    var disabled by remember { mutableStateOf<Set<String>>(emptySet()) }
    var contents by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        persona = apiPreferences.systemPersonaPromptFlow.first()
        disabled = apiPreferences.promptDisabledLayersFlow.first()
        contents = apiPreferences.promptLayerContentsFlow.first()
        loaded = true
    }

    val composed = remember(persona, disabled, contents) {
        if (persona.isBlank()) {
            null
        } else {
            runCatching {
                val overrides = (disabled + contents.keys).associateWith { id ->
                    PromptLayerOverride(
                        enabled = if (id in disabled) false else null,
                        content = contents[id]
                    )
                }
                PromptComposer.compose(
                    context = PromptComposeContext(
                        useEnglish = false,
                        userPersona = persona.trim()
                    ),
                    config = PromptLayerConfig(overrides = overrides)
                )
            }.getOrNull()
        }
    }

    // 冲突检测只在预览时跑一次：逐句 × 逐主题是四重循环，不能放进请求路径。
    val conflicts = remember(composed) {
        composed?.let {
            runCatching {
                ConflictDetector.detect(
                    result = it,
                    options = ConflictDetectOptions(includeUserToUser = true)
                )
            }.getOrDefault(emptyList())
        }.orEmpty()
    }

    fun exportAll(uri: android.net.Uri) {
        runCatching {
            val root = JSONObject()
            root.put("version", 1)
            root.put("persona", persona)
            val disabledArray = JSONArray()
            disabled.sorted().forEach { disabledArray.put(it) }
            root.put("disabled", disabledArray)
            val contentsObject = JSONObject()
            contents.forEach { (id, text) -> contentsObject.put(id, text) }
            root.put("contents", contentsObject)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(root.toString(2).toByteArray(Charsets.UTF_8))
            }
        }.onSuccess {
            Toast.makeText(context, "已导出", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "导出失败：" + (it.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    fun importAll(uri: android.net.Uri) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }.orEmpty()
            val root = JSONObject(text)
            val importedPersona = root.optString("persona", "")
            val importedDisabled = runCatching {
                val arr = root.optJSONArray("disabled") ?: JSONArray()
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet()
            }.getOrDefault(emptySet())
            val importedContents = runCatching {
                val obj = root.optJSONObject("contents") ?: JSONObject()
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }.getOrDefault(emptyMap())
            scope.launch {
                apiPreferences.saveSystemPersonaPrompt(importedPersona)
                apiPreferences.savePromptDisabledLayers(importedDisabled)
                apiPreferences.savePromptLayerContents(importedContents)
                persona = importedPersona
                disabled = importedDisabled
                contents = importedContents
                Toast.makeText(context, "已导入", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            Toast.makeText(context, "导入失败：" + (it.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) exportAll(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) importAll(uri) }

    CustomScaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "提示词分层",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "人设正文来自「系统提示词」。这里控制内置层开关、改写任意一层正文，并可整份导入导出。",
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { exportLauncher.launch("persona-prompt.json") }) {
                    Text("导出人格文件")
                }
                TextButton(onClick = { importLauncher.launch("application/json") }) {
                    Text("导入人格文件")
                }
            }
            Spacer(Modifier.height(12.dp))

            if (persona.isBlank()) {
                Text(
                    text = "当前没有系统提示词，不会注入任何内容（使用模型自身默认）。先去「系统提示词」写一段人设，再回来调层。",
                    fontSize = 13.sp
                )
            } else {
                Text(
                    text = "组装结果约 " + (composed?.totalTokenEstimate ?: 0) + " tokens",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))

                PromptLayers.ALL.forEach { definition ->
                    val enabled = definition.id !in disabled
                    val overridden = contents.containsKey(definition.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = definition.title + if (overridden) "  · 已改写" else "",
                                fontSize = 14.sp
                            )
                            Text(
                                text = definition.id + "  ·  order " + definition.defaultOrder,
                                fontSize = 11.sp
                            )
                        }
                        TextButton(onClick = {
                            if (expandedId == definition.id) {
                                expandedId = null
                            } else {
                                expandedId = definition.id
                                draft = contents[definition.id].orEmpty()
                            }
                        }) {
                            Text(if (expandedId == definition.id) "收起" else "编辑")
                        }
                        Switch(
                            checked = enabled,
                            enabled = loaded,
                            onCheckedChange = { on ->
                                val next = if (on) disabled - definition.id else disabled + definition.id
                                disabled = next
                                scope.launch { apiPreferences.savePromptDisabledLayers(next) }
                            }
                        )
                    }
                    if (expandedId == definition.id) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            minLines = 4,
                            maxLines = 12,
                            placeholder = { Text("留空 = 使用内置默认正文") }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                val next = contents - definition.id
                                contents = next
                                draft = ""
                                expandedId = null
                                scope.launch {
                                    apiPreferences.savePromptLayerContents(next)
                                    Toast.makeText(context, "已恢复默认", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("恢复默认") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                val next = if (draft.isBlank()) contents - definition.id else contents + (definition.id to draft)
                                contents = next
                                expandedId = null
                                scope.launch {
                                    apiPreferences.savePromptLayerContents(next)
                                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("保存本层") }
                        }
                    }
                    HorizontalDivider()
                }

                Spacer(Modifier.height(12.dp))
                Text("冲突检查", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                if (conflicts.isEmpty()) {
                    Text("未发现冲突", fontSize = 13.sp)
                } else {
                    conflicts.forEach { conflict ->
                        Text(
                            text = "[" + conflict.severity.name + "] " + conflict.kind.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(conflict.message, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("组装预览", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = composed?.finalPrompt.orEmpty(),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
