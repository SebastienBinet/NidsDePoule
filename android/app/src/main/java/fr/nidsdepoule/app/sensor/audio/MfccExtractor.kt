package fr.nidsdepoule.app.sensor.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Extracts MFCC (Mel-Frequency Cepstral Coefficients) from raw 16-bit PCM audio.
 * Pure Kotlin — no external dependencies.
 *
 * Pipeline: pre-emphasis → framing → Hamming window → FFT → power spectrum
 *           → Mel filter bank → log → DCT → 13 MFCC coefficients per frame.
 */
class MfccExtractor(
    private val sampleRate: Int = 16000,
    private val frameLengthSamples: Int = 400,   // 25 ms at 16 kHz
    private val frameHopSamples: Int = 160,      // 10 ms at 16 kHz
    private val fftSize: Int = 512,
    private val numMelFilters: Int = 26,
    private val numCoeffs: Int = 13,
    private val preEmphCoeff: Float = 0.97f,
) {
    // Pre-computed Hamming window
    private val window = FloatArray(frameLengthSamples) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (frameLengthSamples - 1))).toFloat()
    }

    // Pre-computed Mel filter bank: [filter_index][fft_bin] → weight
    private val melFilters: Array<FloatArray> = buildMelFilterBank()

    // Pre-computed DCT-II matrix: [coeff][mel_filter] → weight
    private val dctMatrix: Array<FloatArray> = buildDctMatrix()

    /**
     * Extract MFCC features from raw 16-bit PCM samples.
     * Returns array of frames, each containing [numCoeffs] MFCC values.
     */
    fun extract(pcm: ShortArray): Array<FloatArray> {
        val emphasized = preEmphasize(pcm)
        val numFrames = (emphasized.size - frameLengthSamples) / frameHopSamples + 1
        if (numFrames <= 0) return emptyArray()

        return Array(numFrames) { f ->
            val start = f * frameHopSamples
            computeFrameMfcc(emphasized, start)
        }
    }

    /**
     * Compute MFCC for a single frame starting at [offset] in the pre-emphasized signal.
     */
    private fun computeFrameMfcc(signal: FloatArray, offset: Int): FloatArray {
        // 1. Window the frame and zero-pad to fftSize
        val fftData = FloatArray(fftSize * 2) // interleaved [re, im, re, im, ...]
        for (i in 0 until frameLengthSamples) {
            fftData[i * 2] = signal[offset + i] * window[i]
        }

        // 2. In-place FFT
        fft(fftData)

        // 3. Power spectrum (first N/2+1 bins)
        val numBins = fftSize / 2 + 1
        val power = FloatArray(numBins)
        for (k in 0 until numBins) {
            val re = fftData[k * 2]
            val im = fftData[k * 2 + 1]
            power[k] = (re * re + im * im) / fftSize
        }

        // 4. Apply Mel filter bank → log energies
        val melEnergies = FloatArray(numMelFilters)
        for (m in 0 until numMelFilters) {
            var sum = 0f
            val filter = melFilters[m]
            for (k in filter.indices) {
                sum += filter[k] * power[k]
            }
            melEnergies[m] = ln(max(sum, 1e-10f))
        }

        // 5. DCT to produce MFCC
        val mfcc = FloatArray(numCoeffs)
        for (c in 0 until numCoeffs) {
            var sum = 0f
            for (m in 0 until numMelFilters) {
                sum += dctMatrix[c][m] * melEnergies[m]
            }
            mfcc[c] = sum
        }
        return mfcc
    }

    // --- Pre-emphasis filter: y[n] = x[n] - α·x[n-1] ---

    private fun preEmphasize(pcm: ShortArray): FloatArray {
        val out = FloatArray(pcm.size)
        out[0] = pcm[0].toFloat()
        for (i in 1 until pcm.size) {
            out[i] = pcm[i] - preEmphCoeff * pcm[i - 1]
        }
        return out
    }

    // --- Mel filter bank construction ---

    private fun buildMelFilterBank(): Array<FloatArray> {
        val lowMel = hzToMel(0f)
        val highMel = hzToMel(sampleRate / 2f)
        val numBins = fftSize / 2 + 1

        // numMelFilters + 2 equally spaced points in mel scale
        val melPoints = FloatArray(numMelFilters + 2) { i ->
            lowMel + i * (highMel - lowMel) / (numMelFilters + 1)
        }
        val hzPoints = FloatArray(melPoints.size) { melToHz(melPoints[it]) }
        val binPoints = IntArray(hzPoints.size) {
            floor(hzPoints[it] / sampleRate * fftSize + 0.5f).toInt()
                .coerceIn(0, numBins - 1)
        }

        return Array(numMelFilters) { m ->
            val filter = FloatArray(numBins)
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]

            for (k in left until center) {
                if (center > left) {
                    filter[k] = (k - left).toFloat() / (center - left)
                }
            }
            for (k in center..right) {
                if (right > center) {
                    filter[k] = (right - k).toFloat() / (right - center)
                }
            }
            filter
        }
    }

    // --- DCT-II matrix ---

    private fun buildDctMatrix(): Array<FloatArray> {
        return Array(numCoeffs) { c ->
            FloatArray(numMelFilters) { m ->
                cos(PI * c * (m + 0.5) / numMelFilters).toFloat()
            }
        }
    }

    companion object {
        fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
        fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

        /**
         * In-place radix-2 Cooley-Tukey FFT.
         * [data] is interleaved complex: [re0, im0, re1, im1, ...], length = 2·N.
         */
        fun fft(data: FloatArray) {
            val n = data.size / 2

            // Bit-reversal permutation
            var j = 0
            for (i in 0 until n) {
                if (j > i) {
                    val tr = data[j * 2]; val ti = data[j * 2 + 1]
                    data[j * 2] = data[i * 2]; data[j * 2 + 1] = data[i * 2 + 1]
                    data[i * 2] = tr; data[i * 2 + 1] = ti
                }
                var m = n / 2
                while (m >= 1 && j >= m) {
                    j -= m
                    m /= 2
                }
                j += m
            }

            // Cooley-Tukey butterfly stages
            var step = 1
            while (step < n) {
                val halfStep = step
                step *= 2
                val angle = -PI / halfStep
                for (group in 0 until n step step) {
                    for (pair in 0 until halfStep) {
                        val theta = angle * pair
                        val wr = cos(theta).toFloat()
                        val wi = sin(theta).toFloat()
                        val i1 = (group + pair) * 2
                        val i2 = (group + pair + halfStep) * 2
                        val tr = wr * data[i2] - wi * data[i2 + 1]
                        val ti = wr * data[i2 + 1] + wi * data[i2]
                        data[i2] = data[i1] - tr
                        data[i2 + 1] = data[i1 + 1] - ti
                        data[i1] += tr
                        data[i1 + 1] += ti
                    }
                }
            }
        }
    }
}
