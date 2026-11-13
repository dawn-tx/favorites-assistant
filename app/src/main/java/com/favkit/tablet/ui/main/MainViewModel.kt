package com.favkit.tablet.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.favkit.tablet.data.ArchiveBucket
import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.FavoriteFilter
import com.favkit.tablet.data.ResolveStatus
import com.favkit.tablet.data.SettingsSnapshot
import com.favkit.tablet.data.Tag
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.floating.FloatingDraftStore
import com.favkit.tablet.floating.FloatingSaveUseCase
import com.favkit.tablet.floating.PanelStatus
import com.favkit.tablet.floating.SaveOutcome
import com.favkit.tablet.media.PlayerUiState
import com.favkit.tablet.net.PullOutcome
import com.favkit.tablet.net.VideosOutcome
import com.favkit.tablet.util.VideoLinkDetector
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val items: List<Favorite> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val filter: FavoriteFilter = FavoriteFilter(),
    val selected: Favorite? = null,
    /** 竖屏与窄横屏下详情页作为整屏页打开；宽横屏为常驻右栏，此值恒为 true */
    val detailOpen: Boolean = false,
    val checked: Set<String> = emptySet(),
    val player: PlayerUiState = PlayerUiState(),
    val fullscreen: Boolean = false,
    val online: Boolean = true,
    val queued: Int = 0,
    val unsynced: Int = 0,
    val cacheBytes: Long = 0L,
    val prefetch: Map<String, Long> = emptyMap(),
    /** 正在向后台取解析结果 */
    val resolving: Boolean = false,
    val settings: SettingsSnapshot? = null,
    val showSettings: Boolean = false,
    val quickCollect: Boolean = false,
    val message: String? = null,
) {
    val selectionActive: Boolean get() = checked.isNotEmpty()
    val allChecked: Boolean get() = items.isNotEmpty() && checked.size == items.size
    val unresolvedCount: Int get() = items.count { it.video?.status != ResolveStatus.READY }
}

