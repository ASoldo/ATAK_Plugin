package com.walaris.airscout.packaging

import android.net.Uri
import com.atakmap.android.importexport.AbstractMarshal
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

/**
 * Marshal that identifies AirScout resource bundles within ATAK's import manager.
 */
object AirScoutResourceMarshal : AbstractMarshal(AirScoutResourceImporter.CONTENT_TYPE) {

    override fun marshal(inputStream: InputStream, limit: Int): String? {
        // Stream-based inspection is not currently supported for this marshal.
        return null
    }

    @Throws(IOException::class)
    override fun marshal(uri: Uri): String? {
        val path = uri.path ?: return null
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        val name = file.name.lowercase(Locale.US)
        if (!name.endsWith(".json") && !name.endsWith(".airscout")) return null

        FileInputStream(file).use { fis ->
            InputStreamReader(fis).use { isr ->
                BufferedReader(isr).use { reader ->
                    val content = reader.readText()
                    return if (looksLikeAirScoutPayload(content)) {
                        AirScoutResourceImporter.MIME_TYPE
                    } else {
                        null
                    }
                }
            }
        }
    }

    override fun getPriorityLevel(): Int = DEFAULT_PRIORITY

    private fun looksLikeAirScoutPayload(payload: String): Boolean {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            val array = JSONArray(trimmed)
            if (array.length() == 0) return@runCatching true
            val first = array.optJSONObject(0) ?: return@runCatching false
            hasExpectedKeys(first)
        }.getOrDefault(false)
    }

    private fun hasExpectedKeys(obj: JSONObject): Boolean {
        return obj.has("uid") &&
            obj.has("displayName") &&
            obj.has("rtsp") &&
            obj.has("control")
    }

    private const val DEFAULT_PRIORITY = 10
}
