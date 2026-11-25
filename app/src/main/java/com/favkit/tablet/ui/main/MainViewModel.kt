package com.favkit.tablet.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.favkit.tablet.data.Favorite
import com.favkit.tablet.data.FavoriteFilter
import com.favkit.tablet.data.MediaKind
import com.favkit.tablet.data.MediaState
import com.favkit.tablet.data.SettingsSnapshot
import com.favkit.tablet.data.SourceMode
import com.favkit.tablet.data.SyncBadge
import com.favkit.tablet.data.Tag
import com.favkit.tablet.di.AppGraph
import com.favkit.tablet.floating.FloatingDraftStore
import com.favkit.tablet.floating.FloatingSaveUseCase
import com.favkit.tablet.floating.PanelStatus
import com.favkit.tablet.floating.SaveOutcome
import com.favkit.tablet.media.PlayerUiState
import com.favkit.tablet.net.LookupOutcome
import com.favkit.tablet.net.PullOutcome
import com.favkit.tablet.util.VideoLinkDetector
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
    val sourceMode: SourceMode = SourceMode.PRIMARY,
    val backupIndex: Int = 0,
    /** primary URL/版本：后台换档会推新地址，进 key 才能触发重载 */
    val primaryRevision: String = "",
    /** 当前备用项的 URL：同一 backupIndex 下 URL 变了也要重载 */
    val backupRevision: String = "",
) {
    val playerKey: String
        get() = "$itemId#$segmentIndex#${sourceMode.name}#$backupIndex#$primaryRevision#$backupRevision"
}

private data class PlaybackItem(
    val url: String,
    val kind: MediaKind,
    val title: String,
    val durationMs: Long,
    val quality: String,
)

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
    val online: Boolean = true,
    val queued: Int = 0,
    /** 队列里重试耗尽/被后台拒绝、需要用户动手的那部分 */
    val failed: Int = 0,
    val cacheBytes: Long = 0L,
    /** 图片磁盘缓存（Coil）实际占用，与视频缓存分开记：两者落在不同目录、清法也不同 */
    val imageBytes: Long = 0L,
    val prefetch: Map<String, Long> = emptyMap(),
    /** 设置页「存储明细」最近一次测量的原文；null = 还没测过 */
    val storageReport: String? = null,
    /** 上传器在等后台响应：顶栏按钮用它区分「正在排队」和「点了没用」 */
    val syncing: Boolean = false,
    /** 正在从后台取下一页收藏 */
    val remoteLoading: Boolean = false,
    /** 后台还有没发完的页；滚到底要不要补页、列表尾部挂不挂加载位都看它 */
    val remoteHasMore: Boolean = true,
    /** 后台上报的收藏总数（每页都带）；0 = 这个后台没上报，界面退回只说本地条数 */
    val remoteTotal: Int = 0,
    /** 本地缓存的条数，不带筛选：计数文案要的是「已取回多少」，跟筛出多少条无关 */
    val cachedTotal: Int = 0,
    val settings: SettingsSnapshot? = null,
    val showSettings: Boolean = false,
    val quickCollect: Boolean = false,
    val message: String? = null,
    /** 对账失败的原因（底部悬浮提示条读它）；null = 上次对账成功 */
    val sweepError: String? = null,
    /** 打开待就绪条目时那次单条查询失败了：只影响提示，不动库里任何状态 */
    val contentRefreshError: String? = null,
) {
    val selectionActive: Boolean get() = checked.isNotEmpty()
    val allChecked: Boolean get() = items.isNotEmpty() && checked.size == items.size

    /**
     * 「待同步」的唯一口径：队列里还会自动再传的那部分。
     *
     * 后台保存与解析在同一次调用里完成，收藏传成功内容信息也就到了，所以不存在「待解析」
     * 这第二个数；解析失败是那条内容自己的事，在卡片和遮罩层上说明，不进计数。
     */
    val pendingSync: Int get() = (queued - failed).coerceAtLeast(0)

    /**
     * 本地只缓存了还没取完的几页时才说「已取回 M / N」—— 这时候顶栏再说「共 N 条」
     * 就是把后台的库说成了这一屏。
     *
     * 页取全了（[remoteHasMore] 为假）就别再报后台总数：本地就是后台的镜像，删掉一条之后
     * 再拿那个只在翻页时刷新的总数来比，会出现「共 20 条 · 已取回 19 条」这种把刚删的那条
     * 说成还没取回的话。后台没上报总数（[remoteTotal] 为 0）时同样不算部分。
     */
    val cachePartial: Boolean get() = remoteHasMore && remoteTotal > cachedTotal

    /** 筛选抽屉那行说的是「一共占了多少」，所以两个目录加起来报；设置页仍按目录分开列 */
    val cacheBytesTotal: Long get() = cacheBytes + imageBytes
}