class MainViewModel private constructor(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val filterFlow = MutableStateFlow(FavoriteFilter())
    private val selectedIdFlow = MutableStateFlow<String?>(null)
    private val detailOpenFlow = MutableStateFlow(false)
    private val checkedFlow = MutableStateFlow<Set<String>>(emptySet())
    private val fullscreenFlow = MutableStateFlow(false)
    private val settingsFlow = MutableStateFlow(false)
    private val quickCollectFlow = MutableStateFlow(false)
    private val messageFlow = MutableStateFlow<String?>(null)
    private val cacheBytesFlow = MutableStateFlow(0L)
    private val prefetchFlow = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val resolvingFlow = MutableStateFlow(false)

    private class PrefetchHandle {
        @Volatile
        var cancelled: Boolean = false
    }

    private val prefetches = ConcurrentHashMap<String, Pair<PrefetchHandle, Job>>()
    private var tickCount = 0

    init {
        viewModelScope.launch { graph.tags.ensurePresets() }
        bindFlows()
        cacheBytesFlow.value = graph.media.totalCachedBytes()
        refreshVideos()
    }

    private fun bindFlows() {
        viewModelScope.launch {
            combine(graph.favorites.observeAll(), filterFlow, selectedIdFlow) { all, filter, selectedId ->
                val visible = all.filter(filter::matches)
                visible to (visible.firstOrNull { it.id == selectedId } ?: visible.firstOrNull())
            }.collect { (visible, detail) ->
                _state.update { it.copy(items = visible, selected = detail) }
            }
        }
        viewModelScope.launch { graph.tags.observeTags().collect { v -> _state.update { it.copy(tags = v) } } }
        viewModelScope.launch { detailOpenFlow.collect { v -> _state.update { it.copy(detailOpen = v) } } }
        viewModelScope.launch { checkedFlow.collect { v -> _state.update { it.copy(checked = v) } } }
        viewModelScope.launch { fullscreenFlow.collect { v -> _state.update { it.copy(fullscreen = v) } } }
        viewModelScope.launch { settingsFlow.collect { v -> _state.update { it.copy(showSettings = v) } } }
        viewModelScope.launch { quickCollectFlow.collect { v -> _state.update { it.copy(quickCollect = v) } } }
        viewModelScope.launch { messageFlow.collect { v -> _state.update { it.copy(message = v) } } }
        viewModelScope.launch { cacheBytesFlow.collect { v -> _state.update { it.copy(cacheBytes = v) } } }
        viewModelScope.launch { prefetchFlow.collect { v -> _state.update { it.copy(prefetch = v) } } }
        viewModelScope.launch { resolvingFlow.collect { v -> _state.update { it.copy(resolving = v) } } }
        viewModelScope.launch { graph.player.state.collect { v -> _state.update { it.copy(player = v) } } }
        viewModelScope.launch { graph.settings.snapshot.collect { v -> _state.update { it.copy(settings = v) } } }
        viewModelScope.launch { graph.sync.queuedCount.collect { v -> _state.update { it.copy(queued = v) } } }
        viewModelScope.launch {
            graph.favorites.unsyncedCount.collect { v -> _state.update { it.copy(unsynced = v) } }
        }
        viewModelScope.launch {
            graph.connectivity.onlineState().collect { v -> _state.update { it.copy(online = v) } }
        }
    }

    // region 筛选与导航
    fun setQuery(query: String) = filterFlow.update { it.copy(query = query) }

    fun setBucket(bucket: ArchiveBucket?) = filterFlow.update { it.copy(bucket = bucket) }

    fun toggleTagFilter(tagId: Long) = filterFlow.update {
        it.copy(tagIds = if (tagId in it.tagIds) it.tagIds - tagId else it.tagIds + tagId)
    }

    fun showUnsyncedOnly(only: Boolean) = filterFlow.update { it.copy(onlyUnsynced = only) }

    fun clearFilters() {
        filterFlow.value = FavoriteFilter()
    }

    /** 新建标签直接进全局标签库，可选顺手加进当前筛选 */
    fun createTag(name: String, applyFilter: Boolean) {
        viewModelScope.launch {
            runCatching { graph.tags.getOrCreate(name) }
                .onSuccess { tag ->
                    if (applyFilter) toggleTagFilter(tag.id)
                    messageFlow.value = "标签「${tag.name}」已加入全局标签库"
                }
                .onFailure { messageFlow.value = "新建标签失败：${it.message}" }
        }
    }

    /** 标签本身变更后，受影响条目的标签快照要重新排队，否则云端停留在旧标签名 */
    fun renameTag(id: Long, name: String) {
        viewModelScope.launch {
            val affected = _state.value.items.filter { favorite -> favorite.tags.any { it.id == id } }
            runCatching { graph.tags.rename(id, name) }
                .onFailure { messageFlow.value = "重命名失败：${it.message}" }
            resync(affected)
        }
    }

    fun deleteTag(id: Long) {
        viewModelScope.launch {
            val affected = _state.value.items.filter { favorite -> favorite.tags.any { it.id == id } }
            graph.tags.delete(id)
            filterFlow.update { it.copy(tagIds = it.tagIds - id) }
            resync(affected)
        }
    }

    private suspend fun resync(favorites: List<Favorite>) {
        if (favorites.isEmpty()) return
        favorites.forEach { favorite ->
            graph.favorites.byId(favorite.id)?.let { graph.sync.queueUpsert(it) }
        }
        messageFlow.value = "标签已更新，${favorites.size} 条收藏重新排队同步"
    }

    /**
     * 点卡片：打开详情并让播放器对准后台给的播放地址。
     * 后台还没解析出可播地址时不启动播放器，由详情面板提示「解析中/解析失败」。
     */
    fun openDetail(id: String) {
        selectedIdFlow.value = id
        detailOpenFlow.value = true
        playIfReady(_state.value.items.firstOrNull { it.id == id })
    }

    fun closeDetail() {
        detailOpenFlow.value = false
    }

    fun showSettings(value: Boolean) {
        settingsFlow.value = value
    }

    fun consumeMessage() {
        messageFlow.value = null
    }
    // endregion

    // region 播放
    private fun playIfReady(favorite: Favorite?) {
        val video = favorite?.video ?: return
        if (!video.playable) return
        graph.player.open(
            itemId = favorite.id,
            url = video.playUrl,
            resumeAtMs = favorite.lastPositionMs,
            speed = _state.value.settings?.defaultPlaybackSpeed ?: 1f,
            autoPlay = false,
        )
    }

    fun togglePlay() {
        graph.player.togglePlay()
        persistProgressNow()
    }

    fun seekTo(positionMs: Long) = graph.player.seekTo(positionMs)

    fun setSpeed(speed: Float) {
        graph.player.setSpeed(speed)
        viewModelScope.launch { graph.settings.setDefaultPlaybackSpeed(speed) }
    }

    fun setFullscreen(value: Boolean) {
        fullscreenFlow.value = value
    }

    /** 详情页每约 500ms 调用一次，进度每 6 次回写一次，避免频繁落盘 */
    fun tick() {
        graph.player.syncTick()
        tickCount += 1
        if (tickCount % 6 == 0) persistProgressNow()
    }

    fun persistProgressNow() {
        val id = _state.value.selected?.id ?: return
        val (position, _) = graph.player.snapshotProgress()
        viewModelScope.launch { graph.favorites.saveProgress(id, position) }
    }
    // endregion

    // region 后台解析
    /** 向后台取未解析条目的视频信息；下拉刷新与启动时各走一次 */
    fun refreshVideos() {
        val pending = _state.value.items
            .filter { it.video?.status != ResolveStatus.READY }
            .map { it.id }
        if (pending.isEmpty()) return
        resolvingFlow.value = true
        viewModelScope.launch {
            when (val outcome = graph.api.fetchVideos(pending)) {
                is VideosOutcome -> applyVideos(outcome, silentEmpty = true)
            }
            resolvingFlow.value = false
        }
    }

    /** 详情页「重新解析」：让后台丢弃旧结果重算 */
    fun resolveNow(id: String) {
        resolvingFlow.value = true
        viewModelScope.launch {
            applyVideos(graph.api.resolveVideos(listOf(id)), silentEmpty = false)
            resolvingFlow.value = false
        }
    }

    private suspend fun applyVideos(outcome: VideosOutcome, silentEmpty: Boolean) {
        if (outcome.videos.isNotEmpty()) graph.favorites.saveVideos(outcome.videos)
        when {
            outcome.error != null -> messageFlow.value = "取解析结果失败：${outcome.error}"
            outcome.videos.isEmpty() && !silentEmpty -> messageFlow.value = "后台暂无这条链接的解析结果"
        }
    }

    /** 本地库为空（换机/清数据）时，从后台整体回填 */
    fun pullFromServer() {
        viewModelScope.launch {
            when (val outcome = graph.api.pull()) {
                is PullOutcome.Loaded -> {
                    graph.favorites.importRemote(outcome.favorites, outcome.videos)
                    messageFlow.value = "已从后台回填 ${outcome.favorites.size} 条收藏"
                }

                is PullOutcome.Failed -> messageFlow.value = "回填失败：${outcome.reason}"
            }
        }
    }
    // endregion

    // region 收藏管理
    fun toggleCheck(id: String) = checkedFlow.update { if (id in it) it - id else it + id }

    fun checkAll() {
        checkedFlow.value = if (_state.value.allChecked) emptySet() else _state.value.items.map { it.id }.toSet()
    }

    fun clearChecks() {
        checkedFlow.value = emptySet()
    }

    fun deleteChecked() {
        val ids = checkedFlow.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            graph.favorites.markDeleted(ids)
            graph.sync.queueDelete(ids)
            checkedFlow.value = emptySet()
            detailOpenFlow.value = false
            messageFlow.value = "已删除 ${ids.size} 条，正在排队上传"
        }
    }

    fun deleteOne(id: String) {
        viewModelScope.launch {
            graph.favorites.markDeleted(listOf(id))
            graph.sync.queueDelete(listOf(id))
            detailOpenFlow.value = false
            messageFlow.value = "已删除，正在排队上传"
        }
    }

    fun saveNote(id: String, note: String) {
        viewModelScope.launch {
            val updated = graph.favorites.updateNote(id, note)
            updated?.let { graph.sync.queueUpsert(it) }
            messageFlow.value = "备注已保存，已排入上传队列"
        }
    }

    fun saveTags(id: String, tagIds: List<Long>) {
        viewModelScope.launch {
            graph.favorites.updateTags(id, tagIds)
            graph.favorites.byId(id)?.let { graph.sync.queueUpsert(it) }
        }
    }

    fun moveTo(id: String, bucket: ArchiveBucket) {
        viewModelScope.launch {
            val updated = graph.favorites.setBucket(id, bucket)
            updated?.let { graph.sync.queueUpsert(it) }
            messageFlow.value = "已归档到「${bucket.label()}」"
        }
    }

    fun retrySync() {
        graph.sync.retryFailed()
        graph.sync.flush()
        messageFlow.value = "已重新排队，联网后自动补传"
    }

    /** 手动把某条视频缓存到本地，离线也能播 */
    fun prefetch(id: String) {
        val favorite = _state.value.items.firstOrNull { it.id == id } ?: return
        val url = favorite.video?.playUrl ?: return
        if (url.isBlank() || prefetches.containsKey(id)) return
        val handle = PrefetchHandle()
        val job = viewModelScope.launch {
            val written = graph.media.prefetch(
                url = url,
                onProgress = { cached -> prefetchFlow.update { it + (id to cached) } },
                isCancelled = { handle.cancelled },
            )
            if (!handle.cancelled && written > 0) {
                graph.favorites.setLocallyCached(id, true)
                messageFlow.value = "已缓存到本地，离线可直接播放"
            }
            prefetches.remove(id)
            prefetchFlow.update { it - id }
            cacheBytesFlow.value = graph.media.totalCachedBytes()
        }
        prefetches[id] = handle to job
    }

    fun cancelPrefetch(id: String) {
        prefetches.remove(id)?.let { (handle, job) ->
            handle.cancelled = true
            job.cancel()
        }
        prefetchFlow.update { it - id }
    }

    fun clearCache() {
        viewModelScope.launch {
            // SimpleCache release 后不能复用，必须先让播放器释放，缓存实例才会重建
            graph.player.release()
            graph.media.clearAll()
            cacheBytesFlow.value = 0L
            messageFlow.value = "本地视频缓存已清空（收藏记录不受影响）"
        }
    }
    // endregion

    // region 设置
    fun setSyncEndpoint(value: String) = viewModelScope.launch { graph.settings.setSyncEndpoint(value) }
    fun setSyncToken(value: String) = viewModelScope.launch { graph.settings.setSyncToken(value) }
    fun setAssistantEnabled(value: Boolean) = viewModelScope.launch { graph.settings.setAssistantEnabled(value) }
    fun setPasteSuggestion(value: Boolean) = viewModelScope.launch { graph.settings.setPasteSuggestion(value) }
    fun setUploadIntervalMs(value: Long) = viewModelScope.launch { graph.settings.setUploadIntervalMs(value) }
    fun setCacheLimitMb(value: Long) = viewModelScope.launch {
        graph.settings.setCacheLimitMb(value)
        // 改上限会重建 SimpleCache 实例，先释放播放器才不会打断正在读缓存的播放
        graph.player.release()
        graph.media.applyLimitFromSettings()
    }
    // endregion

    // region 面板复用（分享入口 / 无悬浮窗权限时的主 App 内收藏）
    fun startQuickCollect(url: String) {
        FloatingDraftStore.update {
            copy(url = url, sourceLabel = VideoLinkDetector.sourceLabel(url), status = PanelStatus.IDLE)
        }
        quickCollectFlow.value = true
    }

    fun dismissQuickCollect() {
        quickCollectFlow.value = false
    }

    fun saveQuickCollect() {
        val draft = FloatingDraftStore.draft.value
        viewModelScope.launch {
            val outcome = FloatingSaveUseCase(graph)
                .save(draft.url, draft.note, draft.selectedTagIds, draft.newTagName)
            when (outcome) {
                is SaveOutcome.Queued -> {
                    FloatingDraftStore.clearAfterSave()
                    quickCollectFlow.value = false
                    messageFlow.value = outcome.reason
                    refreshVideos()
                }

                is SaveOutcome.Rejected ->
                    FloatingDraftStore.update { copy(status = PanelStatus.FAILED, message = outcome.reason) }
            }
        }
    }
    // endregion

    override fun onCleared() {
        prefetches.values.forEach { (handle, job) ->
            handle.cancelled = true
            job.cancel()
        }
        persistProgressNow()
        graph.player.release()
        super.onCleared()
    }

    companion object {
        fun factory(graph: AppGraph) = viewModelFactory {
            initializer { MainViewModel(graph) }
        }
    }
}

fun ArchiveBucket.label(): String = when (this) {
    ArchiveBucket.INBOX -> "待看"
    ArchiveBucket.ARCHIVED -> "已归档"
    ArchiveBucket.STARRED -> "精选"
}
