package com.favkit.tablet.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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

/**
 * 聚焦遮罩层看的是哪一条、第几段。
 *
 * 段号放在这里而不是从 `selected` 现推，是因为切段要能撤销、转屏和进程重启后也要回到
 * 用户刚才看的那一段；[playerKey] 是播放器认的装载键，同一个收藏的不同段互不覆盖。
 */
data class FocusUiState(
    val itemId: String,
    val segmentIndex: Int = 0,
) {
    val playerKey: String get() = "$itemId#$segmentIndex"
}

data class MainUiState(
    val items: List<Favorite> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val filter: FavoriteFilter = FavoriteFilter(),
    /** 聚焦遮罩层看的是哪一条、第几段；为空表示正在看墙。遮罩是盖在墙上的，墙不会因此退出组合，滚动位置天然保留 */
    val focus: FocusUiState? = null,
    /** 筛选与标签管理收进顶栏抽屉，不再占一栏 */
    val showFilters: Boolean = false,
    val checked: Set<String> = emptySet(),
    val player: PlayerUiState = PlayerUiState(),
    val fullscreen: Boolean = false,
    val online: Boolean = true,
    val queued: Int = 0,
    /** 队列里重试耗尽/被后台拒绝、需要用户动手的那部分 */
    val failed: Int = 0,
    val unsynced: Int = 0,
    /** 还没解析出素材的条数，由库里数出来：以前从可见列表里数，筛一次就变小一次 */
    val unresolvedCount: Int = 0,
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
    /** [queued] 是队列总数，含 [failed]；胶囊与角标都显示「还会自动再传的」，避免红黄两枚数同一批 */
    val pendingUploads: Int get() = (queued - failed).coerceAtLeast(0)
}

