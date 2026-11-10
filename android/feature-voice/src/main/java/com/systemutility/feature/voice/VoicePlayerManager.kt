package com.systemutility.feature.voice

import android.media.MediaPlayer
import android.media.PlaybackParams
import com.systemutility.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class VoicePlaybackState(
    val activeAttachmentId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f
)

@Singleton
class VoicePlayerManager @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var tickerJob: Job? = null

    fun playOrToggle(attachmentId: String, filePath: String) {
        val current = _state.value
        if (current.activeAttachmentId == attachmentId && player != null) {
            if (current.isPlaying) {
                runCatching { player?.pause() }
                tickerJob?.cancel()
                _state.value = current.copy(isPlaying = false)
            } else {
                applyPlaybackSpeed(current.speed)
                runCatching { player?.start() }
                startTicker()
                _state.value = current.copy(isPlaying = true)
            }
            return
        }

        stop()

        val mp = MediaPlayer()
        runCatching {
            mp.setDataSource(filePath)
            mp.prepare()
            val speed = _state.value.speed
            runCatching {
                mp.playbackParams = PlaybackParams().setSpeed(speed)
            }
            mp.setOnCompletionListener {
                tickerJob?.cancel()
                _state.value = _state.value.copy(
                    isPlaying = false,
                    positionMs = 0L
                )
            }
            mp.start()
            player = mp
            _state.value = VoicePlaybackState(
                activeAttachmentId = attachmentId,
                isPlaying = true,
                positionMs = 0L,
                durationMs = mp.duration.toLong().coerceAtLeast(1L),
                speed = speed
            )
            startTicker()
        }.onFailure {
            runCatching { mp.release() }
        }
    }

    fun seekToFraction(attachmentId: String, fraction: Float) {
        if (_state.value.activeAttachmentId != attachmentId) return
        val mp = player ?: return
        val target = (mp.duration * fraction.coerceIn(0f, 1f)).toInt()
        runCatching { mp.seekTo(target) }
        _state.value = _state.value.copy(positionMs = target.toLong())
    }

    fun cyclePlaybackSpeed(): Float {
        val next = when (_state.value.speed) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        applyPlaybackSpeed(next)
        _state.value = _state.value.copy(speed = next)
        return next
    }

    private fun applyPlaybackSpeed(speed: Float) {
        val mp = player ?: return
        runCatching {
            mp.playbackParams = PlaybackParams().setSpeed(speed)
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = appScope.launch {
            while (isActive && player != null && _state.value.isPlaying) {
                val pos = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
                _state.value = _state.value.copy(positionMs = pos)
                delay(80L)
            }
        }
    }

    fun stop() {
        tickerJob?.cancel()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _state.value = VoicePlaybackState(speed = _state.value.speed)
    }
}