class MainViewModel private constructor(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val filterFlow = MutableStateFlow(FavoriteFilter())
    private val focusFlow = MutableStateFlow<FocusUiState?>(null)
    private val filtersFlow = MutableStateFlow(false)
    private val checkedFlow = MutableStateFlow<Set<String>>(emptySet())
    private val settingsFlow = MutableStateFlow(false)
    private val quickCollectFlow = MutableStateFlow(false)
    private val messageFlow = MutableStateFlow<String?>(null)
    private val cacheBytesFlow = MutableStateFlow(0L)
    private val imageBytesFlow = MutableStateFlow(0L)
    private val prefetchFlow = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val storageFlow = MutableStateFlow<String?>(null)

    private class PrefetchHandle {
        @Volatile
        var cancelled: Boolean = false
    }

    private class AheadPrefetch(
        val favoriteId: String,
        val handle: PrefetchHandle,
        val job: Job,
    )

    private val prefetches = ConcurrentHashMap<String, Pair<PrefetchHandle, Job>>()
    private val prefetchingUrls = ConcurrentHashMap.newKeySet<String>()
    private var aheadPrefetch: AheadPrefetch? = null

    /** 主源失败只自动切一次；同一个 playerKey 不来回跳 */
    private val autoFallbackKeys = ConcurrentHashMap.newKeySet<String>()
    private val failureLookups = ConcurrentHashMap.newKeySet<String>()

    /** 后台下一页的游标；进程死了就归零，重取第一页靠 id 去重，不会翻倍 */
    private var remoteCursor: String? = null
    private var remotePaging = false

    init {
        viewModelScope.launch { graph.tags.ensurePresets() }
        bindFlows()
        viewModelScope.launch { graph.player.ended.collect { key -> advanceAfterEnd(key) } }
        viewModelScope.launch { cacheBytesFlow.value = graph.media.totalCachedBytes() }
        seedRemoteCache()
    }

    /** 本地缓存空着 = 换机或清了数据，不必等用户点按钮，自己去后台取第一页 */
    private fun seedRemoteCache() {
        viewModelScope.launch {
            if (graph.favorites.cachedCount() == 0) loadRemotePage(announce = false)
        }
    }

    /**
     * 进前台对一次账（翻一遍后台清单做差集）。
     *
     * WS 只推它在线期间发生的变化；进后台/断线期间别的设备删了什么、加了什么，重连后不会补发，
     * 所以这一轮完整差集仍然是删除判断的唯一来源。
     * 单飞由 [com.favkit.tablet.sync.SyncManager.sweep] 里的 AtomicBoolean 保证，撞上了回 BUSY。
     * 冷启动那一次由 MainActivity 用 `window.decorView.post` 推到首帧之后再跑。
     * **镜像空着就退让**：表空 = 换机或清了数据，[seedRemoteCache] 已经去后台取第一页。
     */
    fun onEnterForeground() {
        if (!_state.value.online) return
        viewModelScope.launch {
            if (graph.favorites.cachedCount() == 0) return@launch
            graph.sync.sweep()
        }
    }

    /** 底部提示条上的「重试」：用户手点，不构成定时重试 */
    fun retrySweep() {
        viewModelScope.launch { graph.sync.sweep() }
    }

    private fun bindFlows() {
        viewModelScope.launch {
            combine(graph.favorites.observeAll(), filterFlow) { all, filter -> all.filter(filter::matches) }
                .collect { visible ->
                    _state.update { it.copy(items = visible) }
                    reconcileFocus(visible)
                }
        }
        viewModelScope.launch { graph.tags.observeTags().collect { v -> _state.update { it.copy(tags = v) } } }
        // 筛选条件本身也要回写 state：items 由 combine 过滤，但 UI 的选中态、搜索框文字、
        // 「清除筛选」按钮都读 state.filter，不同步就会出现「筛了却看不见筛了什么」。
        viewModelScope.launch { filterFlow.collect { v -> _state.update { it.copy(filter = v) } } }
        viewModelScope.launch { focusFlow.collect { v -> _state.update { it.copy(focus = v) } } }
        viewModelScope.launch { filtersFlow.collect { v -> _state.update { it.copy(showFilters = v) } } }
        viewModelScope.launch { checkedFlow.collect { v -> _state.update { it.copy(checked = v) } } }
        viewModelScope.launch { settingsFlow.collect { v -> _state.update { it.copy(showSettings = v) } } }
        viewModelScope.launch { quickCollectFlow.collect { v -> _state.update { it.copy(quickCollect = v) } } }
        viewModelScope.launch { messageFlow.collect { v -> _state.update { it.copy(message = v) } } }
        viewModelScope.launch { cacheBytesFlow.collect { v -> _state.update { it.copy(cacheBytes = v) } } }
        viewModelScope.launch { imageBytesFlow.collect { v -> _state.update { it.copy(imageBytes = v) } } }
        viewModelScope.launch { prefetchFlow.collect { v -> _state.update { it.copy(prefetch = v) } } }
        viewModelScope.launch { storageFlow.collect { v -> _state.update { it.copy(storageReport = v) } } }
        viewModelScope.launch { graph.sync.running.collect { v -> _state.update { it.copy(syncing = v) } } }
        viewModelScope.launch { graph.sync.sweepError.collect { v -> _state.update { it.copy(sweepError = v) } } }
        viewModelScope.launch {
            graph.player.state.collect { player ->
                _state.update { it.copy(player = player) }
                onPlayerState(player)
            }
        }
        viewModelScope.launch { graph.settings.snapshot.collect { v -> _state.update { it.copy(settings = v) } } }
        viewModelScope.launch { graph.sync.queuedCount.collect { v -> _state.update { it.copy(queued = v) } } }
        viewModelScope.launch { graph.sync.failedCount.collect { v -> _state.update { it.copy(failed = v) } } }
        viewModelScope.launch {
            graph.favorites.cachedTotal.collect { v -> _state.update { it.copy(cachedTotal = v) } }
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

    /**
     * 标签本身变了（改名或从库里删掉）：受影响条目的标签快照要重新排队，否则后台停在旧标签名。
     */
    private suspend fun resync(favorites: List<Favorite>) {
        if (favorites.isEmpty()) return
        val restamped = graph.favorites.stampTagRewrite(favorites.map { it.id })
        restamped.forEach { graph.sync.queueUpsert(it) }
        messageFlow.value = "标签已更新，${restamped.size} 条收藏重新排队同步"
    }

    /**
     * 点卡片：把这条内容摊开放在遮罩层里。
     *
     * 落在上次看的那一段（老数据没有段号就第 0 段），因为后台给的 `media[]` 顺序就是阅读顺序，
     * 中途退出再进来不该从第一张重看。没解析出素材也照样打开 —— 遮罩层要能说清这条链接怎么了，
     * 并且把「重新准备」放在用户看得见的位置。
     */
    fun openFocus(id: String) {
        val favorite = _state.value.items.firstOrNull { it.id == id } ?: return
        cancelAheadPrefetch()
        filtersFlow.value = false
        // 内容还没就绪：用户点开的那一下就是最合理的询问时机，问一次就走，不做轮询
        if (favorite.contentPending) peekContent(id)
        val first = favorite.segments.firstOrNull()
        val focus = FocusUiState(id, 0, primaryRevision = first?.url.orEmpty())
        focusFlow.value = focus
        if (first == null) {
            graph.player.pausePlayback()
            return
        }
        loadFocus(favorite, focus, autoPlay = false)
    }

    /** 关掉遮罩层时把那次单条查询的失败提示一起收掉：它只属于刚才打开的那一条 */
    private fun clearContentRefreshError() = _state.update { it.copy(contentRefreshError = null) }

    /**
     * 关闭遮罩层：停下播放。
     *
     * 墙上没有播放器，遮罩层一关就没有任何界面能再控制这个实例了 —— 之前只解绑不暂停，
     * 用户听到的就是「关了还在放」。
     */
    fun closeFocus() {
        cancelAheadPrefetch()
        graph.player.pausePlayback()
        focusFlow.value = null
        clearContentRefreshError()
    }

    /**
     * 筛选与标签管理收进顶栏抽屉；遮罩层打开时顶栏收起，所以抽屉只从墙上打开。
     * 底部那行报的是两个缓存目录之和，打开时一起重新量：图片字节没有别的回调时机，冷启动也没量过。
     */
    fun showFilters(value: Boolean) {
        filtersFlow.value = value
        if (value) {
            viewModelScope.launch {
                cacheBytesFlow.value = graph.media.totalCachedBytes()
                imageBytesFlow.value = graph.images.cachedBytes()
            }
        }
    }

    /** 分段条点击、媒体区左右滑动都走这里；[FocusUiState.playerKey] 让每段各自记住这一会话里的位置 */
    fun showSegment(index: Int) {
        val focus = focusFlow.value ?: return
        val favorite = _state.value.items.firstOrNull { it.id == focus.itemId } ?: return
        // 同段不处理：pager 在 BACKUP 模式下回弹/轻拖也会回调这里，
        // 不能因为「页码没变」就把它重置回 PRIMARY。
        if (index !in favorite.segments.indices || index == focus.segmentIndex) return
        cancelAheadPrefetch()
        val segment = favorite.segments[index]
        val next = FocusUiState(focus.itemId, index, primaryRevision = segment.url)
        focusFlow.value = next
        // 在遮罩层里主动翻段 = 直接操作，视频段自动播起来；从墙上点开时才停在暂停态等用户按播放
        loadFocus(favorite, next, autoPlay = true)
    }

    /** C 层主源/备用切换；备用未 ready 时不允许切 */
    fun setSourceMode(mode: SourceMode) {
        val focus = focusFlow.value ?: return
        val favorite = _state.value.items.firstOrNull { it.id == focus.itemId } ?: return
        val segment = favorite.segments.getOrNull(focus.segmentIndex) ?: return
        if (mode == SourceMode.BACKUP && !segment.backup.ready) return
        if (focus.sourceMode == mode && (mode == SourceMode.PRIMARY || focus.backupIndex == 0)) return
        val next = if (mode == SourceMode.PRIMARY) {
            focus.copy(sourceMode = SourceMode.PRIMARY)
        } else {
            focus.copy(
                sourceMode = SourceMode.BACKUP,
                backupIndex = 0,
                backupRevision = segment.backup.items.firstOrNull()?.url.orEmpty(),
            )
        }
        cancelAheadPrefetch()
        focusFlow.value = next
        loadFocus(favorite, next, autoPlay = true)
    }

    fun setBackupIndex(index: Int) {
        val focus = focusFlow.value ?: return
        if (focus.sourceMode != SourceMode.BACKUP) return
        val favorite = _state.value.items.firstOrNull { it.id == focus.itemId } ?: return
        val segment = favorite.segments.getOrNull(focus.segmentIndex) ?: return
        if (index !in segment.backup.items.indices || index == focus.backupIndex) return
        val next = focus.copy(backupIndex = index, backupRevision = segment.backup.items[index].url)
        cancelAheadPrefetch()
        focusFlow.value = next
        loadFocus(favorite, next, autoPlay = true)
    }

    /** 一段自然播完：PRIMARY 进下一段；BACKUP 片内推进，最后一片停住。 */
    private fun advanceAfterEnd(playerKey: String) {
        val focus = focusFlow.value ?: return
        if (playerKey != focus.playerKey) return
        val favorite = _state.value.items.firstOrNull { it.id == focus.itemId } ?: return
        val segment = favorite.segments.getOrNull(focus.segmentIndex) ?: return

        if (focus.sourceMode == SourceMode.BACKUP) {
            val nextIndex = focus.backupIndex + 1
            val item = segment.backup.items.getOrNull(nextIndex) ?: return
            if (!item.playable) return
            val next = focus.copy(backupIndex = nextIndex, backupRevision = item.url)
            focusFlow.value = next
            loadFocus(favorite, next, autoPlay = true)
            return
        }

        val next = focus.segmentIndex + 1
        val nextSegment = favorite.segments.getOrNull(next) ?: return
        if (!nextSegment.playable) return
        val focusNext = FocusUiState(focus.itemId, next, primaryRevision = nextSegment.url)
        focusFlow.value = focusNext
        loadFocus(favorite, focusNext, autoPlay = true)
    }

    /**
     * 遮罩层竖向 pager 滑到落定的那条：换成它。
     *
     * [id] 为 null（页序里的这条已被删掉）或就是当前那条时什么都不做 —— pager 落定与
     * ViewModel 换焦点因此不会互相打转。
     */
    fun focusFromPager(id: String?) {
        if (id == null || id == focusFlow.value?.itemId) return
        openFocus(id)
    }

    /** 打开设置就把两个缓存数重新取一遍：在线边播边存的字节没有别的回调时机，等它自己刷新就是「要重启才对」 */
    fun showSettings(value: Boolean) {
        settingsFlow.value = value
        if (value) {
            viewModelScope.launch {
                cacheBytesFlow.value = graph.media.totalCachedBytes()
                imageBytesFlow.value = graph.images.cachedBytes()
            }
        }
    }

    fun consumeMessage() {
        messageFlow.value = null
    }
    // endregion

    // region 播放
    private fun loadFocus(
        favorite: Favorite,
        focus: FocusUiState,
        autoPlay: Boolean,
        resumeAtMs: Long = 0L,
    ) {
        val segment = favorite.segments.getOrNull(focus.segmentIndex) ?: return
        val item = when (focus.sourceMode) {
            SourceMode.PRIMARY -> PlaybackItem(
                url = segment.url,
                kind = segment.kind,
                title = segment.title,
                durationMs = segment.durationMs,
                quality = segment.displayQuality,
            )

            SourceMode.BACKUP -> {
                val backup = segment.backup.items.getOrNull(focus.backupIndex)
                if (backup == null) {
                    graph.player.pausePlayback()
                    return
                }
                PlaybackItem(backup.url, backup.kind, backup.title, backup.durationMs, backup.quality)
            }
        }
        val next = when (focus.sourceMode) {
            SourceMode.PRIMARY -> focus.copy(primaryRevision = item.url)
            SourceMode.BACKUP -> focus.copy(backupRevision = item.url)
        }
        if (next != focus) focusFlow.value = next
        if (item.kind == MediaKind.IMAGE || item.url.isBlank()) {
            graph.player.pausePlayback()
            cancelAheadPrefetch()
            return
        }
        graph.player.open(
            itemId = next.playerKey,
            url = item.url,
            resumeAtMs = resumeAtMs,
            speed = _state.value.settings?.defaultPlaybackSpeed ?: 1f,
            autoPlay = autoPlay,
        )
        prefetchAhead(favorite, next)
    }

    /** 滚动预取：PRIMARY 沿后续 A 段，BACKUP 沿后续备用项；每次换源/换段都重建窗口 */
    private fun prefetchAhead(favorite: Favorite, focus: FocusUiState) {
        cancelAheadPrefetch()
        if (prefetches.containsKey(favorite.id)) return
        val segment = favorite.segments.getOrNull(focus.segmentIndex) ?: return
        val urls = when (focus.sourceMode) {
            SourceMode.PRIMARY -> favorite.segments
                .drop(focus.segmentIndex + 1)
                .filter { it.playable && it.url.isNotBlank() && !isPlaylist(it.url) }
                .take(AUTO_PREFETCH_AHEAD)
                .map { it.url }

            SourceMode.BACKUP -> segment.backup.items
                .drop(focus.backupIndex + 1)
                .filter { it.playable && it.url.isNotBlank() && !isPlaylist(it.url) }
                .take(AUTO_PREFETCH_AHEAD)
                .map { it.url }
        }
        if (urls.isEmpty()) return
        val handle = PrefetchHandle()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                for (url in urls) {
                    if (handle.cancelled) break
                    if (!prefetchingUrls.add(url)) continue
                    try {
                        graph.media.prefetch(url, onProgress = {}, isCancelled = { handle.cancelled })
                    } finally {
                        prefetchingUrls.remove(url)
                    }
                }
            } finally {
                if (aheadPrefetch?.handle === handle) aheadPrefetch = null
            }
        }
        aheadPrefetch = AheadPrefetch(favorite.id, handle, job)
        job.start()
    }

    private fun cancelAheadPrefetch() {
        aheadPrefetch?.let { task ->
            task.handle.cancelled = true
            task.job.cancel()
        }
        aheadPrefetch = null
    }

    /**
     * WS/对账把收藏行换了之后，只处理会和当前播放器冲突的变化：
     * PRIMARY 换 URL 要重载；BACKUP 消失回主源，越界夹紧，当前项 URL 变了才重载。
     */
    private fun reconcileFocus(visible: List<Favorite>) {
        val focus = focusFlow.value ?: return
        val favorite = visible.firstOrNull { it.id == focus.itemId } ?: return
        val segment = favorite.segments.getOrNull(focus.segmentIndex) ?: return
        val playing = _state.value.player.isPlaying
        val position = _state.value.player.positionMs

        if (focus.sourceMode == SourceMode.BACKUP) {
            if (!segment.backup.ready) {
                val next = focus.copy(sourceMode = SourceMode.PRIMARY)
                focusFlow.value = next
                loadFocus(favorite, next, autoPlay = playing, resumeAtMs = position)
                return
            }
            val index = focus.backupIndex.coerceIn(0, segment.backup.items.lastIndex)
            val item = segment.backup.items.getOrNull(index) ?: return
            val next = focus.copy(backupIndex = index, backupRevision = item.url)
            if (next != focus) {
                focusFlow.value = next
                loadFocus(favorite, next, autoPlay = playing, resumeAtMs = position)
            }
            return
        }

        if (segment.url != focus.primaryRevision) {
            val next = focus.copy(primaryRevision = segment.url)
            focusFlow.value = next
            loadFocus(favorite, next, autoPlay = playing, resumeAtMs = position)
        }
    }

    /** 主源播放失败：backup ready 就直接切；否则只做一次 lookup，等后台换档/修复 */
    private fun onPlayerState(player: PlayerUiState) {
        val focus = focusFlow.value ?: return
        if (player.error == null || player.itemId != focus.playerKey || focus.sourceMode != SourceMode.PRIMARY) return
        val favorite = _state.value.items.firstOrNull { it.id == focus.itemId } ?: return
        val segment = favorite.segments.getOrNull(focus.segmentIndex) ?: return

        if (segment.backup.ready && autoFallbackKeys.add(focus.playerKey)) {
            val item = segment.backup.items.first()
            val next = focus.copy(
                sourceMode = SourceMode.BACKUP,
                backupIndex = 0,
                backupRevision = item.url,
            )
            focusFlow.value = next
            loadFocus(favorite, next, autoPlay = true)
            return
        }

        val lookupKey = "${focus.itemId}#${focus.segmentIndex}"
        if (failureLookups.add(lookupKey)) {
            viewModelScope.launch {
                when (val outcome = graph.control.peek(favorite.url)) {
                    is LookupOutcome.Loaded -> graph.favorites.importRemote(outcome.favorites)
                    is LookupOutcome.Failed -> Unit
                }
            }
        }
    }

    private suspend fun stopAheadPrefetch() {
        val task = aheadPrefetch ?: return
        aheadPrefetch = null
        task.handle.cancelled = true
        task.job.cancel()
        task.job.join()
    }

    fun togglePlay() {
        graph.player.togglePlay()
    }

    fun seekTo(positionMs: Long) = graph.player.seekTo(positionMs)

    fun setSpeed(speed: Float) {
        graph.player.setSpeed(speed)
        viewModelScope.launch { graph.settings.setDefaultPlaybackSpeed(speed) }
    }

    /** 遮罩层每约 500ms 调用一次，只刷新播放器状态；不记录播放进度 */
    fun tick() {
        graph.player.syncTick()
    }
    // endregion

    // region 后台读写
    /**
     * 遮罩层「重新准备」：让后台丢弃旧结果，按当前切片规则重新解析一次；备份随后再做。
     *
     * 后台立刻返回、把这条置回 `pending`，内容稍后再就绪 —— 所以这里只回一句「已重新排队」，
     * 不假装已经拿到内容。这是全站唯一主动重建内容的入口，必须由用户点。
     */
    fun resolveNow(id: String) {
        viewModelScope.launch {
            val favorite = graph.favorites.byId(id) ?: return@launch
            val result = graph.control.resolve(listOf(favorite.url))
            if (result.error != null) {
                messageFlow.value = "重新准备失败：${result.error}"
                return@launch
            }
            if (result.unknownUrls.isNotEmpty()) {
                messageFlow.value = "后台没有这条收藏，请先把它重新上传"
                return@launch
            }
            graph.favorites.importRemote(result.favorites)
            messageFlow.value = "已重新排队，内容准备好会自动出现"
        }
    }

    /**
     * 打开一条内容还没就绪的收藏时，单独问一次它的状态。
     *
     * 只在用户点开那条时发一次（不进任何定时器）：用户正盯着一条还在转存的大视频，
     * 他点开的那一下就是最合理的询问时机。失败时**不动库里任何东西**，
     * 只在界面上说一句「刷新失败」，库里那行继续停在原来的 pending/ready 状态。
     */
    fun peekContent(id: String) {
        viewModelScope.launch {
            val favorite = graph.favorites.byId(id) ?: return@launch
            // 还没传上去的条目后台根本没有，问也问不出来
            if (favorite.badge != SyncBadge.SYNCED) return@launch
            if (favorite.resolved || favorite.mediaState == MediaState.FAILED) return@launch
            when (val outcome = graph.control.peek(favorite.url)) {
                is LookupOutcome.Failed -> {
                    _state.update { it.copy(contentRefreshError = "刷新失败：${outcome.reason}") }
                }
                is LookupOutcome.Loaded -> {
                    graph.favorites.importRemote(outcome.favorites)
                    _state.update { it.copy(contentRefreshError = null) }
                }
            }
        }
    }

    /** 空状态、抽屉里那颗「从后台加载」：取下一页，并把结果一条一句说清楚 */
    fun pullFromServer() {
        loadRemotePage(announce = true)
    }

    /** 列表滚到底：静默补一页，离线或到底就不发 */
    fun loadMoreFromServer() {
        if (!_state.value.online) return
        loadRemotePage(announce = false)
    }

    private fun loadRemotePage(announce: Boolean) {
        if (remotePaging) return
        if (!announce && !_state.value.remoteHasMore) return
        remotePaging = true
        _state.update { it.copy(remoteLoading = true) }
        viewModelScope.launch {
            try {
                fetchRemotePage(announce)
            } finally {
                remotePaging = false
                _state.update { it.copy(remoteLoading = false) }
            }
        }
    }

    /**
     * 取后台的一页收藏落进本地缓存 —— 服务端是事实来源，本地这张表只缓存滚到过的那几页，
     * 所以离线能看的也就是这些，滚到底就再补一页。每条收藏自带解析结果，取回即完整。
     *
     * 整段兜异常：这条链原本没有隔离，后台多一条脏数据一崩就是 App 直接退出。
     * 取消异常照常往上抛，别把它当失败弹消息。
     * [announce] 为假是自动补页，除了界面自己的加载位，什么都不该弹。
     */
    private suspend fun fetchRemotePage(announce: Boolean) {
        val outcome = try {
            graph.control.pull(remoteCursor, PAGE_SIZE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (broken: Exception) {
            if (announce) messageFlow.value = "从后台加载失败：${broken.message ?: "后台响应异常"}"
            return
        }
        when (outcome) {
            is PullOutcome.Loaded -> {
                val report = try {
                    graph.favorites.importRemote(outcome.favorites)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (broken: Exception) {
                    if (announce) messageFlow.value = "从后台加载失败：${broken.message ?: "本地写入异常"}"
                    return
                }
                remoteCursor = outcome.nextCursor.ifBlank { null }
                // 后台说还有、却一条没发，那就是它自己数错了，别再往下滚着要了
                val more = outcome.hasMore && outcome.favorites.isNotEmpty()
                // 总数每页都一样，取到就覆盖；老后台不下发（0）时保持原值，界面退回只说本地条数
                _state.update {
                    it.copy(remoteHasMore = more, remoteTotal = if (outcome.total > 0) outcome.total else it.remoteTotal)
                }
                if (!announce) return
                messageFlow.value = buildString {
                    append("后台这一页 ${outcome.favorites.size} 条")
                    if (outcome.total > 0) append("（后台共 ${outcome.total} 条）")
                    append("，新入库 ${report.imported} 条")
                    if (report.refreshed > 0) append("，刷新 ${report.refreshed} 条")
                    if (report.skipped > 0) append("，${report.skipped} 条链接为空已跳过")
                    if (report.broken > 0) append("，${report.broken} 条数据异常已跳过")
                    append(if (more) "；往下滚还有下一页" else "；已经是最后一页")
                }
            }

            is PullOutcome.Failed -> if (announce) messageFlow.value = "从后台加载失败：${outcome.reason}"
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
            val targets = ids.mapNotNull { graph.favorites.byId(it) }
            graph.favorites.markDeleted(ids)
            graph.sync.queueDelete(targets)
            checkedFlow.value = emptySet()
            closeFocus()
            messageFlow.value = "已删除 ${ids.size} 条，正在排队上传"
            reclaim(ids)
        }
    }

    fun deleteOne(id: String) {
        viewModelScope.launch {
            val target = graph.favorites.byId(id)
            graph.favorites.markDeleted(listOf(id))
            target?.let { graph.sync.queueDelete(listOf(it)) }
            closeFocus()
            messageFlow.value = "已删除，正在排队上传"
            reclaim(listOf(id))
        }
    }

    /**
     * 删收藏顺手把它在本地留下的字节要回来：图片按 URL 精确删 Coil 那一份，视频段删
     * SimpleCache 里那一份。软删的行按 id 照样读得到段信息，所以放在 markDeleted 之后调也来得及；
     * 回收失败只影响字节，不能把删除本身挡下。
     */
    private suspend fun reclaim(ids: List<String>) {
        runCatching {
            val favorites = ids.mapNotNull { graph.favorites.byId(it) }
            val images = favorites.flatMap { f ->
                f.segments.flatMap { s ->
                    listOfNotNull(
                        s.url.takeIf { s.kind == MediaKind.IMAGE && it.isNotBlank() },
                        s.coverUrl.takeIf { it.isNotBlank() },
                    )
                }
            }
            val videos = favorites.flatMap { f ->
                f.segments.filter { it.kind == MediaKind.VIDEO }.map { it.url }
            }
            graph.images.evict(images)
            videos.forEach { graph.media.evict(it) }
            cacheBytesFlow.value = graph.media.totalCachedBytes()
            imageBytesFlow.value = graph.images.cachedBytes()
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

    /**
     * 顶栏唯一的同步入口：**把失败的请求重新排队并立刻继续上传**。
     *
     * 这里只做上传这一件事，不兼任「取解析结果」：后台传回收藏时就把它解析出来的内容一起
     * 带回来了，没有第二件事可等。四种情况都必须回一句话，不许无声。
     */
    fun retrySyncNow() {
        val current = _state.value
        if (current.syncing) {
            messageFlow.value = "正在等后台响应，传完这条会自动继续下一条"
            return
        }
        when {
            !current.online -> messageFlow.value =
                if (current.queued > 0) "当前离线，队列已保留，联网后自动继续上传" else "当前离线，没有待同步的条目"

            current.failed > 0 -> {
                graph.sync.retryFailed()
                messageFlow.value = "已把 ${current.failed} 条失败的重新排队，按上传节奏继续"
            }

            current.queued > 0 -> {
                graph.sync.flush()
                messageFlow.value = "队列里还有 ${current.queued} 条，已推动继续上传"
            }

            else -> messageFlow.value = "没有需要重试的条目，本地与后台一致"
        }
    }

    /**
     * 手动把某条视频缓存到本地，离线也能播。
     *
     * 长视频由后台切成一组独立 mp4，所以「整片缓存」就是逐片预取：
     * 全部片段都读到才算缓存完成，中途取消或某片失败都不标「已缓存」。
     * 旧后台留下的 `.m3u8` 直接拒绝 —— 整片预取要一套播放列表解析，换来的只是省一次边播边存。
     */
    fun prefetch(id: String) {
        val favorite = _state.value.items.firstOrNull { it.id == id } ?: return
        val urls = favorite.segments.filter { it.playable }.map { it.url }.filter { it.isNotBlank() }
        if (urls.isEmpty() || prefetches.containsKey(id)) return
        if (urls.any { isPlaylist(it) }) {
            messageFlow.value = "这条是切片视频，播放时会边看边存，不支持整片缓存"
            return
        }
        val handle = PrefetchHandle()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // 手动整片缓存优先：先停掉滚动窗口，避免同一 URL 被两条链路同时拉
            try {
                stopAheadPrefetch()
                var total = 0L
                var complete = true
                for (url in urls) {
                    if (handle.cancelled) {
                        complete = false
                        break
                    }
                    if (!prefetchingUrls.add(url)) continue
                    val base = total
                    try {
                        val written = graph.media.prefetch(
                            url = url,
                            onProgress = { cached -> prefetchFlow.update { it + (id to base + cached) } },
                            isCancelled = { handle.cancelled },
                        )
                        if (written <= 0) {
                            complete = false
                            break
                        }
                        total += written
                        prefetchFlow.update { it + (id to total) }
                    } finally {
                        prefetchingUrls.remove(url)
                    }
                }
                if (complete && !handle.cancelled) {
                    graph.favorites.setLocallyCached(id, true)
                    messageFlow.value = "已缓存到本地，离线可直接播放"
                }
            } finally {
                prefetches.remove(id)
                prefetchFlow.update { it - id }
                cacheBytesFlow.value = graph.media.totalCachedBytes()
            }
        }
        prefetches[id] = handle to job
        job.start()
    }

    /** 切片清单（HLS）当资源读只会读到几 KB 的播放列表，不能算「已缓存」 */
    private fun isPlaylist(url: String): Boolean =
        url.substringBefore('?').lowercase().endsWith(".m3u8")

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
            // 跟着把「已缓存」一起抹掉：卡片状态点和遮罩层那句提示读的就是这一列，
            // 只删字节不动标记，清完缓存界面还在说这条能离线播
            graph.favorites.clearLocallyCached()
            cacheBytesFlow.value = 0L
            messageFlow.value = "本地视频缓存已清空（收藏记录不受影响）"
        }
    }

    /** 图片缓存不碰播放器，Coil 自己按目录清；旧版本遗留的孤儿图只有这里能清掉 */
    fun clearImageCache() {
        viewModelScope.launch {
            graph.images.clear()
            imageBytesFlow.value = 0L
            messageFlow.value = "图片缓存已清空（收藏记录不受影响）"
        }
    }

    /** 只读量一遍私有目录，结果原样铺在设置页里，用来对着系统「存储」那页核数 */
    fun measureStorage() {
        storageFlow.value = "测量中…"
        viewModelScope.launch { storageFlow.value = graph.storage.report() }
    }
    // endregion

    // region 设置
    fun setSyncEndpoint(value: String) = viewModelScope.launch { graph.settings.setSyncEndpoint(value) }

    /** 换令牌只换后台身份：队列里的 op 按 url 落库，推过去最多把这几条也建到新后台，无害，所以不清队列 */
    fun setSyncToken(value: String) = viewModelScope.launch {
        val previous = graph.settings.current().syncToken
        graph.settings.setSyncToken(value)
        if (previous != value.trim()) graph.favorites.settlePendingBadges()
    }
    fun setAssistantEnabled(value: Boolean) = viewModelScope.launch { graph.settings.setAssistantEnabled(value) }
    fun setPasteSuggestion(value: Boolean) = viewModelScope.launch { graph.settings.setPasteSuggestion(value) }
    fun setBallStatusBadge(value: Boolean) = viewModelScope.launch { graph.settings.setBallStatusBadge(value) }
    fun setUploadIntervalMs(value: Long) = viewModelScope.launch { graph.settings.setUploadIntervalMs(value) }
    fun setSliceRuleSizeMb(value: Long) =
        viewModelScope.launch { graph.settings.setSliceRuleBytes(value * 1_000_000L) }
    fun setCacheLimitMb(value: Long) = viewModelScope.launch {
        graph.settings.setCacheLimitMb(value)
        // 改上限会重建 SimpleCache 实例，先释放播放器才不会打断正在读缓存的播放
        graph.player.release()
        graph.media.applyLimitFromSettings()
    }

    /** 图片缓存改上限会换掉整个 Coil loader，正在屏上的图会重新解码一次；不动播放器 */
    fun setImageCacheLimitMb(value: Long) = viewModelScope.launch {
        graph.settings.setImageCacheLimitMb(value)
        graph.images.applyLimitFromSettings()
        imageBytesFlow.value = graph.images.cachedBytes()
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
        cancelAheadPrefetch()
        prefetches.values.forEach { (handle, job) ->
            handle.cancelled = true
            job.cancel()
        }
        graph.player.release()
        super.onCleared()
    }

    companion object {
        /** 一页收藏的条数：够铺两三屏，又不至于一次把几年的收藏全拽下来 */
        private const val PAGE_SIZE = 50

        /** 自动预取的滚动窗口：只提前 3 片，避免长视频把缓存挤空 */
        private const val AUTO_PREFETCH_AHEAD = 3

        fun factory(graph: AppGraph) = viewModelFactory {
            initializer { MainViewModel(graph) }
        }
    }
}
