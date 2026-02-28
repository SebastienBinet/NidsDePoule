package fr.nidsdepoule.app.sensor.audio

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists and loads voice templates (MFCC feature sequences) for keyword recognition.
 *
 * Storage layout:
 *   files/voice_profiles/
 *     attention/0.json, 1.json, ..., 5.json
 *     trou/0.json, ...
 *     ...
 *
 * Each JSON file contains a 2D MFCC array: [[c0,c1,...,c12], [c0,c1,...], ...]
 */
class VoiceProfileStore(private val context: Context) {

    companion object {
        private const val TAG = "VoiceProfileStore"
        private const val DIR_NAME = "voice_profiles"
        const val SAMPLES_PER_KEYWORD = 6
    }

    private val baseDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)

    /**
     * Save an MFCC template for a keyword.
     * [keyword] = the keyword name (e.g. "attention")
     * [sampleIndex] = 0..5
     * [mfcc] = array of frames, each frame has 13 coefficients
     */
    fun saveTemplate(keyword: String, sampleIndex: Int, mfcc: Array<FloatArray>) {
        val dir = File(baseDir, keyword)
        dir.mkdirs()
        val file = File(dir, "$sampleIndex.json")
        val json = mfccToJson(mfcc)
        file.writeText(json.toString())
        Log.d(TAG, "Saved template: $keyword/$sampleIndex (${mfcc.size} frames)")
    }

    /**
     * Load all templates for a keyword.
     * Returns a list of MFCC sequences (one per recorded sample).
     */
    fun loadTemplates(keyword: String): List<Array<FloatArray>> {
        val dir = File(baseDir, keyword)
        if (!dir.exists()) return emptyList()

        val templates = mutableListOf<Array<FloatArray>>()
        for (i in 0 until SAMPLES_PER_KEYWORD) {
            val file = File(dir, "$i.json")
            if (file.exists()) {
                try {
                    val json = JSONArray(file.readText())
                    templates.add(jsonToMfcc(json))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load $keyword/$i: ${e.message}")
                }
            }
        }
        return templates
    }

    /**
     * Load all templates for all keywords.
     * Returns a map: keyword name → list of MFCC templates.
     */
    fun loadAllProfiles(): Map<String, List<Array<FloatArray>>> {
        val profiles = mutableMapOf<String, List<Array<FloatArray>>>()
        val dir = baseDir
        if (!dir.exists()) return profiles

        dir.listFiles()?.filter { it.isDirectory }?.forEach { keywordDir ->
            val templates = loadTemplates(keywordDir.name)
            if (templates.isNotEmpty()) {
                profiles[keywordDir.name] = templates
            }
        }
        return profiles
    }

    /**
     * Check how many samples have been recorded for a keyword.
     */
    fun sampleCount(keyword: String): Int {
        val dir = File(baseDir, keyword)
        if (!dir.exists()) return 0
        return (0 until SAMPLES_PER_KEYWORD).count { File(dir, "$it.json").exists() }
    }

    /**
     * Check if a keyword is fully trained (has all 6 samples).
     */
    fun isKeywordTrained(keyword: String): Boolean {
        return sampleCount(keyword) >= SAMPLES_PER_KEYWORD
    }

    /**
     * Check if all keywords in a list are fully trained.
     */
    fun areAllTrained(keywords: List<String>): Boolean {
        return keywords.all { isKeywordTrained(it) }
    }

    /**
     * Delete all templates for a keyword (for re-recording).
     */
    fun clearKeyword(keyword: String) {
        val dir = File(baseDir, keyword)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    /**
     * Delete all profiles.
     */
    fun clearAll() {
        baseDir.deleteRecursively()
    }

    /**
     * Migrate profiles from internal storage to external storage (one-time).
     * Call on app startup before loading profiles.
     */
    fun migrateIfNeeded() {
        val oldDir = File(context.filesDir, DIR_NAME)
        if (!oldDir.exists()) return
        val newDir = baseDir
        if (oldDir.absolutePath == newDir.absolutePath) return // fallback case
        if (newDir.exists() && (newDir.listFiles()?.isNotEmpty() == true)) {
            // Already migrated — clean up old dir
            oldDir.deleteRecursively()
            return
        }
        try {
            oldDir.copyRecursively(newDir, overwrite = false)
            oldDir.deleteRecursively()
            Log.d(TAG, "Migrated voice profiles from internal to external storage")
        } catch (e: Exception) {
            Log.w(TAG, "Migration failed: ${e.message}")
        }
    }

    // --- JSON serialization ---

    private fun mfccToJson(mfcc: Array<FloatArray>): JSONArray {
        val arr = JSONArray()
        for (frame in mfcc) {
            val frameArr = JSONArray()
            for (v in frame) frameArr.put(v.toDouble())
            arr.put(frameArr)
        }
        return arr
    }

    private fun jsonToMfcc(json: JSONArray): Array<FloatArray> {
        return Array(json.length()) { i ->
            val frameArr = json.getJSONArray(i)
            FloatArray(frameArr.length()) { j -> frameArr.getDouble(j).toFloat() }
        }
    }
}
