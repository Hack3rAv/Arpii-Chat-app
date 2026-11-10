package com.systemutility.feature.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.systemutility.core.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class RecordedVoiceNote(
    val file: File,
    val durationMs: Long,
    val waveformCsv: String
)

data class VoiceRecordingState(
    val isRecording: Boolean = false,
    val isLocked: Boolean = false,
    val elapsedMs: Long = 0L,
    val liveBars: List<Int> = emptyList()
)

@Singleton
class VoiceRecorderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _state = MutableStateFlow(VoiceRecordingState())
    val state: StateFlow<VoiceRecordingState> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L
    private var meterJob: Job? = null
    private val rawSamples = mutableListOf<Int>()

    fun startRecording(locked: Boolean = false): Boolean {
        if (_state.value.isRecording) return true

        val dir = File(context.filesDir, "voice_staging").apply { mkdirs() }
        val file = File(dir, "vn_${System.currentTimeMillis()}.m4a")
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(64_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()

            recorder = mr
            outputFile = file
            startedAtMs = System.currentTimeMillis()
            rawSamples.clear()
            _state.value = VoiceRecordingState(isRecording = true, isLocked = locked, elapsedMs = 0L, liveBars = emptyList())

            meterJob?.cancel()
            meterJob = appScope.launch {
                while (isActive && _state.value.isRecording) {
                    delay(70L)
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val normalized = ((amp.coerceAtMost(24_000) / 24_000f) * 100f).roundToInt().coerceIn(8, 100)
                    rawSamples.add(normalized)
                    _state.value = _state.value.copy(
                        elapsedMs = System.currentTimeMillis() - startedAtMs,
                        liveBars = rawSamples.takeLast(32)
                    )
                }
            }
            true
        } catch (_: Throwable) {
            runCatching { mr.release() }
            file.delete()
            false
        }
    }

    fun lockRecording() {
        if (_state.value.isRecording) {
            _state.value = _state.value.copy(isLocked = true)
        }
    }

    fun stopAndFinalize(): RecordedVoiceNote? {
        if (!_state.value.isRecording) return null
        meterJob?.cancel()
        val duration = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(300L)
        val file = outputFile
        val activeRecorder = recorder
        recorder = null
        outputFile = null
        _state.value = VoiceRecordingState()

        runCatching {
            activeRecorder?.stop()
        }
        runCatching {
            activeRecorder?.release()
        }

        if (file == null || !file.exists() || file.length() < 128L || duration < 400L) {
            file?.delete()
            return null
        }

        val normalizedBars = downsampleToBars(rawSamples, targetBars = 32)
        return RecordedVoiceNote(
            file = file,
            durationMs = duration,
            waveformCsv = normalizedBars.joinToString(",")
        )
    }

    fun cancelRecording() {
        meterJob?.cancel()
        val file = outputFile
        val activeRecorder = recorder
        recorder = null
        outputFile = null
        _state.value = VoiceRecordingState()

        runCatching { activeRecorder?.stop() }
        runCatching { activeRecorder?.release() }
        file?.delete()
    }

    private fun downsampleToBars(samples: List<Int>, targetBars: Int): List<Int> {
        if (samples.isEmpty()) return List(targetBars) { 25 }
        if (samples.size <= targetBars) return samples
        val step = samples.size.toFloat() / targetBars
        return List(targetBars) { i ->
            val start = (i * step).toInt().coerceIn(0, samples.lastIndex)
            val end = (((i + 1) * step).toInt()).coerceIn(start + 1, samples.size)
            samples.subList(start, end).maxOrNull() ?: 25
        }
    }
}
