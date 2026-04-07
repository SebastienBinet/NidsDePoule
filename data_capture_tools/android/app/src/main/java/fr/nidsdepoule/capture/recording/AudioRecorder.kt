package fr.nidsdepoule.capture.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Continuous audio recorder that writes raw PCM to a WAV file.
 *
 * Records mono 16-bit at 16 kHz — good enough for voice and impact sounds,
 * and produces ~1.9 MB/minute (~115 MB/hour).
 *
 * Runs on its own thread to avoid blocking sensor callbacks.
 */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2  // 16-bit
        private const val NUM_CHANNELS = 1      // mono
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    @Volatile var bytesWritten = 0L; private set

    /**
     * Start recording audio to a WAV file.
     * Writes a placeholder WAV header first, then fills in the correct sizes on stop.
     */
    @SuppressLint("MissingPermission")
    fun start(outputFile: File) {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            4096,
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return
        }

        isRecording = true
        bytesWritten = 0L

        recordingThread = Thread({
            val fos = FileOutputStream(outputFile)
            val buffer = ByteArray(bufferSize)

            // Write placeholder WAV header (44 bytes) — will be updated on stop
            fos.write(buildWavHeader(0))

            audioRecord?.startRecording()

            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    fos.write(buffer, 0, read)
                    bytesWritten += read
                }
            }

            audioRecord?.stop()
            fos.flush()
            fos.close()

            // Fix WAV header with correct data size
            fixWavHeader(outputFile, bytesWritten)

        }, "audio-recorder").apply { start() }
    }

    fun stop() {
        isRecording = false
        recordingThread?.join(3000)
        recordingThread = null
        audioRecord?.release()
        audioRecord = null
    }

    private fun buildWavHeader(dataSize: Long): ByteArray {
        val totalSize = dataSize + 36  // file size minus 8 bytes for RIFF header
        val byteRate = SAMPLE_RATE * NUM_CHANNELS * BYTES_PER_SAMPLE
        val blockAlign = NUM_CHANNELS * BYTES_PER_SAMPLE

        val header = ByteArray(44)
        // RIFF chunk
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeInt(header, 4, totalSize.toInt())
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()

        // fmt sub-chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt(header, 16, 16)  // sub-chunk size
        writeShort(header, 20, 1)  // PCM format
        writeShort(header, 22, NUM_CHANNELS)
        writeInt(header, 24, SAMPLE_RATE)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, blockAlign)
        writeShort(header, 34, BYTES_PER_SAMPLE * 8)

        // data sub-chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeInt(header, 40, dataSize.toInt())

        return header
    }

    private fun fixWavHeader(file: File, dataSize: Long) {
        val raf = RandomAccessFile(file, "rw")
        raf.seek(4)
        writeIntToRaf(raf, (dataSize + 36).toInt())
        raf.seek(40)
        writeIntToRaf(raf, dataSize.toInt())
        raf.close()
    }

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value shr 8 and 0xFF).toByte()
        buf[offset + 2] = (value shr 16 and 0xFF).toByte()
        buf[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun writeIntToRaf(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xFF)
        raf.write(value shr 8 and 0xFF)
        raf.write(value shr 16 and 0xFF)
        raf.write(value shr 24 and 0xFF)
    }
}
