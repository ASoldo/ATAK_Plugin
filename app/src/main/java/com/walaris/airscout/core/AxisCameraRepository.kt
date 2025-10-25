package com.walaris.airscout.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists Axis camera definitions in plugin scoped SharedPreferences.
 */
class AxisCameraRepository(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cache: MutableMap<String, AxisCamera> = loadInternal()

    fun getAll(): List<AxisCamera> = synchronized(this) {
        cache.values.map { it.copy() }.sortedBy { it.displayName }
    }

    fun get(uid: String): AxisCamera? = synchronized(this) {
        cache[uid]?.copy()
    }

    fun upsert(camera: AxisCamera) {
        synchronized(this) {
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
        synchronized(this) {
            if (cache.remove(uid) != null) {
                persistLocked()
            }
        }
    }

    fun replaceAll(cameras: Collection<AxisCamera>) {
        synchronized(this) {
            cache = cameras.associateBy { it.uid }.mapValues { it.value.copy() }.toMutableMap()
            persistLocked()
        }
    }

    private fun loadInternal(): MutableMap<String, AxisCamera> {
        val json = preferences.getString(KEY_CAMERAS, null) ?: return mutableMapOf()
        return try {
            val array = JSONArray(json)
            buildMap {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val camera = AxisCamera.fromJson(obj)
                    put(camera.uid, camera)
                }
            }.toMutableMap()
        } catch (ex: Exception) {
            mutableMapOf()
        }
    }

    private fun persistLocked() {
        val array = JSONArray()
        cache.values.sortedBy { it.displayName }.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_CAMERAS, array.toString()).apply()
    }

    companion object {
        private const val PREF_NAME = "walaris_airscout"
        private const val KEY_CAMERAS = "cameras"
    }
}
