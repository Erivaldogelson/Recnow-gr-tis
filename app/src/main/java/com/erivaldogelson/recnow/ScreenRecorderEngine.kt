package com.erivaldogelson.recnow

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class ScreenRecorderEngine(
    private val context: Context,
    private val outputDescriptor: ParcelFileDescriptor,
    private val spec: CaptureSpec,
    private val quality: RecordingQuality,
    private val options: RecordingOptions,
    private val projection: MediaProjection
) {
    private val videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE)
    private var audioEncoder: MediaCodec? = null
    private val muxer = MediaMuxer(outputDescriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val stopping = AtomicBoolean(false)
    private val muxerLock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val wantsAudio = options.audioMode != AudioMode.NONE && audioSupported()
    lateinit var inputSurface: Surface
        private set

    fun start() {
        val videoFormat = MediaFormat.createVideoFormat(
            VIDEO_MIME_TYPE,
            spec.width,
            spec.height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, quality.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoEncoder.createInputSurface()
        videoEncoder.start()

        if (wantsAudio) {
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            startAudioThreads()
        }
        startVideoThread()
    }

    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        runCatching { videoEncoder.signalEndOfInputStream() }
        synchronized(muxerLock) {
            muxerLock.notifyAll()
        }
    }

    fun release() {
        stop()
        Thread.sleep(250)
        runCatching { inputSurface.release() }
        runCatching { videoEncoder.stop() }
        runCatching { videoEncoder.release() }
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        runCatching {
            if (muxerStarted) muxer.stop()
        }
        runCatching { muxer.release() }
        runCatching { outputDescriptor.close() }
    }

    private fun startVideoThread() {
        thread(name = "RecnowVideoEncoder") {
            val info = MediaCodec.BufferInfo()
            var done = false
            while (!done) {
                val index = videoEncoder.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            videoTrack = muxer.addTrack(videoEncoder.outputFormat)
                            startMuxerIfReady()
                        }
                    }
                    index >= 0 -> {
                        val encoded = videoEncoder.getOutputBuffer(index)
                        if (encoded != null && info.size > 0) {
                            waitForMuxer()
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            synchronized(muxerLock) {
                                if (muxerStarted) muxer.writeSampleData(videoTrack, encoded, info)
                            }
                        }
                        done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        videoEncoder.releaseOutputBuffer(index, false)
                    }
                }
                if (stopping.get() && !done) {
                    runCatching { videoEncoder.signalEndOfInputStream() }
                }
            }
        }
    }

    private fun startAudioThreads() {
        val encoder = audioEncoder ?: return
        val records = createAudioRecords()
        records.forEach { it.startRecording() }

        thread(name = "RecnowAudioInput") {
            val bufferSize = AUDIO_BUFFER_SAMPLES * CHANNEL_COUNT * BYTES_PER_SAMPLE
            val mixBuffer = ByteArray(bufferSize)
            val sourceBuffers = records.map { ByteArray(bufferSize) }
            while (!stopping.get()) {
                sourceBuffers.forEach { it.fill(0) }
                var maxRead = 0
                records.forEachIndexed { index, record ->
                    val read = record.read(sourceBuffers[index], 0, bufferSize)
                    if (read > 0) maxRead = max(maxRead, read)
                }
                if (maxRead <= 0) continue
                mixPcm16(sourceBuffers, mixBuffer, maxRead)
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    encoder.getInputBuffer(inputIndex)?.apply {
                        clear()
                        put(mixBuffer, 0, maxRead)
                    }
                    encoder.queueInputBuffer(inputIndex, 0, maxRead, System.nanoTime() / 1000L, 0)
                }
            }
            val inputIndex = encoder.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(inputIndex, 0, 0, System.nanoTime() / 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            records.forEach {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
        }

        thread(name = "RecnowAudioEncoder") {
            val info = MediaCodec.BufferInfo()
            var done = false
            while (!done) {
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            audioTrack = muxer.addTrack(encoder.outputFormat)
                            startMuxerIfReady()
                        }
                    }
                    index >= 0 -> {
                        val encoded = encoder.getOutputBuffer(index)
                        if (encoded != null && info.size > 0) {
                            waitForMuxer()
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            synchronized(muxerLock) {
                                if (muxerStarted) muxer.writeSampleData(audioTrack, encoded, info)
                            }
                        }
                        done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
    }

    private fun createAudioRecords(): List<AudioRecord> {
        val records = mutableListOf<AudioRecord>()
        if (options.audioMode == AudioMode.MEDIA || options.audioMode == AudioMode.MEDIA_AND_MICROPHONE) {
            if (Build.VERSION.SDK_INT >= 29) {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                records += AudioRecord.Builder()
                    .setAudioFormat(pcmFormat())
                    .setBufferSizeInBytes(minAudioBufferSize())
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            }
        }
        if (options.audioMode == AudioMode.MICROPHONE || options.audioMode == AudioMode.MEDIA_AND_MICROPHONE) {
            if (hasAudioPermission()) {
                records += AudioRecord.Builder()
                    .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(pcmFormat())
                    .setBufferSizeInBytes(minAudioBufferSize())
                    .build()
            }
        }
        return records
    }

    private fun audioSupported(): Boolean {
        return when (options.audioMode) {
            AudioMode.NONE -> false
            AudioMode.MICROPHONE -> hasAudioPermission()
            AudioMode.MEDIA -> Build.VERSION.SDK_INT >= 29 && hasAudioPermission()
            AudioMode.MEDIA_AND_MICROPHONE -> Build.VERSION.SDK_INT >= 29 && hasAudioPermission()
        }
    }

    private fun startMuxerIfReady() {
        if (muxerStarted) return
        if (videoTrack < 0) return
        if (wantsAudio && audioTrack < 0) return
        muxer.start()
        muxerStarted = true
        muxerLock.notifyAll()
    }

    private fun waitForMuxer() {
        synchronized(muxerLock) {
            while (!muxerStarted && !stopping.get()) {
                muxerLock.wait(50)
            }
        }
    }

    private fun audioFormat(): MediaFormat {
        return MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
        }
    }

    private fun pcmFormat(): AudioFormat {
        return AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
    }

    private fun minAudioBufferSize(): Int {
        val minSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return max(minSize, AUDIO_BUFFER_SAMPLES * CHANNEL_COUNT * BYTES_PER_SAMPLE * 2)
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_COUNT = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val AUDIO_BUFFER_SAMPLES = 1024
        private const val VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_HEVC
    }
}

private fun mixPcm16(sourceBuffers: List<ByteArray>, output: ByteArray, size: Int) {
    if (sourceBuffers.size == 1) {
        sourceBuffers.first().copyInto(output, endIndex = size)
        return
    }
    var i = 0
    while (i + 1 < size) {
        var sample = 0
        sourceBuffers.forEach { buffer ->
            val low = buffer[i].toInt() and 0xff
            val high = buffer[i + 1].toInt()
            sample += (high shl 8) or low
        }
        val mixed = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        output[i] = (mixed.toInt() and 0xff).toByte()
        output[i + 1] = ((mixed.toInt() shr 8) and 0xff).toByte()
        i += 2
    }
}
