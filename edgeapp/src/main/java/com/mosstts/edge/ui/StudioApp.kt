@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mosstts.edge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mosstts.edge.*
import com.mosstts.edge.R
import com.mosstts.edge.data.AudioRecord
import com.mosstts.edge.protocol.EdgeVoice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun StudioApp(model: StudioViewModel, export: (AudioRecord, String) -> Unit,
                          share: (AudioRecord) -> Unit, systemSettings: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var voiceTarget by remember { mutableStateOf<Boolean?>(null) }
    var deleteTarget by remember { mutableStateOf<AudioRecord?>(null) }
    var clearHistory by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { value -> snack.showSnackbar(value); model.clearMessage(value) }
    }
    EdgeTheme(state.theme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                CenterAlignedTopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(painterResource(R.drawable.ic_edge_tts), null, Modifier.size(32.dp))
                        Text("Edge", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Text("语音工作室", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf("创作", "最近", "设置").forEachIndexed { index, title ->
                        NavigationBarItem(selected = page == index,
                            onClick = { page = index; if (index == 1) model.refreshHistory() },
                            icon = { Icon(when (index) { 0 -> Icons.Default.Create; 1 -> Icons.AutoMirrored.Filled.List; else -> Icons.Default.Settings }, null) },
                            label = { Text(title) }, modifier = Modifier.testTag("nav_$index"))
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Crossfade(page, label = "studio_page", modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()) { current ->
                    when (current) {
                        0 -> CreationScreen(state, model, { voiceTarget = false }, export, share,
                            { deleteTarget = it })
                        1 -> HistoryScreen(state, model, export, share,
                            { model.useRecord(it); page = 0 }, { deleteTarget = it })
                        else -> SettingsScreen(state, model, systemSettings,
                            { voiceTarget = true }, { clearHistory = true }, { licenses = true })
                    }
                }
            }
        }
        voiceTarget?.let { system ->
            VoiceSheet(state, if (system) state.systemVoice else state.voice,
                { model.stopPreview(); voiceTarget = null },
                { model.selectVoice(it.name, system); model.stopPreview(); voiceTarget = null }, model::preview)
        }
        deleteTarget?.let { record ->
            AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除这条记录？") },
                text = { Text(record.title + "\n\n本机音频和字幕将一并删除，已导出的文件不受影响。") },
                confirmButton = { TextButton({ model.delete(record); deleteTarget = null }) { Text("删除") } },
                dismissButton = { TextButton({ deleteTarget = null }) { Text("取消") } })
        }
        if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("清空最近记录？") },
            text = { Text("将删除本机保存的 ${state.history.size} 条记录及音频、字幕。已导出的文件不受影响。") },
            confirmButton = { TextButton({ model.clearHistory(); clearHistory = false }) { Text("清空") } },
            dismissButton = { TextButton({ clearHistory = false }) { Text("取消") } })
        if (licenses) LicenseDialog { licenses = false }
    }
}

