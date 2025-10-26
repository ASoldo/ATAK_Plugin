package com.walaris.airscout.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Persists Axis camera definitions and provides import/export helpers.
 */
class AxisCameraRepository(private val context: Context) {

    data class ImportResult(val total: Int, val added: Int, val updated: Int)

    private val storageFile = PluginStorage.resolveFile(context, STORAGE_SUBDIR, STORAGE_FILENAME)
    private val legacyPreferences by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    private val lock = Any()

    @Volatile
    private var cache: MutableMap<String, AxisCamera> = loadInternal()

    fun getAll(): List<AxisCamera> = synchronized(lock) {
        cache.values.map { it.copy() }.sortedBy { it.displayName }
    }

    fun get(uid: String): AxisCamera? = synchronized(lock) {
        cache[uid]?.copy()
    }

    fun upsert(camera: AxisCamera) {
        synchronized(lock) {
            val entry = cache[camera.uid]
            if (entry == null) {
                cache[camera.uid] = camera.copy()
            } else {
                entry.updateFrom(camera)
            }
            persistLocked()
        }
    }

    fun delete(uid: String) {
        synchronized(lock) {
            if (cache.remove(uid) != null) {
                persistLocked()
            }
        }
    }

    fun replaceAll(cameras: Collection<AxisCamera>) {
        synchronized(lock) {
            cache = cameras.associateBy { it.uid }.mapValues { it.value.copy() }.toMutableMap()
            persistLocked()
        }
    }

    fun exportTo(file: File): Boolean = synchronized(lock) {
        return try {
            if (!file.parentFile.exists()) {
                file.parentFile.mkdirs()
            }
            file.writeText(buildJson())
            true
        } catch (_: IOException) {
            false
        }
    }

    fun importFrom(file: File, replaceExisting: Boolean): ImportResult {
        if (!file.exists() || !file.canRead()) return ImportResult(0, 0, 0)
        val imported = parseJson(runCatching { file.readText() }.getOrNull() ?: return ImportResult(0, 0, 0))
            ?: return ImportResult(0, 0, 0)
        synchronized(lock) {
            if (replaceExisting) {
                cache = mutableMapOf()
            }
            var added = 0
            var updated = 0
            imported.forEach { camera ->
                val existing = cache[camera.uid]
                if (existing == null) {
                    cache[camera.uid] = camera.copy()
                    added++
                } else {
                    existing.updateFrom(camera)
                    updated++
                }
            }
            persistLocked()
            return ImportResult(imported.size, added, updated)
        }
    }

    private fun loadInternal(): MutableMap<String, AxisCamera> {
        ensureStoragePath()
        val diskPayload = runCatching {
            if (storageFile.isFile && storageFile.canRead()) storageFile.readText() else null
        }.getOrNull()
        val parsedDisk = diskPayload?.let { parseJson(it) }
        if (parsedDisk != null) {
            return parsedDisk.associateBy { it.uid }.mapValues { it.value.copy() }.toMutableMap()
        }

        val json = legacyPreferences.getString(KEY_CAMERAS, null)
        val parsedLegacy = json?.let { parseJson(it) }
        if (parsedLegacy != null) {
            synchronized(lock) {
                ensureStoragePath()
                runCatching {
                    storageFile.writeText(
                        JSONArray().apply {
                            parsedLegacy.sortedBy { it.displayName }.forEach { put(it.toJson()) }
                        }.toString()
                    )
                }
                legacyPreferences.edit().remove(KEY_CAMERAS).apply()
            }
            return parsedLegacy.associateBy { it.uid }.mapValues { it.value.copy() }.toMutableMap()
        }

        return mutableMapOf()
    }

    private fun parseJson(raw: String): List<AxisCamera>? {
        return try {
            val array = JSONArray(raw)
            val items = mutableListOf<AxisCamera>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                items += AxisCamera.fromJson(obj)
            }
            items
        } catch (_: Exception) {
            null
        }
    }

    private fun buildJson(): String {
        val array = JSONArray()
        cache.values.sortedBy { it.displayName }.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun persistLocked() {
        ensureStoragePath()
        try {
            storageFile.writeText(buildJson())
        } catch (_: IOException) {
            // swallow - caller handles reporting
        }
    }

    private fun ensureStoragePath() {
        storageFile.parentFile?.let {
            if (!it.exists()) {
                it.mkdirs()
            }
        }
    }

    companion object {
        private const val PREF_NAME = "walaris_airscout"
        private const val KEY_CAMERAS = "cameras"
        private const val STORAGE_SUBDIR = "data"
        private const val STORAGE_FILENAME = "cameras.json"
    }
}
