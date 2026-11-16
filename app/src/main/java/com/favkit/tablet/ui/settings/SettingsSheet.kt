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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.favkit.tablet.core.TabletScale
import com.favkit.tablet.floating.FloatingAssistantService
import com.favkit.tablet.ui.components.Cardish
import com.favkit.tablet.ui.components.FavColors
import com.favkit.tablet.ui.components.PLAYBACK_SPEEDS
import com.favkit.tablet.ui.components.SectionTitle
import com.favkit.tablet.ui.components.StatusPill
import com.favkit.tablet.ui.components.formatBytes
import com.favkit.tablet.ui.components.formatSpeed
import com.favkit.tablet.ui.components.rememberDialogWidth
import com.favkit.tablet.ui.main.MainUiState
import com.favkit.tablet.ui.main.MainViewModel

private val CACHE_LIMITS_MB = listOf(128L, 256L, 512L, 1024L, 2048L)
private val IMAGE_CACHE_LIMITS_MB = listOf(32L, 64L, 128L, 256L, 512L)
private val UPLOAD_INTERVAL_PRESETS = listOf(0L, 1_000L, 3_000L, 10_000L, 30_000L)

/** 后台 setUploadIntervalMs 会再夹一次，这里先按同一个范围挡掉脏输入 */
private const val MAX_UPLOAD_INTERVAL_MS = 600_000L