@Composable private fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable private fun CreationScreen(state: StudioState, model: StudioViewModel, chooseVoice: () -> Unit,
                                       export: (AudioRecord, String) -> Unit, share: (AudioRecord) -> Unit,
                                       delete: (AudioRecord) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val scroll = rememberLazyListState()
    var previousGenerating by remember { mutableStateOf(false) }
    var previousResult by remember { mutableStateOf(state.result?.id) }
    LaunchedEffect(state.generating) {
        if (!state.generating && previousGenerating && state.result?.id != previousResult) scroll.animateScrollToItem(3)
        previousGenerating = state.generating
        previousResult = state.result?.id
    }
    var controls by rememberSaveable { mutableStateOf(false) }
    val voice = state.voices.firstOrNull { it.name == state.voice }
    LazyColumn(Modifier.fillMaxSize().testTag("creation_list"), state = scroll, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("输入文本", fontWeight = FontWeight.SemiBold)
                    Row {
                        TextButton(onClick = { clipboard.getText()?.text?.let(model::text) }, enabled = !state.generating,
                            contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.widthIn(min = 48.dp)) { Text("粘贴") }
                        TextButton(onClick = { model.text("") }, enabled = !state.generating && state.text.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.widthIn(min = 48.dp).testTag("clear_text")) { Text("清空") }
                        TextButton(onClick = { model.text("早安。给自己一点时间，让生活慢下来。\n每一个新的开始，都藏着值得期待的可能。") }, enabled = !state.generating,
                            contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.widthIn(min = 48.dp)) { Text("示例") }
                    }
                }
                OutlinedTextField(state.text, model::text, modifier = Modifier.fillMaxWidth().testTag("text_input"),
                    placeholder = { Text("在这里输入或粘贴文字…") },
                    minLines = 3, maxLines = 8, readOnly = state.generating, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
                Text("${state.text.length} / 20,000", modifier = Modifier.align(Alignment.End),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            SectionCard {
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.generating, onClick = chooseVoice).testTag("choose_voice"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceAvatar(voice, 36)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(voice?.let(::voiceLabel) ?: state.voice, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.KeyboardArrowDown, "选择音色", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp))
                        }
                        Text(voice?.let(::voiceDescription) ?: "选择在线音色", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalIconButton(onClick = { voice?.let(model::preview) }, enabled = voice != null && !state.generating) {
                        if (state.previewLoading == voice?.name) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else if (state.playback.id == "sample:${voice?.name}" && state.playback.playing) PauseIcon()
                        else Icon(Icons.Default.PlayArrow, "试听当前音色")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { controls = !controls }, label = { Text("语速 ${speed(state.rate)}", maxLines = 1) },
                        enabled = !state.generating, modifier = Modifier.weight(1f))
                    AssistChip(onClick = { controls = !controls }, label = { Text("音调 ${state.pitch}%", maxLines = 1) },
                        enabled = !state.generating, modifier = Modifier.weight(1f))
                }
                AnimatedVisibility(controls) {
                    Column {
                        ParameterSlider("语速", state.rate, 50..300, { model.rate(it) }, !state.generating)
                        ParameterSlider("音调", state.pitch, 50..150, { model.pitch(it) }, !state.generating)
                        TextButton(onClick = { model.rate(100); model.pitch(100) }, enabled = !state.generating) { Text("恢复标准") }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { focus.clearFocus(); model.generate() }, enabled = !state.generating && state.text.isNotBlank() && voice != null,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("generate"), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.generating) "正在生成语音…" else "生成语音", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                if (state.generating) {
                    LinearProgressIndicator(progress = { state.completedParts.toFloat() / state.totalParts.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("已完成 ${state.completedParts} / ${state.totalParts} 段", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        TextButton(model::cancelGeneration) { Text("取消生成") }
                    }
                }
            }
        }
        state.result?.let { record -> item {
            RecordCard(record, state, { model.play(record) }, model::seek, { export(record, it) }, { share(record) },
                { model.useRecord(record) }, { delete(record) }, compact = false)
        } }
    }
}

@Composable internal fun RecordCard(record: AudioRecord, state: StudioState, play: () -> Unit, seek: (Long) -> Unit,
                                   export: (String) -> Unit, share: () -> Unit, edit: () -> Unit, delete: () -> Unit,
                                   compact: Boolean) {
    val active = state.playback.id == record.id
    val duration = if (active && state.playback.durationMs > 0) state.playback.durationMs else record.durationMs
    var menu by remember { mutableStateOf(false) }
    var dragging by remember(record.id) { mutableStateOf<Float?>(null) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f)) else null,
        modifier = Modifier.fillMaxWidth().testTag("record_${record.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(record.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val details = "${record.voiceLabel.take(24)} · ${speed(record.rate)} · ${time(duration)}"
                    val date = if (compact) SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(record.createdAt)) else "已生成"
                    Text("$date · $details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "记录操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("继续编辑") }, onClick = { menu = false; edit() }, enabled = !state.generating)
                        DropdownMenuItem(text = { Text("分享音频") }, onClick = { menu = false; share() })
                        if (record.hasSubtitles) {
                            DropdownMenuItem(text = { Text("下载 SRT 字幕") }, onClick = { menu = false; export("srt") }, enabled = !state.exporting)
                            DropdownMenuItem(text = { Text("下载 VTT 字幕") }, onClick = { menu = false; export("vtt") }, enabled = !state.exporting)
                        }
                        DropdownMenuItem(text = { Text("删除记录", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delete() })
                    }
                }
            }
            if (!compact || active) {
                Slider(value = dragging ?: if (active) state.playback.positionMs.toFloat().coerceAtMost(duration.toFloat()) else 0f,
                    onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { seek(it.toLong()) }; dragging = null },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = active && !state.playback.preparing,
                    modifier = Modifier.fillMaxWidth().height(28.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(time(if (active) state.playback.positionMs else 0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(time(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledIconButton(play, enabled = !state.playback.preparing, modifier = Modifier.size(48.dp)) {
                    if (active && state.playback.preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else if (active && state.playback.playing) PauseIcon()
                    else Icon(Icons.Default.PlayArrow, "播放音频")
                }
                Text(if (active && state.playback.playing) "正在播放" else if (active && state.playback.positionMs > 0) "继续播放" else "试听音频",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                OutlinedButton({ export("mp3") }, enabled = !state.exporting, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    DownloadIcon(); Spacer(Modifier.width(6.dp)); Text("保存 MP3")
                }
            }
        }
    }
}

@Composable private fun HistoryScreen(state: StudioState, model: StudioViewModel, export: (AudioRecord, String) -> Unit,
                                     share: (AudioRecord) -> Unit, edit: (AudioRecord) -> Unit, delete: (AudioRecord) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("最近记录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${state.history.size} 条 · 本机保存", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.history.isEmpty()) item {
            SectionCard {
                Spacer(Modifier.height(8.dp))
                Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally))
                Text("还没有生成记录", fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally))
                Text("在创作页生成第一段语音，\n它会自动出现在这里。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
            }
        }
        items(state.history, key = { it.id }) { record ->
            RecordCard(record, state, { model.play(record) }, model::seek, { export(record, it) }, { share(record) },
                { edit(record) }, { delete(record) }, compact = true)
        }
        item { Text("保留最近 20 条，最多 100 MB。已导出的文件不受历史清理影响。",
            Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun SettingsScreen(state: StudioState, model: StudioViewModel, systemSettings: () -> Unit,
                                      chooseVoice: () -> Unit, clearHistory: () -> Unit, licenses: () -> Unit) {
    val context = LocalContext.current
    val systemVoice = state.voices.firstOrNull { it.name == state.systemVoice }
    LazyColumn(Modifier.fillMaxSize().testTag("settings_list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("设置", Modifier.padding(vertical = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item {
            SectionCard("系统文字转语音") {
                Text("让阅读器和其他应用使用 Edge 在线语音。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(systemSettings, modifier = Modifier.fillMaxWidth().testTag("system_settings")) {
                    Icon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); Text("打开系统文字转语音设置")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().clickable(onClick = chooseVoice), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("系统默认音色", style = MaterialTheme.typography.labelLarge)
                        Text(systemVoice?.let(::voiceLabel) ?: state.systemVoice, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.KeyboardArrowDown, "选择系统音色")
                }
                TextButton({ model.selectVoice(state.voice, true) }) { Text("使用当前创作音色") }
                ParameterSlider("默认语速", state.systemRate, 50..300, { model.rate(it, true) })
                ParameterSlider("默认音调", state.systemPitch, 50..150, { model.pitch(it, true) })
                Text("这里的默认值用于系统引擎。阅读器传入的语速和音调会与默认值相乘，音量由系统控制。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SectionCard("外观与播放") {
                Text("界面主题", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("跟随系统", "浅色", "深色").forEachIndexed { index, label ->
                        FilterChip(state.theme == index, { model.theme(index) }, label = { Text(label) })
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("生成后自动播放", style = MaterialTheme.typography.bodyLarge)
                        Text("语音准备好后立即试听", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(state.autoPlay, model::autoPlay)
                }
            }
        }
        item {
            SectionCard("音色与存储") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("在线音色", fontWeight = FontWeight.Medium)
                        Text("当前 ${state.voices.size} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(model::refreshVoices, enabled = !state.refreshing) {
                        if (state.refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("刷新") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("最近记录保留 20 条，最多占用 100 MB。较旧记录会自动清理，下载到其他位置的文件会保留。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(clearHistory, enabled = state.history.isNotEmpty() && !state.generating) { Text("清空最近记录", color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            SectionCard("关于 Edge TTS") {
                Text("语音生成需要联网，文本直接发送至 Microsoft。历史记录、音频和字幕保存在本机。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(licenses) { Text("开源许可") }
                    TextButton({ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/keosu/MOSS-TTS-Nano-Android/tree/feature/edge-tts/edgeapp"))) }
                        .onFailure { model.message("未找到可打开链接的浏览器") } }) { Text("源代码") }
                }
                Text("版本 1.1.1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun VoiceSheet(state: StudioState, selected: String, dismiss: () -> Unit,
                                  choose: (EdgeVoice) -> Unit, preview: (EdgeVoice) -> Unit) {
    var query by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(state.voices.firstOrNull { it.name == selected }?.locale?.language) }
    var gender by remember { mutableStateOf<String?>(null) }
    var languageMenu by remember { mutableStateOf(false) }
    val filtered = remember(state.voices, query, language, gender) {
        state.voices.filter { voice -> (language == null || voice.locale.language == language) &&
            (gender == null || voice.gender == gender) &&
            (query.isBlank() || (voice.name + voice.displayName + voiceDescription(voice)).contains(query, true)) }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 12.dp).testTag("voice_sheet"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("选择音色", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(query, { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("搜索音色、语言或地区") }, modifier = Modifier.fillMaxWidth().testTag("voice_search"), shape = RoundedCornerShape(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    FilterChip(language != null, { languageMenu = true }, label = { Text(language?.let { Locale(it).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE) } ?: "全部语言") },
                        trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp)) })
                    DropdownMenu(languageMenu, { languageMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        DropdownMenuItem(text = { Text("全部语言") }, onClick = { language = null; languageMenu = false })
                        state.voices.map { it.locale.language }.distinct().sorted().forEach { code ->
                            DropdownMenuItem(text = { Text(Locale(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)) },
                                onClick = { language = code; languageMenu = false })
                        }
                    }
                }
                listOf(null to "全部音色", "Female" to "女声", "Male" to "男声").forEach { (value, label) ->
                    FilterChip(gender == value, { gender = value }, label = { Text(label) })
                }
            }
            Text("${filtered.size} 个音色 · 点播放按钮试听", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(filtered, key = { it.name }) { voice ->
                    val active = voice.name == selected
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { choose(voice) }.padding(horizontal = 8.dp, vertical = 4.dp).testTag("voice_${voice.name}"), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VoiceAvatar(voice, 32)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(voiceLabel(voice), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(voiceDescription(voice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (active) Icon(Icons.Default.Check, "已选择", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        IconButton({ preview(voice) }) {
                            if (state.previewLoading == voice.name) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else if (state.playback.id == "sample:${voice.name}" && state.playback.playing) PauseIcon()
                            else Icon(Icons.Default.PlayArrow, "试听 ${voiceLabel(voice)}")
                        }
                    }
                }
                if (filtered.isEmpty()) item { Text("没有找到匹配的音色，试试其他关键词。", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable private fun ParameterSlider(label: String, value: Int, range: IntRange, change: (Int) -> Unit, enabled: Boolean = true) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(if (label.contains("语速")) speed(value) else "$value%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        Slider(value.toFloat(), { change(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled)
    }
}

@Composable private fun VoiceAvatar(voice: EdgeVoice?, size: Int = 48) {
    val color = if (voice?.gender == "Female") Color(0xFF8270B9) else MaterialTheme.colorScheme.primary
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
        Text(voice?.let(::voiceLabel)?.take(1) ?: "声", color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}
@Composable private fun DownloadIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        drawLine(color, Offset(w * .5f, h * .1f), Offset(w * .5f, h * .65f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(w * .25f, h * .4f), Offset(w * .5f, h * .65f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(w * .75f, h * .4f), Offset(w * .5f, h * .65f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(w * .2f, h * .9f), Offset(w * .8f, h * .9f), 2.dp.toPx(), StrokeCap.Round)
    }
}
@Composable private fun PauseIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp).semantics { contentDescription = "暂停音频" }) {
        drawRoundRect(color, Offset(size.width * .25f, size.height * .2f), Size(size.width * .16f, size.height * .6f), CornerRadius(2.dp.toPx()))
        drawRoundRect(color, Offset(size.width * .59f, size.height * .2f), Size(size.width * .16f, size.height * .6f), CornerRadius(2.dp.toPx()))
    }
}
@Composable private fun LicenseDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember { listOf("NOTICE.txt", "Apache-2.0.txt", "LGPL-3.0.txt", "GPL-3.0.txt")
        .joinToString("\n\n") { context.assets.open("licenses/$it").bufferedReader().use { reader -> reader.readText() } } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("开源许可") },
        text = { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(dismiss) { Text("关闭") } })
}
private fun voiceLabel(voice: EdgeVoice): String = if (voice.displayName.contains("Microsoft"))
    voice.name.substringAfterLast('-').removeSuffix("Neural") else voice.displayName
private fun voiceDescription(voice: EdgeVoice): String = voice.locale.getDisplayName(Locale.SIMPLIFIED_CHINESE) +
    " · " + if (voice.gender == "Female") "女声" else "男声"
private fun speed(rate: Int): String = String.format(Locale.ROOT, "%.1f×", rate / 100f)
private fun time(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
