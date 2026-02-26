package fr.nidsdepoule.app.sensor.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * Continuous audio capture with voice activity detection.
 *
 * Captures 16 kHz mono 16-bit PCM from the microphone in a background thread.
 * When a speech segment is detected (energy-based VAD), it notifies the listener
 * with the raw PCM audio of the segment.
 */
class AudioCapture(
    private val sampleRate: Int = 16000,
) {
    companion object {
        private const val TAG = "AudioCapture"

        // VAD parameters
        private const val FRAME_SIZE = 160           // 10 ms at 16 kHz
        private const val SPEECH_THRESHOLD = 250f    // RMS energy threshold
        private const val SPEECH_START_FRAMES = 3    // consecutive frames to start
        private const val SPEECH_END_FRAMES = 15     // consecutive frames to end
        private const val MAX_SEGMENT_FRAMES = 300   // 3 seconds max
        private const val MIN_SEGMENT_FRAMES = 20    // 200 ms min
    }

    /** Called when a speech segment is captured. */
    var onSpeechSegment: ((ShortArray) -> Unit)? = null

    /** Called every frame with the RMS energy (for UI level meters). */
    var onAudioLevel: ((Float) -> Unit)? = null

    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = maxOf(minBuf, sampleRate) // at least 1 second

        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufSize,
        )

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            recorder?.release()
            recorder = null
            return
        }

        running = true
        recorder?.startRecording()

        captureThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            captureLoop()
        }, "AudioCapture").apply { start() }

        Log.i(TAG, "Audio capture started at ${sampleRate}Hz")
    }

    fun stop() {
        running = false
        captureThread?.join(2000)
        captureThread = null
        try {
            recorder?.stop()
            recorder?.release()
        } catch (_: Exception) { }
        recorder = null
        Log.i(TAG, "Audio capture stopped")
    }

    val isRunning: Boolean get() = running

    /**
     * Record a fixed-duration segment (for training). Blocks the calling thread.
     * Returns the captured PCM samples, or null on failure.
     */
    @SuppressLint("MissingPermission")
    fun recordSegment(durationMs: Int): ShortArray? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val numSamples = sampleRate * durationMs / 1000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = maxOf(minBuf, numSamples * 2)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufSize,
        )

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }

        val buffer = ShortArray(numSamples)
        rec.startRecording()
        var offset = 0
        while (offset < numSamples) {
            val read = rec.read(buffer, offset, numSamples - offset)
            if (read <= 0) break
            offset += read
        }
        rec.stop()
        rec.release()

        // Trim silence from start and end
        return trimSilence(buffer)
    }

    // --- Capture loop with VAD ---

    private fun captureLoop() {
        val frame = ShortArray(FRAME_SIZE)
        val segmentBuffer = ArrayList<Short>(MAX_SEGMENT_FRAMES * FRAME_SIZE)
        var speechFrameCount = 0
        var silenceFrameCount = 0
        var inSpeech = false

        while (running) {
            val read = recorder?.read(frame, 0, FRAME_SIZE) ?: -1
            if (read <= 0) continue

            val rms = computeRms(frame, read)
            onAudioLevel?.invoke(rms)

            val isSpeechFrame = rms > SPEECH_THRESHOLD

            if (!inSpeech) {
                if (isSpeechFrame) {
                    speechFrameCount++
                    if (speechFrameCount >= SPEECH_START_FRAMES) {
                        inSpeech = true
                        silenceFrameCount = 0
                        segmentBuffer.clear()
                        // Include the frames that triggered speech start
                    }
                } else {
                    speechFrameCount = 0
                }
                // Always buffer last few frames (pre-roll)
                for (i in 0 until read) segmentBuffer.add(frame[i])
                if (segmentBuffer.size > SPEECH_START_FRAMES * FRAME_SIZE * 2) {
                    // Keep only last N frames as pre-roll
                    val keep = SPEECH_START_FRAMES * FRAME_SIZE
                    val excess = segmentBuffer.size - keep
                    if (excess > 0) {
                        for (i in 0 until excess) segmentBuffer.removeAt(0)
                    }
                }
            } else {
                // In speech
                for (i in 0 until read) segmentBuffer.add(frame[i])

                if (!isSpeechFrame) {
                    silenceFrameCount++
                    if (silenceFrameCount >= SPEECH_END_FRAMES) {
                        // End of speech
                        inSpeech = false
                        speechFrameCount = 0
                        if (segmentBuffer.size >= MIN_SEGMENT_FRAMES * FRAME_SIZE) {
                            val segment = ShortArray(segmentBuffer.size)
                            for (i in segment.indices) segment[i] = segmentBuffer[i]
                            onSpeechSegment?.invoke(segment)
                        }
                        segmentBuffer.clear()
                    }
                } else {
                    silenceFrameCount = 0
                }

                // Cap segment length
                if (segmentBuffer.size >= MAX_SEGMENT_FRAMES * FRAME_SIZE) {
                    inSpeech = false
                    speechFrameCount = 0
                    val segment = ShortArray(segmentBuffer.size)
                    for (i in segment.indices) segment[i] = segmentBuffer[i]
                    onSpeechSegment?.invoke(segment)
                    segmentBuffer.clear()
                }
            }
        }
    }

    private fun computeRms(frame: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val s = frame[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / length).toFloat()
    }

    private fun trimSilence(pcm: ShortArray, threshold: Float = 150f): ShortArray {
        // Find first sample above threshold
        var start = 0
        for (i in pcm.indices step FRAME_SIZE) {
            val end = minOf(i + FRAME_SIZE, pcm.size)
            val rms = computeRms(pcm.sliceArray(i until end), end - i)
            if (rms > threshold) {
                start = maxOf(0, i - FRAME_SIZE) // keep one frame before
                break
            }
        }
        // Find last sample above threshold
        var last = pcm.size
        for (i in pcm.size - FRAME_SIZE downTo 0 step FRAME_SIZE) {
            val end = minOf(i + FRAME_SIZE, pcm.size)
            val rms = computeRms(pcm.sliceArray(i until end), end - i)
            if (rms > threshold) {
                last = minOf(pcm.size, i + FRAME_SIZE * 2) // keep one frame after
                break
            }
        }
        return if (last > start) pcm.sliceArray(start until last) else pcm
    }
}
