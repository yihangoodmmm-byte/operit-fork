package com.ai.assistance.operit.ui.features.settings.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.ai.assistance.operit.core.prompt.PromptComposeContext
import com.ai.assistance.operit.core.prompt.PromptComposer
import com.ai.assistance.operit.core.prompt.PromptLayerConfig
import com.ai.assistance.operit.core.prompt.PromptLayerOverride
import com.ai.assistance.operit.core.prompt.PromptLayers
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.ui.components.CustomScaffold
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 提示词分层设置页。
 *
 * 人设正文仍然来自【系统提示词】；这里只决定内置的十几层是否进入提示词，
 * 并给出组装结果和 token 估算，方便判断某一层是不是在悄悄膨胀。
 */
@Composable
fun PromptLayersSettingsScreen(onBackPressed: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiPreferences = remember { ApiPreferences.getInstance(context) }

    var persona by remember { mutableStateOf("") }
    var disabled by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        persona = apiPreferences.systemPersonaPromptFlow.first()
        disabled = apiPreferences.promptDisabledLayersFlow.first()
        loaded = true
    }

    val composed = remember(persona, disabled) {
        if (persona.isBlank()) {
            null
        } else {
            runCatching {
                PromptComposer.compose(
                    context = PromptComposeContext(
                        useEnglish = false,
                        userPersona = persona.trim()
                    ),
                    config = PromptLayerConfig(
                        overrides = disabled.associateWith { PromptLayerOverride(enabled = false) }
                    )
                )
            }.getOrNull()
        }
    }

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
                text = "人设正文来自「系统提示词」。这里控制内置层是否进入提示词；关掉一层就少一段内容。",
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))

            if (persona.isBlank()) {
                Text(
                    text = "当前没有系统提示词，不会注入任何内容（使用模型自身默认）。",
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(definition.title, fontSize = 14.sp)
                            Text(
                                text = definition.id + "  ·  order " + definition.defaultOrder,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = enabled,
                            enabled = loaded,
                            onCheckedChange = { on ->
                                val next = if (on) disabled - definition.id else disabled + definition.id
                                disabled = next
                                scope.launch {
                                    apiPreferences.savePromptDisabledLayers(next)
                                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    HorizontalDivider()
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
