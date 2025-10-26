package com.walaris.airscout.core

import android.content.Context
import java.io.File

/**
 * Helper for resolving plugin-scoped storage directories. This keeps all
 * AirScout artifacts under a dedicated, private path managed by ATAK.
 */
object PluginStorage {

    private const val BASE_DIR_NAME = "airscout"

    fun resolveDirectory(context: Context, child: String = ""): File {
        val base = context.getDir(BASE_DIR_NAME, Context.MODE_PRIVATE)
        if (!base.exists()) {
            base.mkdirs()
        }
        if (child.isBlank()) {
            return base
        }
        return File(base, child).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    fun resolveFile(context: Context, childDir: String, fileName: String): File {
        val dir = resolveDirectory(context, childDir)
        return File(dir, fileName)
    }
}
