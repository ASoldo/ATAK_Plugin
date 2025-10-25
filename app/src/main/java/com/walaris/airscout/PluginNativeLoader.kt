package com.walaris.airscout

import android.content.Context
import java.io.File

object PluginNativeLoader {

    private var nativeLibDir: String? = null

    @Synchronized
    fun init(context: Context) {
        if (nativeLibDir != null) return
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        nativeLibDir = info.nativeLibraryDir
    }

    fun loadLibrary(name: String) {
        val dir = nativeLibDir
            ?: throw IllegalStateException("PluginNativeLoader not initialized")
        val libraryPath = dir + File.separator + System.mapLibraryName(name)
        val target = File(libraryPath)
        if (!target.exists()) {
            throw IllegalArgumentException("Native library $libraryPath missing")
        }
        System.load(libraryPath)
    }
}
