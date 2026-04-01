package fr.nidsdepoule.app.detection

import fr.nidsdepoule.app.sensor.audio.MfccExtractor
import kotlin.math.sqrt

/**
 * Frequency-domain analysis of accelerometer data using FFT.
 *
 * Reuses the Cooley-Tukey FFT from MfccExtractor.
 * At 50 Hz sample rate with 256-point FFT:
 *   - Resolution: 50/256 = 0.195 Hz/bin
 *   - Nyquist: 25 Hz
 *   - CPU cost: ~0.2ms per call
 *
 * Frequency bands for pothole discrimination:
 *   - Infra (0-1 Hz): braking, gentle turns
 *   - Low (1-5 Hz): speed bumps, large undulations
 *   - Mid (5-15 Hz): potholes, road joints
 *   - High (15-25 Hz): rough pavement, gravel
 */
object FrequencyAnalyzer {

    /** Band energy breakdown from FFT analysis. */
    data class BandEnergy(
        val infraEnergy: Float,  // 0-1 Hz
        val lowEnergy: Float,    // 1-5 Hz
        val midEnergy: Float,    // 5-15 Hz
        val highEnergy: Float,   // 15-25 Hz
        val dominantHz: Float,   // frequency of the highest-energy bin
        val totalEnergy: Float,
    )

    private const val FFT_SIZE = 256
    private const val SAMPLE_RATE_HZ = 50f

    // Bin boundaries at 50Hz / 256-point FFT (0.195 Hz/bin)
    private val INFRA_END = (1f / SAMPLE_RATE_HZ * FFT_SIZE).toInt()    // bin 5
    private val LOW_END = (5f / SAMPLE_RATE_HZ * FFT_SIZE).toInt()      // bin 25
    private val MID_END = (15f / SAMPLE_RATE_HZ * FFT_SIZE).toInt()     // bin 76
    private val HIGH_END = (25f / SAMPLE_RATE_HZ * FFT_SIZE).toInt()    // bin 128 (Nyquist)

    /**
     * Analyze the frequency content of magnitude samples.
     *
     * @param magnitudes List of magnitude values (milli-g). Uses last FFT_SIZE samples.
     * @return Band energy breakdown, or null if insufficient data.
     */
    fun analyze(magnitudes: List<Int>): BandEnergy? {
        if (magnitudes.size < FFT_SIZE) return null

        // Take last FFT_SIZE samples, remove DC by subtracting mean
        val window = magnitudes.takeLast(FFT_SIZE)
        val mean = window.average().toFloat()

        // Prepare interleaved complex array [re0, im0, re1, im1, ...]
        val data = FloatArray(FFT_SIZE * 2)
        for (i in 0 until FFT_SIZE) {
            // Apply Hann window to reduce spectral leakage
            val hann = (0.5 * (1 - kotlin.math.cos(2 * Math.PI * i / (FFT_SIZE - 1)))).toFloat()
            data[i * 2] = (window[i] - mean) * hann
            data[i * 2 + 1] = 0f
        }

        // In-place FFT
        MfccExtractor.fft(data)

        // Compute power spectrum (only first half — symmetric for real input)
        val halfN = FFT_SIZE / 2
        val power = FloatArray(halfN)
        for (i in 0 until halfN) {
            val re = data[i * 2]
            val im = data[i * 2 + 1]
            power[i] = sqrt(re * re + im * im)
        }

        // Sum energy per band
        var infraEnergy = 0f
        var lowEnergy = 0f
        var midEnergy = 0f
        var highEnergy = 0f
        var maxPower = 0f
        var maxBin = 0

        for (i in 1 until halfN) { // skip bin 0 (DC)
            val p = power[i]
            when {
                i < INFRA_END -> infraEnergy += p
                i < LOW_END -> lowEnergy += p
                i < MID_END -> midEnergy += p
                i < HIGH_END -> highEnergy += p
            }
            if (p > maxPower) {
                maxPower = p
                maxBin = i
            }
        }

        val totalEnergy = infraEnergy + lowEnergy + midEnergy + highEnergy
        val dominantHz = maxBin * SAMPLE_RATE_HZ / FFT_SIZE

        return BandEnergy(
            infraEnergy = infraEnergy,
            lowEnergy = lowEnergy,
            midEnergy = midEnergy,
            highEnergy = highEnergy,
            dominantHz = dominantHz,
            totalEnergy = totalEnergy,
        )
    }

    /**
     * Check if the frequency profile looks like a pothole (mid-band dominant)
     * rather than a speed bump (low-band dominant) or braking (infra dominant).
     */
    fun isPotholeProfile(energy: BandEnergy): Boolean {
        if (energy.totalEnergy < 1f) return false
        return energy.midEnergy > energy.lowEnergy && energy.dominantHz >= 2f
    }
}
