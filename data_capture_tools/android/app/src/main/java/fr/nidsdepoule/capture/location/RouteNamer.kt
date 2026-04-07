package fr.nidsdepoule.capture.location

import android.content.Context
import android.location.Geocoder
import android.location.Location
import java.util.Locale

/**
 * Suggests origin/destination names from GPS coordinates via reverse geocoding,
 * and generates 9-char abbreviations for filenames.
 *
 * Examples:
 *   "Rue Sherbrooke / Rue St-Denis" → "Sher_SDen"
 *   "Montréal" to "Sherbrooke" → "Mont2Sher"
 */
object RouteNamer {

    data class RouteSuggestion(
        val origin: String,
        val destination: String,
        val abbreviation: String,
    )

    /**
     * Reverse-geocode first and last GPS locations to suggest route names.
     * Returns null if geocoding fails (no network, etc.).
     */
    fun suggest(context: Context, firstFix: Location?, lastFix: Location?): RouteSuggestion? {
        if (firstFix == null || lastFix == null) return null
        if (!Geocoder.isPresent()) return null

        val geocoder = Geocoder(context, Locale.getDefault())
        val originName = reverseGeocode(geocoder, firstFix.latitude, firstFix.longitude)
        val destName = reverseGeocode(geocoder, lastFix.latitude, lastFix.longitude)

        if (originName == null && destName == null) return null

        val origin = originName ?: "?"
        val dest = destName ?: "?"
        val abbrev = buildAbbreviation(origin, dest)

        return RouteSuggestion(origin, dest, abbrev)
    }

    private fun reverseGeocode(geocoder: Geocoder, lat: Double, lon: Double): String? {
        return try {
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (addresses.isNullOrEmpty()) return null
            val addr = addresses[0]

            // Try to build "Street1 / Street2" intersection name
            val street = addr.thoroughfare  // e.g. "Rue Sherbrooke Ouest"
            val city = addr.locality        // e.g. "Montréal"

            when {
                street != null -> street
                city != null -> city
                else -> addr.getAddressLine(0)?.take(30)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build a compact abbreviation from origin and destination names.
     * Target: ~9 chars, format "Orig2Dest".
     *
     * Examples:
     *   "Rue Sherbrooke" to "Rue Guy" → "Sher2Guy"
     *   "Montréal" to "Sherbrooke" → "Mont2Sher"
     *   "Rue St-Denis/René-Lévesque" to "Rue Guy/St-Jacques" → "SDRL2GuSJ"
     */
    fun buildAbbreviation(origin: String, dest: String): String {
        val o = abbreviateName(origin, 4)
        val d = abbreviateName(dest, 4)
        return "${o}2$d"
    }

    private fun abbreviateName(name: String, maxLen: Int): String {
        // Remove common prefixes
        var clean = name
            .replace(Regex("^(Rue|Avenue|Boulevard|Chemin|Route|Place|Av\\.|Boul\\.?)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()

        // Handle intersection "X / Y" → take initials of each
        if (clean.contains("/")) {
            val parts = clean.split("/").map { it.trim() }
            return parts.joinToString("") { abbreviateWord(it, 2) }.take(maxLen)
        }

        // Handle "St-Denis" → "SDen", "René-Lévesque" → "RLev"
        if (clean.contains("-")) {
            val parts = clean.split("-")
            return parts.joinToString("") { abbreviateWord(it, 2) }.take(maxLen)
        }

        return abbreviateWord(clean, maxLen)
    }

    private fun abbreviateWord(word: String, maxLen: Int): String {
        val w = word.trim()
        if (w.isEmpty()) return ""
        // Capitalize first letter, take consonants + first vowel
        val first = w[0].uppercase()
        val rest = w.drop(1).filter { it.isLetter() }
        return (first + rest).take(maxLen)
    }
}