/**
 * 设置面板：同步地址与令牌、上传节奏、悬浮助手、播放与缓存。
 * 全部可在运行期修改，换后端环境或自建服务都不需要重新打包。
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

    var endpoint by remember(settings.syncEndpoint) { mutableStateOf(settings.syncEndpoint) }
    var token by remember(settings.syncToken) { mutableStateOf(settings.syncToken) }
    var intervalDraft by remember(settings.uploadIntervalMs) {
        mutableStateOf(settings.uploadIntervalMs.toString())
    }
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
            modifier = Modifier.width(rememberDialogWidth(scale.floatingPanelWidth + 60.dp)),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 760.dp)
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
                        SectionTitle("同步服务")
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            label = { Text("接口地址") },
                            singleLine = true,
                            supportingText = {
                                Text("例：http://10.0.2.2:8787/api/v1（模拟器里指向本机 mock 服务）")
                            },
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
                        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
                            Button(
                                onClick = {
                                    viewModel.setSyncEndpoint(endpoint.trim())
                                    viewModel.setSyncToken(token.trim())
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
                                },
                                modifier = Modifier.heightIn(min = scale.touchMin),
                            ) {
                                Text("还原")
                            }
                        }
                        InfoLine("设备标识", settings.deviceId.ifBlank { "尚未生成" })
                    }
                }

                Cardish(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(scale.gapSm),
                        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
                    ) {
                        SectionTitle("上传节奏")
                        OutlinedTextField(
                            value = intervalDraft,
                            onValueChange = { input ->
                                intervalDraft = input.filter { it.isDigit() }.take(7)
                            },
                            label = { Text("上传间隔（毫秒）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = {
                                Text(
                                    "上一批传完后等待多久再传下一批，范围 0–600000 毫秒（0 = 不限速）。" +
                                        "后台可以在响应里用 next_wait_ms 临时覆盖这个间隔，这里只做兜底。",
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = scale.touchMin),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
                        ) {
                            UPLOAD_INTERVAL_PRESETS.forEach { preset ->
                                val active = settings.uploadIntervalMs == preset
                                val label = if (preset == 0L) "不限速" else "${preset / 1000}s"
                                ChoiceButton(
                                    label = label,
                                    selected = active,
                                    scale = scale,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        viewModel.setUploadIntervalMs(preset)
                                        intervalDraft = preset.toString()
                                    },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(scale.gapXs)) {
                            Button(
                                onClick = {
                                    val value = intervalDraft.toLongOrNull()
                                        ?.coerceIn(0L, MAX_UPLOAD_INTERVAL_MS)
                                        ?: settings.uploadIntervalMs
                                    viewModel.setUploadIntervalMs(value)
                                    intervalDraft = value.toString()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = scale.touchMin),
                            ) {
                                Text("应用间隔")
                            }
                            Text(
                                text = "当前 ${settings.uploadIntervalMs} ms",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
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
                            if (state.pendingSync > 0) StatusPill("待同步 ${state.pendingSync}", FavColors.warn)
                            if (state.failed > 0) StatusPill("失败 ${state.failed}", FavColors.fail)
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = viewModel::retrySyncNow,
                                modifier = Modifier.heightIn(min = scale.touchMin),
                            ) {
                                Text("立即重试")
                            }
                        }
                        Text(
                            "断网时先落本地队列，联网后自动补传；只有后台确认收到的条数才算同步成功。",
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
                        ToggleRow(
                            label = "悬浮球显示同步角标",
                            hint = "球肩上标出还需要处理的条数：红=有条目重试耗尽或被拒绝，灰=离线攒着，黄=排队等上传。" +
                                "贴边半隐藏时角标会被裁到屏幕外，那种状态下改用整球底色报警。关掉后悬浮助手不再读同步队列。",
                            checked = settings.ballStatusBadge,
                            scale = scale,
                            onCheckedChange = viewModel::setBallStatusBadge,
                        )
                    }
                }

                Cardish(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(scale.gapSm),
                        verticalArrangement = Arrangement.spacedBy(scale.gapSm),
                    ) {
                        SectionTitle("播放与缓存")
                        Text(
                            text = "默认倍速",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
                        ) {
                            PLAYBACK_SPEEDS.forEach { speed ->
                                ChoiceButton(
                                    label = formatSpeed(speed),
                                    selected = kotlin.math.abs(settings.defaultPlaybackSpeed - speed) < 0.01f,
                                    scale = scale,
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.setSpeed(speed) },
                                )
                            }
                        }
                        // 这里按目录分开报；「本地缓存占用」那个词在筛选抽屉里是两者合计，别再复用
                        InfoLine(
                            "视频缓存占用",
                            formatBytes(state.cacheBytes) + " / ${settings.cacheLimitMb}MB",
                        )
                        InfoLine(
                            "图片缓存占用",
                            formatBytes(state.imageBytes) + " / ${settings.imageCacheLimitMb}MB",
                        )
                        SectionTitle("缓存上限")
                        Text(
                            "视频缓存",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
                        ) {
                            CACHE_LIMITS_MB.forEach { limit ->
                                ChoiceButton(
                                    label = "${limit}MB",
                                    selected = settings.cacheLimitMb == limit,
                                    scale = scale,
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.setCacheLimitMb(limit) },
                                )
                            }
                        }
                        Text(
                            "图片缓存",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
                        ) {
                            IMAGE_CACHE_LIMITS_MB.forEach { limit ->
                                ChoiceButton(
                                    label = "${limit}MB",
                                    selected = settings.imageCacheLimitMb == limit,
                                    scale = scale,
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.setImageCacheLimitMb(limit) },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(scale.gapXs),
                        ) {
                            OutlinedButton(
                                onClick = viewModel::clearCache,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = scale.touchMin),
                            ) {
                                Text("清空视频缓存")
                            }
                            OutlinedButton(
                                onClick = viewModel::clearImageCache,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = scale.touchMin),
                            ) {
                                Text("清空图片缓存")
                            }
                        }
                        Text(
                            "清空只删本地缓存文件，收藏记录、备注和标签都不受影响。删掉某条收藏时它自己的图片和视频段会立刻回收，" +
                                "这两个按钮管的是剩下的：视频和图片缓存各自受上面「缓存上限」约束，到量自动淘汰最旧的。" +
                                "改图片上限会换掉整个图片加载器，正在屏上的图会闪一下重新加载。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Cardish(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(scale.gapSm),
                        verticalArrangement = Arrangement.spacedBy(scale.gapXs),
                    ) {
                        SectionTitle("存储明细")
                        Text(
                            "只读地量一遍本应用的私有目录，用来对着系统「设置 → 应用 → 存储」那两个数核字节。" +
                                "每个文件两列：表观 = 文件长度，占盘 = 实际占的磁盘块，后者才是系统那个口径。" +
                                "测量不删不改，长按结果可选中复制。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = viewModel::measureStorage,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = scale.touchMin),
                        ) {
                            Text(if (state.storageReport == null) "测量存储占用" else "重新测量")
                        }
                        state.storageReport?.let { report ->
                            SelectionContainer {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 380.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    Text(
                                        text = report,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceButton(
    label: String,
    selected: Boolean,
    scale: TabletScale,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = scale.touchMin),
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = scale.touchMin),
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
