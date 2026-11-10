package com.systemutility.feature.calls

import android.content.Context
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import com.systemutility.core.database.dao.CallDao
import com.systemutility.core.database.entity.CallRecordingEntity
import com.systemutility.core.di.ApplicationScope
import com.systemutility.core.di.IoDispatcher
import com.systemutility.core.logging.SafeLogger
import com.systemutility.core.security.AndroidKeystoreManager
import com.systemutility.core.security.StreamCipherEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strictly local, explicit, user-triggered call recorder.
 * Never starts automatically, never controlled remotely, always accompanied by an unmistakable
 * ongoing foreground notification and visual REC HUD indicator.
 *
 * Encodes call audio (AAC-LC) and optionally video (H.264) into an app-private staging file,
 * then immediately stream-encrypts it with the device AndroidKeystore-protected vault key
 * via StreamCipherEngine into filesDir/recordings/.
 */
@Singleton
class CallRecordingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callDao: CallDao,
    private val keystoreManager: AndroidKeystoreManager,
    private val cipherEngine: StreamCipherEngine,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
    private val stagingDir = File(context.cacheDir, "rec_staging").apply { mkdirs() }
    private val playbackDir = File(context.cacheDir, "rec_playback").apply { mkdirs() }

    private val _state = MutableStateFlow(CallRecordingState())
    val state: StateFlow<CallRecordingState> = _state.asStateFlow()

    private val stateLock = Any()
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var timerJob: Job? = null

    private var activeCallId: String? = null
    private var activeKind: String = "audio"
    private var activeTempFile: File? = null

    // Timing & Pause tracking
    private var startedEpochMs = 0L
    private var activeDurationMs = 0L
    private var lastResumedEpochMs = 0L

    // Audio PCM mixing buffer for incoming remote AudioTrack frames
    private val remoteRingLock = Any()
    private val remoteRingBuffer = ShortArray(48_000 * 2) // ~2s capacity
    private var remoteReadIdx = 0
    private var remoteWriteIdx = 0
    private var remoteAvailable = 0

    // Encoders & Muxer (owned exclusively by workerThread)
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var audioEncoder: MediaCodec? = null
    private var videoEncoder: MediaCodec? = null
    private var audioFormat: MediaFormat? = null
    private var videoFormat: MediaFormat? = null
    private var audioTrackIndex = -1
    private var videoTrackIndex = -1
    private var recordVideoTrack = false
    private var configuredSampleRate = 48_000

    private val pendingAudioPackets = ArrayList<EncodedPacket>()
    private val pendingVideoPackets = ArrayList<EncodedPacket>()

    // Video surface rendering (owned exclusively by workerThread)
    private var encoderEgl: EglBase? = null
    private var frameDrawer: VideoFrameDrawer? = null
    private var rectDrawer: GlRectDrawer? = null
    private var lastPtsUs = 0L

    private data class EncodedPacket(
        val trackIsVideo: Boolean,
        val data: ByteArray,
        val info: MediaCodec.BufferInfo
    )

    fun startManualRecording(
        callId: String,
        includeVideo: Boolean,
        sharedEglContext: EglBase.Context?
    ): Boolean {
        synchronized(stateLock) {
            if (_state.value.status == CallRecordingStatus.RECORDING ||
                _state.value.status == CallRecordingStatus.PAUSED
            ) {
                return false
            }

            val recId = UUID.randomUUID().toString()
            val wantVideo = includeVideo && sharedEglContext != null
            val ext = if (wantVideo) "mp4" else "m4a"
            val stagingFile = File(stagingDir, "rec_${recId}.$ext")

            val thread = HandlerThread("CallRecordingWorker").apply { start() }
            val handler = Handler(thread.looper)

            workerThread = thread
            workerHandler = handler
            activeCallId = callId
            activeKind = if (wantVideo) "video" else "audio"
            activeTempFile = stagingFile
            startedEpochMs = System.currentTimeMillis()
            activeDurationMs = 0L
            lastResumedEpochMs = startedEpochMs
            lastPtsUs = 0L
            synchronized(remoteRingLock) {
                remoteReadIdx = 0
                remoteWriteIdx = 0
                remoteAvailable = 0
            }

            val latch = CountDownLatch(1)
            var initOk = false
            handler.post {
                initOk = initEncodersOnWorker(stagingFile, wantVideo, sharedEglContext)
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)

            if (!initOk) {
                cleanupOnWorkerSync()
                stagingFile.delete()
                _state.value = CallRecordingState(
                    status = CallRecordingStatus.ERROR,
                    errorMessage = "Device media encoder unavailable."
                )
                return false
            }

            _state.value = CallRecordingState(
                status = CallRecordingStatus.RECORDING,
                callId = callId,
                kind = activeKind,
                elapsedMs = 0L
            )
            startElapsedTicker()
            return true
        }
    }

    fun pauseRecording() {
        synchronized(stateLock) {
            if (_state.value.status != CallRecordingStatus.RECORDING) return
            val now = System.currentTimeMillis()
            activeDurationMs += (now - lastResumedEpochMs).coerceAtLeast(0L)
            _state.value = _state.value.copy(
                status = CallRecordingStatus.PAUSED,
                elapsedMs = activeDurationMs
            )
        }
    }

    fun resumeRecording() {
        synchronized(stateLock) {
            if (_state.value.status != CallRecordingStatus.PAUSED) return
            lastResumedEpochMs = System.currentTimeMillis()
            _state.value = _state.value.copy(status = CallRecordingStatus.RECORDING)
        }
    }

    fun stopAndSaveRecording(onCompleted: (Boolean) -> Unit = {}) {
        val currentStatus = _state.value.status
        if (currentStatus != CallRecordingStatus.RECORDING && currentStatus != CallRecordingStatus.PAUSED) {
            onCompleted(false)
            return
        }

        val callId = activeCallId ?: return
        val kind = activeKind
        val tempFile = activeTempFile ?: return
        val startedAt = startedEpochMs
        val duration = synchronized(stateLock) {
            val extra = if (currentStatus == CallRecordingStatus.RECORDING) {
                (System.currentTimeMillis() - lastResumedEpochMs).coerceAtLeast(0L)
            } else 0L
            (activeDurationMs + extra).coerceAtLeast(500L)
        }

        timerJob?.cancel()
        _state.value = _state.value.copy(status = CallRecordingStatus.FINALIZING)

        val handler = workerHandler
        appScope.launch(ioDispatcher) {
            var success = false
            try {
                if (handler != null) {
                    val latch = CountDownLatch(1)
                    handler.post {
                        finishEncodersOnWorker()
                        latch.countDown()
                    }
                    latch.await(4, TimeUnit.SECONDS)
                }

                if (tempFile.exists() && tempFile.length() > 256L) {
                    val recId = UUID.randomUUID().toString()
                    val encOut = File(recordingsDir, "rec_${recId}.enc")
                    val vaultKey = keystoreManager.getOrCreateVaultKey()
                    val encRes = FileInputStream(tempFile).use { input ->
                        FileOutputStream(encOut).use { output ->
                            cipherEngine.encryptStream(input, output, vaultKey)
                        }
                    }

                    callDao.insertRecording(
                        CallRecordingEntity(
                            id = recId,
                            callId = callId,
                            recordingKind = kind,
                            encryptedFilePath = encOut.absolutePath,
                            sha256Hex = encRes.sha256CiphertextHex,
                            durationMs = duration,
                            sizeBytes = encOut.length(),
                            startedAt = startedAt
                        )
                    )
                    success = true
                }
            } catch (t: Throwable) {
                SafeLogger.e("CallRecording", "Failed finalizing call recording: ${t.message}", t)
            } finally {
                tempFile.delete()
                cleanupThread()
                _state.value = CallRecordingState(status = CallRecordingStatus.IDLE)
                onCompleted(success)
            }
        }
    }

    fun abortIfRunning() {
        val status = _state.value.status
        if (status == CallRecordingStatus.RECORDING || status == CallRecordingStatus.PAUSED) {
            stopAndSaveRecording()
        }
    }

    /**
     * Fed by WebRtcCallManager from JavaAudioDeviceModule SamplesReadyCallback.
     */
    fun onLocalPcmFrame(pcmBytes: ByteArray, sampleRate: Int, channelCount: Int) {
        if (_state.value.status != CallRecordingStatus.RECORDING) return
        val handler = workerHandler ?: return

        val copy = pcmBytes.copyOf()
        val ptsUs = nextMonotonicPtsUs()
        handler.post {
            if (_state.value.status != CallRecordingStatus.RECORDING) return@post
            val monoBytes = mixWithRemotePcm(copy, channelCount)
            feedAudioEncoderOnWorker(monoBytes, sampleRate, ptsUs)
        }
    }

    /**
     * Fed by remote WebRTC AudioTrackSink (if device ROM supplies decoded remote PCM frames).
     */
    fun onRemotePcmFrame(data: ByteBuffer, bitsPerSample: Int, sampleRate: Int, channels: Int) {
        if (_state.value.status != CallRecordingStatus.RECORDING) return
        if (bitsPerSample != 16 || channels <= 0) return

        val dup = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val totalShorts = dup.remaining() / 2
        if (totalShorts <= 0) return

        synchronized(remoteRingLock) {
            var i = 0
            while (i + channels <= totalShorts) {
                var sum = 0
                for (c in 0 until channels) {
                    sum += dup.short.toInt()
                }
                val monoSample = (sum / channels).toShort()
                remoteRingBuffer[remoteWriteIdx] = monoSample
                remoteWriteIdx = (remoteWriteIdx + 1) % remoteRingBuffer.size
                if (remoteAvailable < remoteRingBuffer.size) {
                    remoteAvailable++
                } else {
                    remoteReadIdx = (remoteReadIdx + 1) % remoteRingBuffer.size
                }
                i += channels
            }
        }
    }

    /**
     * Fed by active VideoTrack sink during manual video call recording.
     */
    fun onVideoFrame(frame: VideoFrame) {
        if (_state.value.status != CallRecordingStatus.RECORDING || !recordVideoTrack) return
        val handler = workerHandler ?: return

        frame.retain()
        val ptsUs = nextMonotonicPtsUs()
        val posted = handler.post {
            try {
                if (_state.value.status == CallRecordingStatus.RECORDING) {
                    drawVideoFrameOnWorker(frame, ptsUs)
                }
            } finally {
                frame.release()
            }
        }
        if (!posted) {
            frame.release()
        }
    }

    suspend fun decryptRecordingToTemp(recording: CallRecordingEntity): File? {
        val encFile = File(recording.encryptedFilePath)
        if (!encFile.exists()) return null

        val ext = if (recording.recordingKind == "video") "mp4" else "m4a"
        val out = File(playbackDir, "${recording.id}.$ext")
        if (out.exists() && out.length() > 0L) return out

        return kotlinx.coroutines.withContext(ioDispatcher) {
            runCatching {
                val vaultKey = keystoreManager.getOrCreateVaultKey()
                FileInputStream(encFile).use { input ->
                    FileOutputStream(out).use { output ->
                        cipherEngine.decryptStream(input, output, vaultKey)
                    }
                }
                out
            }.getOrElse {
                out.delete()
                null
            }
        }
    }

    fun clearPlaybackTemp() {
        playbackDir.listFiles()?.forEach { it.delete() }
    }

    suspend fun deleteRecording(recording: CallRecordingEntity) {
        kotlinx.coroutines.withContext(ioDispatcher) {
            runCatching { File(recording.encryptedFilePath).delete() }
            runCatching { File(playbackDir, "${recording.id}.mp4").delete() }
            runCatching { File(playbackDir, "${recording.id}.m4a").delete() }
            callDao.deleteRecording(recording.id)
        }
    }

    private fun startElapsedTicker() {
        timerJob?.cancel()
        timerJob = appScope.launch {
            while (isActive) {
                delay(250L)
                val st = _state.value
                if (st.status == CallRecordingStatus.RECORDING) {
                    val elapsed = activeDurationMs + (System.currentTimeMillis() - lastResumedEpochMs).coerceAtLeast(0L)
                    _state.value = st.copy(elapsedMs = elapsed)
                } else if (st.status == CallRecordingStatus.IDLE || st.status == CallRecordingStatus.FINALIZING) {
                    break
                }
            }
        }
    }

    @Synchronized
    private fun nextMonotonicPtsUs(): Long {
        val elapsedMs = activeDurationMs + (System.currentTimeMillis() - lastResumedEpochMs).coerceAtLeast(0L)
        val candidate = elapsedMs * 1000L
        lastPtsUs = if (candidate > lastPtsUs) candidate else lastPtsUs + 1000L
        return lastPtsUs
    }

    private fun mixWithRemotePcm(localBytes: ByteArray, channels: Int): ByteArray {
        val ch = channels.coerceAtLeast(1)
        val inBuf = ByteBuffer.wrap(localBytes).order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = localBytes.size / 2 / ch
        val out = ByteArray(sampleCount * 2)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        synchronized(remoteRingLock) {
            for (i in 0 until sampleCount) {
                var sumLocal = 0
                for (c in 0 until ch) {
                    if (inBuf.remaining() >= 2) {
                        sumLocal += inBuf.short.toInt()
                    }
                }
                val localMono = sumLocal / ch
                val remoteSample = if (remoteAvailable > 0) {
                    val s = remoteRingBuffer[remoteReadIdx].toInt()
                    remoteReadIdx = (remoteReadIdx + 1) % remoteRingBuffer.size
                    remoteAvailable--
                    s
                } else 0

                val mixed = (localMono + remoteSample).coerceIn(-32768, 32767)
                outBuf.putShort(mixed.toShort())
            }
        }
        return out
    }

    private fun initEncodersOnWorker(
        outputFile: File,
        wantVideo: Boolean,
        sharedEgl: EglBase.Context?
    ): Boolean {
        return try {
            muxerStarted = false
            audioFormat = null
            videoFormat = null
            audioTrackIndex = -1
            videoTrackIndex = -1
            pendingAudioPackets.clear()
            pendingVideoPackets.clear()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Configure AAC audio encoder
            configuredSampleRate = 48_000
            val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, configuredSampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aCodec.start()
            audioEncoder = aCodec

            recordVideoTrack = false
            if (wantVideo && sharedEgl != null) {
                try {
                    val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_W, VIDEO_H).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, 1_400_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 24)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    }
                    val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val inputSurface = vCodec.createInputSurface()
                    vCodec.start()

                    val egl = EglBase.create(sharedEgl, EglBase.CONFIG_RECORDABLE)
                    egl.createSurface(inputSurface)
                    egl.makeCurrent()

                    encoderEgl = egl
                    frameDrawer = VideoFrameDrawer()
                    rectDrawer = GlRectDrawer()
                    videoEncoder = vCodec
                    recordVideoTrack = true
                } catch (videoErr: Throwable) {
                    SafeLogger.w("CallRecording", "Video encoder unavailable, falling back to audio recording: ${videoErr.message}")
                    videoEncoder?.runCatching { release() }
                    videoEncoder = null
                    recordVideoTrack = false
                }
            }
            true
        } catch (t: Throwable) {
            SafeLogger.e("CallRecording", "Encoder init failed: ${t.message}", t)
            false
        }
    }

    private fun feedAudioEncoderOnWorker(monoPcm: ByteArray, sampleRate: Int, ptsUs: Long) {
        val codec = audioEncoder ?: return
        try {
            var offset = 0
            while (offset < monoPcm.size) {
                val idx = codec.dequeueInputBuffer(5_000L)
                if (idx < 0) break
                val buf = codec.getInputBuffer(idx) ?: break
                buf.clear()
                val chunkLen = minOf(buf.remaining(), monoPcm.size - offset)
                buf.put(monoPcm, offset, chunkLen)
                codec.queueInputBuffer(idx, 0, chunkLen, ptsUs, 0)
                offset += chunkLen
            }
            drainCodecOnWorker(codec, isVideo = false, endOfStream = false)
        } catch (t: Throwable) {
            SafeLogger.w("CallRecording", "Audio feed error: ${t.message}")
        }
    }

    private fun drawVideoFrameOnWorker(frame: VideoFrame, ptsUs: Long) {
        val vCodec = videoEncoder ?: return
        val egl = encoderEgl ?: return
        val vDrawer = frameDrawer ?: return
        val gDrawer = rectDrawer ?: return
        try {
            egl.makeCurrent()
            val matrix = Matrix()
            vDrawer.drawFrame(frame, gDrawer, matrix, 0, 0, VIDEO_W, VIDEO_H)
            egl.swapBuffers(ptsUs * 1000L)
            drainCodecOnWorker(vCodec, isVideo = true, endOfStream = false)
        } catch (t: Throwable) {
            SafeLogger.w("CallRecording", "Video frame draw error: ${t.message}")
        }
    }

    private fun finishEncodersOnWorker() {
        try {
            audioEncoder?.let { aCodec ->
                val idx = aCodec.dequeueInputBuffer(10_000L)
                if (idx >= 0) {
                    aCodec.queueInputBuffer(idx, 0, 0, lastPtsUs + 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainCodecOnWorker(aCodec, isVideo = false, endOfStream = true)
            }
            videoEncoder?.let { vCodec ->
                vCodec.signalEndOfInputStream()
                drainCodecOnWorker(vCodec, isVideo = true, endOfStream = true)
            }
        } catch (t: Throwable) {
            SafeLogger.w("CallRecording", "Encoder EOS error: ${t.message}")
        } finally {
            cleanupOnWorkerSync()
        }
    }

    private fun drainCodecOnWorker(codec: MediaCodec, isVideo: Boolean, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        val timeoutUs = if (endOfStream) 10_000L else 0L
        var eosLoops = 0
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || eosLoops++ > 12) break
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isVideo) videoFormat = codec.outputFormat else audioFormat = codec.outputFormat
                tryStartMuxerOnWorker()
            } else if (outIdx >= 0) {
                val outBuf = codec.getOutputBuffer(outIdx)
                if (outBuf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val copy = ByteArray(info.size)
                    outBuf.get(copy)
                    val copyInfo = MediaCodec.BufferInfo().apply {
                        set(0, copy.size, info.presentationTimeUs, info.flags)
                    }
                    if (muxerStarted) {
                        writePacketToMuxerOnWorker(isVideo, copy, copyInfo)
                    } else {
                        val list = if (isVideo) pendingVideoPackets else pendingAudioPackets
                        if (list.size < 180) {
                            list.add(EncodedPacket(isVideo, copy, copyInfo))
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun tryStartMuxerOnWorker() {
        val m = muxer ?: return
        if (muxerStarted) return
        val aFmt = audioFormat ?: return
        if (recordVideoTrack && videoFormat == null) return

        audioTrackIndex = m.addTrack(aFmt)
        if (recordVideoTrack && videoFormat != null) {
            videoTrackIndex = m.addTrack(videoFormat!!)
        }
        m.start()
        muxerStarted = true

        pendingAudioPackets.forEach { writePacketToMuxerOnWorker(false, it.data, it.info) }
        pendingVideoPackets.forEach { writePacketToMuxerOnWorker(true, it.data, it.info) }
        pendingAudioPackets.clear()
        pendingVideoPackets.clear()
    }

    private fun writePacketToMuxerOnWorker(isVideo: Boolean, bytes: ByteArray, info: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        if (!muxerStarted) return
        val track = if (isVideo) videoTrackIndex else audioTrackIndex
        if (track < 0) return
        runCatching {
            m.writeSampleData(track, ByteBuffer.wrap(bytes), info)
        }
    }

    private fun cleanupOnWorkerSync() {
        runCatching { frameDrawer?.release() }
        runCatching { rectDrawer?.release() }
        runCatching { encoderEgl?.release() }
        frameDrawer = null
        rectDrawer = null
        encoderEgl = null

        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        audioEncoder = null

        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null

        if (muxerStarted) {
            runCatching { muxer?.stop() }
        }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
        recordVideoTrack = false
    }

    private fun cleanupThread() {
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        activeCallId = null
        activeTempFile = null
    }

    companion object {
        private const val VIDEO_W = 720
        private const val VIDEO_H = 1280
    }
}
