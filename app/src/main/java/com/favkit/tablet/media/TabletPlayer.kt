package com.favkit.tablet.media

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerUiState(
    val itemId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val error: String? = null,
)

/**
 * 全局唯一播放实例。
 *
 * 悬浮面板/列表切换条目时复用同一个 ExoPlayer（而不是每次新建），
 * 既避免解码器重复分配内存，也让全屏切换、后台短暂失焦不中断播放，
 * 是"常驻内存 <= 80MB"指标的关键约束之一。
 */
class TabletPlayer(private val cache: MediaCache) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var exoPlayer: ExoPlayer? = null

    val player: ExoPlayer? get() = exoPlayer

    fun ensure(): ExoPlayer = exoPlayer ?: cache.buildPlayer().also { created ->
        created.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.value = _state.value.copy(isPlaying = isPlaying)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    _state.value = _state.value.copy(
                        isBuffering = playbackState == Player.STATE_BUFFERING,
                    )
                    if (playbackState == Player.STATE_READY) {
                        _state.value = _state.value.copy(
                            durationMs = created.duration.coerceAtLeast(0L),
                            error = null,
                        )
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        error = "无法播放该链接：${error.errorCodeName}",
                    )
                }
            },
        )
        exoPlayer = created
    }

    fun open(itemId: String, url: String, resumeAtMs: Long, speed: Float, autoPlay: Boolean = true) {
        val created = ensure()
        if (_state.value.itemId == itemId && !created.isPlaying) {
            created.seekTo(resumeAtMs)
            _state.value = _state.value.copy(positionMs = resumeAtMs)
            created.playWhenReady = autoPlay
            return
        }
        _state.value = PlayerUiState(itemId = itemId, speed = speed)
        created.playWhenReady = autoPlay
        created.playbackParameters = PlaybackParameters(speed)
        created.setMediaItem(MediaItem.fromUri(url))
        created.prepare()
        if (resumeAtMs > 0) created.seekTo(resumeAtMs)
    }

    fun togglePlay() {
        val created = ensure()
        if (created.isPlaying) created.pause() else {
            if (created.playbackState == Player.STATE_IDLE) created.prepare()
            created.playWhenReady = true
        }
    }

    /** 翻到图集段时让视频安静下来：不释放实例，已缓冲的还在，翻回来看下一段还是秒开 */
    fun pausePlayback() {
        val created = exoPlayer ?: return
        if (created.isPlaying) created.pause()
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    fun seekTo(positionMs: Long) {
        _state.value = _state.value.copy(positionMs = positionMs)
        exoPlayer?.seekTo(positionMs)
    }

    /** 播放进度回写：列表与详情据此显示"已看到 X%"，离线续播也依赖它 */
    fun snapshotProgress(): Pair<Long, Long> {
        val created = exoPlayer ?: return _state.value.positionMs to _state.value.durationMs
        return created.currentPosition.coerceAtLeast(0L) to created.duration.coerceAtLeast(0L)
    }

    fun syncTick() {
        val created = exoPlayer ?: return
        _state.value = _state.value.copy(
            positionMs = created.currentPosition.coerceAtLeast(0L),
            durationMs = created.duration.coerceAtLeast(0L),
        )
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        _state.value = PlayerUiState()
    }
}
