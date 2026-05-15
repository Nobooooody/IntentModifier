package io.github.nobooooody.intent_modifier.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dalvik.system.DexClassLoader
import io.github.nobooooody.intent_modifier.compiler.JavaEngineSetting
import io.github.nobooooody.intent_modifier.compiler.JavaPrintWriter
import org.eclipse.jdt.internal.compiler.batch.Main
import java.io.File

data class RuleSource(
    val condition: String?,
    val action: String?
)

class JavaRuleEngine(private val context: Context) {
    private val TAG = "JavaRuleEngine"
    private val setting = JavaEngineSetting(context)
    private val cacheDir: File by lazy {
        File(context.filesDir, "rule_cache").also { it.mkdirs() }
    }
    private val compiledRules = mutableMapOf<String, CompiledRuleInfo>()
    private var logCallback: ((String) -> Unit)? = null

    fun setLogCallback(callback: ((String) -> Unit)?) {
        logCallback = callback
    }

    private fun log(msg: String) {
        logCallback?.invoke(msg)
        Log.i(TAG, msg)
    }

    private fun logError(msg: String) {
        logCallback?.invoke("ERROR: $msg")
        Log.e(TAG, msg)
    }

    data class CompiledRuleInfo(
        val dexPath: String,
        val evaluateMethod: java.lang.reflect.Method,
        val executeMethod: java.lang.reflect.Method
    )

    fun compile(rules: List<RuleSource>): Boolean {
        try {
            if (!setting.ensureAllJarsInstalled()) {
                logError("Failed to install required JAR files")
                return false
            }

            clearCache()
            compiledRules.clear()

            for ((index, rule) in rules.withIndex()) {
                val ruleName = "Rule_$index"
                val sourceCode = buildRuleTemplate(ruleName, rule.condition, rule.action)
                val sourceFile = File(cacheDir, "$ruleName.java")
                sourceFile.parentFile?.mkdirs()
                sourceFile.writeText(sourceCode)

                val logFilePath = setting.createAndCleanFile(File(setting.logFilePath))
                val printWriter = JavaPrintWriter(logFilePath)

                val classOutputDir = File(cacheDir, "classes").also { it.mkdirs() }

                val compileCmd = arrayOf(
                    sourceFile.absolutePath,
                    "-d", classOutputDir.absolutePath,
                    "-encoding", setting.compileEncoding,
                    "-source", setting.classSourceVersion,
                    "-target", setting.classTargetVersion,
                    "-classpath", setting.fullClassPath,
                    "-nowarn",
                    "-time",
                    "-noExit"
                )

                log("Compiling $ruleName")

                val success = Main.compile(compileCmd, printWriter, printWriter, null)

                if (!success) {
                    val errorLog = File(logFilePath).readText()
                    logError("Compilation failed for $ruleName:\n$errorLog")
                    continue
                }

                val classFile = File(classOutputDir, "engine/$ruleName.class")
                if (!classFile.exists()) {
                    classFile.parentFile?.mkdirs()
                    val altClassFile = File(classOutputDir, "$ruleName.class")
                    if (altClassFile.exists()) {
                        moveToPackage(altClassFile, ruleName)
                    }
                }

                val finalClassFile = File(classOutputDir, "engine/$ruleName.class")
                if (!finalClassFile.exists()) {
                    logError("Class file not found for $ruleName")
                    continue
                }

                val dexFile = compileToDex(classOutputDir, ruleName)
                if (dexFile == null) {
                    logError("Failed to compile class to dex for $ruleName")
                    continue
                }

                try {
                    val optimizedDir = File(context.codeCacheDir, "optimized").also { it.mkdirs() }
                    val classLoader = DexClassLoader(
                        dexFile.absolutePath,
                        optimizedDir.absolutePath,
                        dexFile.parentFile?.absolutePath,
                        context.classLoader
                    )
                    val ruleClass = classLoader.loadClass("engine.$ruleName")
                    val evaluateMethod = ruleClass.getMethod("evaluate", Intent::class.java, Intent::class.java)
                    val executeMethod = ruleClass.getMethod("execute", Intent::class.java, Intent::class.java)
                    compiledRules[ruleName] = CompiledRuleInfo(dexFile.absolutePath, evaluateMethod, executeMethod)
                    log("Loaded rule: $ruleName from $dexFile")
                } catch (e: Exception) {
                    logError("Failed to load rule $ruleName: ${e.message}")
                }
            }

            log("Compiled ${compiledRules.size} rules")
            return compiledRules.isNotEmpty()
        } catch (e: Exception) {
            logError("Compile failed: ${e.message}")
            return false
        }
    }