class MainViewModel private constructor(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val filterFlow = MutableStateFlow(FavoriteFilter())
    private val focusFlow = MutableStateFlow<FocusUiState?>(null)
    private val filtersFlow = MutableStateFlow(false)
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
            combine(graph.favorites.observeAll(), filterFlow) { all, filter -> all.filter(filter::matches) }
                .collect { visible -> _state.update { it.copy(items = visible) } }
        }
        viewModelScope.launch { graph.tags.observeTags().collect { v -> _state.update { it.copy(tags = v) } } }
        // 筛选条件本身也要回写 state：items 由 combine 过滤，但 UI 的选中态、搜索框文字、
        // 「清除筛选」按钮都读 state.filter，不同步就会出现「筛了却看不见筛了什么」。
        viewModelScope.launch { filterFlow.collect { v -> _state.update { it.copy(filter = v) } } }
        viewModelScope.launch { focusFlow.collect { v -> _state.update { it.copy(focus = v) } } }
        viewModelScope.launch { filtersFlow.collect { v -> _state.update { it.copy(showFilters = v) } } }
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
        viewModelScope.launch { graph.sync.failedCount.collect { v -> _state.update { it.copy(failed = v) } } }
        viewModelScope.launch {
            graph.favorites.unresolvedCount.collect { v -> _state.update { it.copy(unresolvedCount = v) } }
        }
        viewModelScope.launch {
            graph.favorites.unsyncedCount.collect { v -> _state.update { it.copy(unsynced = v) } }
        }
        viewModelScope.launch {
            graph.connectivity.onlineState().collect { v -> _state.update { it.copy(online = v) } }
        }
    }

    // region 筛选与导航
    fun setQuery(query: String) = filterFlow.update { it.copy(query = query) }

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
            val affected = graph.favorites.favoritesWithTag(id)
            runCatching { graph.tags.rename(id, name) }
                .onFailure { messageFlow.value = "重命名失败：${it.message}" }
            resync(affected)
        }
    }

    fun deleteTag(id: Long) {
        viewModelScope.launch {
            val affected = graph.favorites.favoritesWithTag(id)
            graph.tags.delete(id)
            // 删掉的标签若正被用作筛选，必须一并撤掉，否则条件筛出 0 条、界面上又看不出来
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
     * 点卡片：把这条内容摊开放在遮罩层里。
     *
     * 落在上次看的那一段（老数据没有段号就第 0 段），因为后台给的 `media[]` 顺序就是阅读顺序，
     * 中途退出再进来不该从第一张重看。
     */
    fun openFocus(id: String) {
        val favorite = _state.value.items.firstOrNull { it.id == id } ?: return
        if (favorite.segments.isEmpty()) return openFallback(favorite)
        val start = favorite.lastSegmentIndex.takeIf { it in favorite.segments.indices } ?: 0
        focusFlow.value = FocusUiState(id, start)
        filtersFlow.value = false
        loadSegment(favorite, start, autoPlay = false)
    }

    /** 还没解析出素材时也打开：遮罩层要能说明「在等什么」，并且留下重新解析的入口 */
    private fun openFallback(favorite: Favorite) {
        focusFlow.value = FocusUiState(favorite.id, 0)
        filtersFlow.value = false
        graph.player.pausePlayback()
    }

    fun closeFocus() {
        persistProgressNow()
        focusFlow.value = null
    }

    /** 筛选与标签管理收进顶栏抽屉；遮罩层打开时顶栏收起，所以抽屉只从墙上打开 */
    fun showFilters(value: Boolean) {
        filtersFlow.value = value
    }

    /** 分段条点击、媒体区左右滑动都走这里；[FocusUiState.playerKey] 让每段各自记住看到哪 */
    fun showSegment(index: Int) {
        val focus = focusFlow.value ?: return
        val favorite = _state.value.items.firstOrNull { it.id == focus.itemId } ?: return
        if (index !in favorite.segments.indices || index == focus.segmentIndex) return
        persistProgressNow()
        focusFlow.value = FocusUiState(focus.itemId, index)
        viewModelScope.launch { graph.favorites.setLastSegment(favorite.id, index) }
        // 在遮罩层里主动翻段 = 直接操作，视频段自动播起来；从墙上点开时才停在暂停态等用户按播放
        loadSegment(favorite, index, autoPlay = true)
    }

    /** ‹ › 换一条：遮罩层不关，墙跟着滚到新位置 */
    fun stepItem(delta: Int) {
        val items = _state.value.items
        if (items.isEmpty()) return
        val position = items.indexOfFirst { it.id == focusFlow.value?.itemId }
        val target = if (position < 0) 0 else (position + delta).coerceIn(0, items.lastIndex)
        persistProgressNow()
        openFocus(items[target].id)
    }

    fun showSettings(value: Boolean) {
        settingsFlow.value = value
    }

    fun consumeMessage() {
        messageFlow.value = null
    }
    // endregion

    // region 播放
    private fun loadSegment(favorite: Favorite, index: Int, autoPlay: Boolean) {
        val segment = favorite.segments.getOrNull(index) ?: return
        if (!segment.playable) {
            // 翻到图集段：让视频安静下来，实例不释放，翻回去还是秒开
            graph.player.pausePlayback()
            return
        }
        val resumeAtMs = favorite.segmentProgress[index]
            ?: if (index == favorite.lastSegmentIndex) favorite.lastPositionMs else 0L
        graph.player.open(
            itemId = FocusUiState(favorite.id, index).playerKey,
            url = segment.url,
            resumeAtMs = resumeAtMs,
            speed = _state.value.settings?.defaultPlaybackSpeed ?: 1f,
            autoPlay = autoPlay,
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

    /** 遮罩层每约 500ms 调用一次，进度每 6 次回写一次，避免频繁落盘 */
    fun tick() {
        graph.player.syncTick()
        tickCount += 1
        if (tickCount % 6 == 0) persistProgressNow()
    }

    /** 把「第几段看到哪」写回本地；播放器还没装载时别用 0 去覆盖已有进度 */
    fun persistProgressNow() {
        val focus = focusFlow.value ?: return
        val (position, _) = graph.player.snapshotProgress()
        if (position <= 0L) return
        viewModelScope.launch { graph.favorites.saveProgress(focus.itemId, focus.segmentIndex, position) }
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

    /** 遮罩层「重新解析」：让后台丢弃旧结果重算 */
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
            focusFlow.value = null
            messageFlow.value = "已删除 ${ids.size} 条，正在排队上传"
        }
    }

    fun deleteOne(id: String) {
        viewModelScope.launch {
            graph.favorites.markDeleted(listOf(id))
            graph.sync.queueDelete(listOf(id))
            focusFlow.value = null
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

    /**
     * 遮罩层里点标签即时生效：本地一次写库 + 一次排队，比「改完再按保存」少一步，
     * 也让快速新建的标签立刻出现在条目上，不需要 UI 自己维护一份勾选草稿。
     */
    fun toggleTagOnItem(id: String, tagId: Long) {
        viewModelScope.launch {
            val favorite = graph.favorites.byId(id) ?: return@launch
            val ids = favorite.tags.map { it.id }.toMutableList()
            if (!ids.remove(tagId)) ids.add(tagId)
            graph.favorites.updateTags(id, ids)
            graph.favorites.byId(id)?.let { graph.sync.queueUpsert(it) }
        }
    }

    /** 「快速新建标签」：入库 + 直接挂到当前这条，省掉「先去标签库建、回来再勾选」 */
    fun createTagOnItem(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { graph.tags.getOrCreate(trimmed) }
                .onSuccess { tag -> toggleTagOnItem(id, tag.id) }
                .onFailure { messageFlow.value = "新建标签失败：${it.message}" }
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
    fun setBallStatusBadge(value: Boolean) = viewModelScope.launch { graph.settings.setBallStatusBadge(value) }
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

                is SaveOutcome.Duplicate ->
                    FloatingDraftStore.update { copy(status = PanelStatus.NOTICE, message = outcome.reason) }

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
