package fr.nidsdepoule.app.sensor.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dynamic Time Warping matcher for comparing MFCC feature sequences.
 *
 * Used to match a spoken word against stored reference templates.
 * Uses a Sakoe-Chiba band constraint for efficiency.
 */
object DtwMatcher {

    /**
     * Compute the DTW distance between two MFCC sequences.
     * Returns normalized distance (lower = more similar).
     */
    fun distance(seq1: Array<FloatArray>, seq2: Array<FloatArray>): Float {
        val n = seq1.size
        val m = seq2.size
        if (n == 0 || m == 0) return Float.MAX_VALUE

        // Sakoe-Chiba band width: allow warping up to half the longer sequence
        val band = max(n, m) / 2 + 1

        // Use two rows instead of full matrix to save memory
        var prev = FloatArray(m + 1) { Float.MAX_VALUE }
        var curr = FloatArray(m + 1) { Float.MAX_VALUE }
        prev[0] = 0f

        for (i in 1..n) {
            curr.fill(Float.MAX_VALUE)
            val jStart = max(1, i - band)
            val jEnd = min(m, i + band)
            for (j in jStart..jEnd) {
                val dist = euclidean(seq1[i - 1], seq2[j - 1])
                curr[j] = dist + minOf(prev[j], curr[j - 1], prev[j - 1])
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }

        // Normalize by path length
        return prev[m] / max(n, m)
    }

    /**
     * Match a query against multiple templates, returning the best (lowest) distance.
     */
    fun bestMatch(query: Array<FloatArray>, templates: List<Array<FloatArray>>): Float {
        if (templates.isEmpty()) return Float.MAX_VALUE
        var best = Float.MAX_VALUE
        for (t in templates) {
            val d = distance(query, t)
            if (d < best) best = d
        }
        return best
    }

    /**
     * Match a query against all keyword templates, returning distances for each keyword.
     * Result maps keyword name → best DTW distance across that keyword's templates.
     */
    fun matchAll(
        query: Array<FloatArray>,
        profiles: Map<String, List<Array<FloatArray>>>,
    ): Map<String, Float> {
        return profiles.mapValues { (_, templates) -> bestMatch(query, templates) }
    }

    /**
     * Convert DTW distance to a similarity score in [0, 1].
     * 1.0 = perfect match, 0.0 = no match.
     */
    fun similarity(distance: Float, threshold: Float): Float {
        if (distance >= threshold) return 0f
        return 1f - distance / threshold
    }

    private fun euclidean(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - (if (i < b.size) b[i] else 0f)
            sum += d * d
        }
        return sqrt(sum)
    }
}
