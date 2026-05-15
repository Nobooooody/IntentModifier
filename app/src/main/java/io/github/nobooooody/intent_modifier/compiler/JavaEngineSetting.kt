package io.github.nobooooody.intent_modifier.compiler

import android.content.Context
import android.util.Log
import java.io.File

class JavaEngineSetting(private val context: Context) {

    companion object {
        private const val DEFAULT_SOURCE_VERSION = "1.8"
        private const val DEFAULT_TARGET_VERSION = "1.8"
        private const val DEFAULT_COMPILE_CHARSET = "UTF-8"
        private const val TAG = "JavaEngineSetting"
    }

    var classSourceVersion: String = DEFAULT_SOURCE_VERSION
    var classTargetVersion: String = DEFAULT_TARGET_VERSION
    var compileEncoding: String = DEFAULT_COMPILE_CHARSET

    val defaultCacheDir: String
        get() = context.filesDir.absolutePath + "/tmp/compiler"

    val logFilePath: String
        get() = context.cacheDir.absolutePath + File.separator + "class_compile.log"

    val rtJarPath: String
        get() = context.filesDir.absolutePath + "/lib/rt.jar"

    val androidJarPath: String
        get() = context.filesDir.absolutePath + "/lib/android.jar"

    val fullClassPath: String
        get() = "$androidJarPath${File.pathSeparator}$rtJarPath"

    fun createAndCleanDir(dir: File): String {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir.listFiles()?.forEach { it.delete() }
        return dir.absolutePath
    }

    fun createAndCleanFile(file: File): String {
        if (file.exists()) {
            file.delete()
        }
        file.parentFile?.mkdirs()
        file.createNewFile()
        return file.absolutePath
    }

    fun ensureRtJarInstalled(): Boolean {
        val rtJar = File(rtJarPath)
        if (rtJar.exists() && rtJar.length() > 0) {
            return true
        }

        try {
            val libDir = File(context.filesDir, "lib")
            libDir.mkdirs()

            context.assets.open("rt.jar").use { input ->
                rtJar.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "rt.jar installed successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install rt.jar: ${e.message}")
            return false
        }
    }

    fun ensureAndroidJarInstalled(): Boolean {
        val androidJar = File(androidJarPath)
        if (androidJar.exists() && androidJar.length() > 0) {
            return true
        }

        try {
            val libDir = File(context.filesDir, "lib")
            libDir.mkdirs()

            context.assets.open("android.jar").use { input ->
                androidJar.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "android.jar installed successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install android.jar: ${e.message}")
            return false
        }
    }

    fun ensureAllJarsInstalled(): Boolean {
        return ensureAndroidJarInstalled() && ensureRtJarInstalled()
    }
}