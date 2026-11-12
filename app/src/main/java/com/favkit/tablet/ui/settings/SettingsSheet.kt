package com.favkit.tablet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.floating.FloatingAssistantService
import com.favkit.tablet.ui.main.MainUiState
import com.favkit.tablet.ui.main.MainViewModel
import com.favkit.tablet.ui.components.Cardish
import com.favkit.tablet.ui.components.SectionTitle
import com.favkit.tablet.ui.components.StatusPill
import java.util.Locale

private val CACHE_LIMITS_MB = listOf(128L, 256L, 512L, 1024L, 2048L)

/**
 * 设置面板：同步地址与加密公钥、悬浮助手开关、缓存上限与清理、失败重传。
 * 全部为可改配置，方便换后端环境或对接自建服务，不需要重新打包。
 */
@Composable
fun SettingsSheet(
    visible: Boolean,
    state: MainUiState,
    viewModel: MainViewModel,
    scale: TabletScale,
    assistantAvailable: Boolean,
    onEnableAssistant: () -> Unit,
    onClose: () -> Unit,
) {
    val settings = state.settings ?: return
    if (!visible) return

    var endpoint by remember { mutableStateOf(settings.syncEndpoint) }
    var token by remember { mutableStateOf(settings.syncToken) }
    var publicKey by remember { mutableStateOf(settings.serverPublicKeyPem) }
    val context = LocalContext.current
    val toggleAssistant: (Boolean) -> Unit = { enabled ->
        viewModel.setAssistantEnabled(enabled)
        if (enabled && assistantAvailable) {
            FloatingAssistantService.start(context)
        } else {
            FloatingAssistantService.stop(context)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true),
    ) {
        Surface(
            modifier = Modifier.width(scale.floatingPanelWidth + 60.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(scale.gapMd),
                verticalArrangement = Arrangement.spacedBy(scale.gapMd),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = scale.touchMin)) {
                        Text("关闭")
                    }
                }

                Cardish(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(scale.gapSm),
                        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
                    ) {
                        SectionTitle("加密同步服务")
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            label = { Text("接口地址") },
                            singleLine = true,
                            supportingText = { Text("例：http://10.0.2.2:8787/api/v1（模拟器里指向本机 mock 服务）") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = scale.touchMin),
                        )
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text("访问令牌（Bearer）") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = scale.touchMin),
                        )
                        OutlinedTextField(
                            value = publicKey,
                            onValueChange = { publicKey = it },
                            label = { Text("服务端 RSA 公钥（PEM）") },
                            minLines = 3,
                            supportingText = {
                                Text(
                                    "留空则使用设备本地 Keystore 密钥加密，同样是端到端密文出网；填入公钥后改用 RSA-OAEP 包裹会话密钥。",
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = scale.touchMin * 3f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
                            Button(
                                onClick = {
                                    viewModel.setSyncEndpoint(endpoint.trim())
                                    viewModel.setSyncToken(token.trim())
                                    viewModel.setServerPublicKey(publicKey.trim())
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = scale.touchMin),
                            ) {
                                Text("保存同步配置")
                            }
                            TextButton(
                                onClick = {
                                    endpoint = settings.syncEndpoint
                                    token = settings.syncToken
                                    publicKey = settings.serverPublicKeyPem
                                },
                                modifier = Modifier.heightIn(min = scale.touchMin),
                            ) {
                                Text("还原")
                            }
                        }
                        InfoLine("设备标识", settings.deviceId)
                    }
                }

                Cardish(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(scale.gapSm),
                        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
                    ) {
                        SectionTitle("同步队列")
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(scale.gapSm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusPill("待上传 ${state.queued}", Color(0xFFF9AB00))
                            StatusPill("未同步 ${state.unsynced}", Color(0xFFD93025))
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = viewModel::retrySync,
                                modifier = Modifier.heightIn(min = scale.touchMin),
                            ) {
                                Text("立即重试")
                            }
                        }
                        Text(
                            "断网时先落本地队列，联网后自动补传；单条最多静默重试 3 次，之后仍然自动跟随网络恢复，无需手动处理。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Cardish(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(scale.gapSm),
                        verticalArrangement = Arrangement.spacedBy(scale.gapSm),
                    ) {
                        SectionTitle("全局悬浮收藏助手")
                        ToggleRow(
                            label = "常驻悬浮球",
                            hint = if (assistantAvailable) {
                                "悬浮球贴边自动收起，不遮挡第三方 App 主体内容。"
                            } else {
                                "还需要在系统设置里授予「显示在其他应用上层」权限。"
                            },
                            checked = settings.assistantEnabled && assistantAvailable,
                            scale = scale,
                            onCheckedChange = toggleAssistant,
                        )
                        if (!assistantAvailable || !settings.assistantEnabled) {
                            OutlinedButton(
                                onClick = onEnableAssistant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = scale.touchMin),
                            ) {
                                Text(if (assistantAvailable) "开启悬浮助手" else "去授予悬浮窗权限")
                            }
                        }
                        ToggleRow(
                            label = "打开面板时自动读剪贴板",
                            hint = "关掉后面板不会自动读取，只能自己点「粘贴剪贴板链接」，或用视频 App 的「分享 → 收藏助手」。" +
                                "Android 10 起只有前台获得焦点的 App 能读剪贴板，所以不做后台监听、也不申请无障碍权限。",
                            checked = settings.pasteSuggestion,
                            scale = scale,
                            onCheckedChange = viewModel::setPasteSuggestion,
                        )
                    }
                }

                Cardish(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(scale.gapSm),
                        verticalArrangement = Arrangement.spacedBy(scale.gapSm),
                    ) {
                        SectionTitle("显示与缓存")
                        ToggleRow(
                            label = "允许自由旋转",
                            hint = "默认强制横屏三栏。打开后跟随传感器，但输入、选中标签和滚动位置都不会丢。",
                            checked = settings.allowFreeRotation,
                            scale = scale,
                            onCheckedChange = viewModel::setAllowFreeRotation,
                        )
                        InfoLine("默认倍速", String.format(Locale.CHINA, "%.2fx", settings.defaultPlaybackSpeed))
                        InfoLine("本地缓存占用", formatBytes(state.cacheBytes))
                        SectionTitle("缓存上限")
                        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
                            CACHE_LIMITS_MB.forEach { limit ->
                                val label = "${limit}MB"
                                if (settings.cacheLimitMb == limit) {
                                    Button(
                                        onClick = { viewModel.setCacheLimitMb(limit) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = scale.touchMin),
                                    ) {
                                        Text(label)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { viewModel.setCacheLimitMb(limit) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = scale.touchMin),
                                    ) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = viewModel::clearCache,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = scale.touchMin),
                        ) {
                            Text("清空本地视频缓存")
                        }
                        Text(
                            "清空只删除视频缓存文件，收藏记录、备注和标签都不受影响。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    hint: String,
    checked: Boolean,
    scale: TabletScale,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = scale.touchMin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scale.gapSm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = hint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.CHINA, "%.2fGB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(Locale.CHINA, "%.0fMB", bytes.toDouble() / (1L shl 20))
    else -> "${bytes / 1024}KB"
}