    private fun compileToDex(classOutputDir: File, ruleName: String): File? {
        return try {
            val dexOutputDir = File(cacheDir, "dex").also { it.mkdirs() }
            val dexFile = File(dexOutputDir, "classes.dex")

            val classFiles = classOutputDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .toList()

            if (classFiles.isEmpty()) {
                logError("No class files found in $classOutputDir")
                return null
            }

            val jarFile = File(cacheDir, "temp_${ruleName}.jar")
            val jarOutputStream = java.util.jar.JarOutputStream(java.io.FileOutputStream(jarFile))
            classFiles.forEach { file ->
                val entryName = file.relativeTo(classOutputDir).path.replace("\\", "/")
                jarOutputStream.putNextEntry(java.util.jar.JarEntry(entryName))
                jarOutputStream.write(file.readBytes())
                jarOutputStream.closeEntry()
            }
            jarOutputStream.close()

            val compileCmd = arrayOf(
                "--lib", setting.rtJarPath,
                "--output", dexOutputDir.absolutePath,
                "--release",
                jarFile.absolutePath
            )

            log("Running D8: ${compileCmd.joinToString(" ")}")
            com.android.tools.r8.D8.run(
                com.android.tools.r8.D8Command.parse(compileCmd, com.android.tools.r8.origin.Origin.root(), null).build()
            )

            jarFile.delete()

            if (dexFile.exists()) {
                val copyDest = File(context.filesDir, "rule_dex_${System.currentTimeMillis()}.dex")
                dexFile.copyTo(copyDest, overwrite = true)
                dexFile.delete()
                copyDest.setReadOnly()
                copyDest
            } else {
                logError("Dex file not created")
                null
            }
        } catch (e: Exception) {
            logError("D8 compilation failed: ${e.message}")
            null
        }
    }

    private fun moveToPackage(classFile: File, ruleName: String) {
        val engineDir = File(classFile.parentFile, "engine")
        engineDir.mkdirs()
        val targetFile = File(engineDir, "$ruleName.class")
        classFile.copyTo(targetFile, overwrite = true)
        classFile.delete()
    }

    private fun buildRuleTemplate(ruleName: String, conditionCode: String?, actionCode: String?): String {
        val conditionBody = conditionCode?.trim()?.let { code ->
            val processedCode = code.lines().joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.endsWith(";") && !trimmed.endsWith("{") && !trimmed.endsWith("}")) {
                    "$trimmed;"
                } else {
                    trimmed
                }
            }
            """
                public static boolean evaluate(android.content.Intent intent, android.content.Intent result) {
                    $processedCode
                }
            """.trimIndent()
        } ?: """
                public static boolean evaluate(android.content.Intent intent, android.content.Intent result) {
                    return false;
                }
        """.trimIndent()

        val actionBody = actionCode?.trim()?.let { code ->
            val processedCode = code.lines().joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.endsWith(";") && !trimmed.endsWith("{") && !trimmed.endsWith("}")) {
                    "$trimmed;"
                } else {
                    trimmed
                }
            }
            """
                public static void execute(android.content.Intent intent, android.content.Intent result) {
                    $processedCode
                }
            """.trimIndent()
        } ?: """
                public static void execute(android.content.Intent intent, android.content.Intent result) {
                }
        """.trimIndent()

        return """
            package engine;
            
            import android.content.Intent;
            
            public class $ruleName {
                
                $conditionBody
                
                $actionBody
            }
        """.trimIndent()
    }

    fun evaluateAndExecute(intent: Intent, result: Intent): Boolean {
        for ((_, info) in compiledRules) {
            try {
                val evaluateResult = info.evaluateMethod.invoke(null, intent, result) as? Boolean ?: false
                if (evaluateResult) {
                    info.executeMethod.invoke(null, intent, result)
                    return true
                }
            } catch (e: Exception) {
                logError("Rule evaluation/execution failed: ${e.message}")
            }
        }
        return false
    }

    fun isCompiled(): Boolean = compiledRules.isNotEmpty()

    fun getRuleCount(): Int = compiledRules.size

    private fun clearCache() {
        try {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
        } catch (e: Exception) {
            logError("Clear cache failed: ${e.message}")
        }
    }

    fun cleanup() {
        try {
            clearCache()
            compiledRules.clear()
        } catch (e: Exception) {
            logError("Cleanup failed: ${e.message}")
        }
    }
}