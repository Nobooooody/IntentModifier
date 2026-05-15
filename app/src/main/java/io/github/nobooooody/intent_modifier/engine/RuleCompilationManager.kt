package io.github.nobooooody.intent_modifier.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import dalvik.system.DexClassLoader
import io.github.nobooooody.intent_modifier.compiler.JavaEngineSetting
import io.github.nobooooody.intent_modifier.compiler.JavaPrintWriter
import org.eclipse.jdt.internal.compiler.batch.Main
import java.io.File
import java.security.MessageDigest

data class CompiledRuleVersion(
    val version: Long,
    val dexBase64: String,
    val ruleCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)

data class LoadedRuleInfo(
    val evaluateMethod: java.lang.reflect.Method,
    val executeMethod: java.lang.reflect.Method
)

class RuleCompilationManager(private val context: Context) {
    private val TAG = "RuleCompilationManager"
    private val setting = JavaEngineSetting(context)
    
    companion object {
        private const val PREFS_NAME = "intent_modifier_config"
        private const val KEY_COMPILED_VERSION = "compiled_version"
        private const val KEY_COMPILED_DEX = "compiled_dex"
        private const val KEY_RULES_HASH = "rules_hash"
        private const val KEY_RULE_COUNT = "rule_count"
        
        fun computeRulesHash(rules: List<RuleSource>): String {
            val sb = StringBuilder()
            rules.forEach { rule ->
                sb.append(rule.condition ?: "")
                sb.append("|")
                sb.append(rule.action ?: "")
                sb.append("||")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(sb.toString().toByteArray())
            return Base64.encodeToString(hash, Base64.NO_WRAP)
        }
    }
    
    fun compileAndStore(rules: List<RuleSource>, onProgress: ((String) -> Unit)? = null): Boolean {
        try {
            log("Starting compilation of ${rules.size} rules")
            
            if (!setting.ensureAllJarsInstalled()) {
                log("Failed to install required JAR files")
                return false
            }
            
            val cacheDir = File(context.filesDir, "compile_cache").also { it.mkdirs() }
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            
            val classOutputDir = File(cacheDir, "classes").also { it.mkdirs() }
            
            for ((index, rule) in rules.withIndex()) {
                val ruleName = "Rule_$index"
                val sourceCode = buildRuleTemplate(ruleName, rule.condition, rule.action)
                val sourceFile = File(cacheDir, "$ruleName.java")
                sourceFile.writeText(sourceCode)
                
                onProgress?.invoke("Compiling $ruleName...")
                
                val logFilePath = setting.createAndCleanFile(File(cacheDir, "compile_$ruleName.log"))
                val printWriter = JavaPrintWriter(logFilePath)
                
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
                printWriter.close()
                
                if (!success) {
                    val errorLog = File(logFilePath).readText()
                    log("Compilation failed for $ruleName:\n$errorLog")
                    return false
                }
                
                val classFile = File(classOutputDir, "engine/$ruleName.class")
                if (!classFile.exists()) {
                    val altClassFile = File(classOutputDir, "$ruleName.class")
                    if (altClassFile.exists()) {
                        val engineDir = File(classOutputDir, "engine").also { it.mkdirs() }
                        altClassFile.copyTo(File(engineDir, "$ruleName.class"), overwrite = true)
                        altClassFile.delete()
                    }
                }
            }
            
            onProgress?.invoke("Converting to DEX...")
            val dexFile = convertToDex(classOutputDir, cacheDir)
            if (dexFile == null || !dexFile.exists()) {
                log("Failed to create DEX file")
                return false
            }
            
            val dexBytes = dexFile.readBytes()
            val dexBase64 = Base64.encodeToString(dexBytes, Base64.NO_WRAP)
            val version = System.currentTimeMillis()
            val rulesHash = computeRulesHash(rules)
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE or Context.MODE_MULTI_PROCESS)
            prefs.edit()
                .putLong(KEY_COMPILED_VERSION, version)
                .putString(KEY_COMPILED_DEX, dexBase64)
                .putString(KEY_RULES_HASH, rulesHash)
                .putInt(KEY_RULE_COUNT, rules.size)
                .apply()
            
            log("Saved compiled DEX: ${dexBytes.size} bytes, version=$version, rules=${rules.size}")
            return true
            
        } catch (e: Exception) {
            log("Compilation failed: ${e.message}")
            return false
        }
    }
    
    fun getStoredVersion(): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE or Context.MODE_MULTI_PROCESS)
        return prefs.getLong(KEY_COMPILED_VERSION, 0)
    }
    
    fun getStoredRulesHash(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE or Context.MODE_MULTI_PROCESS)
        return prefs.getString(KEY_RULES_HASH, null)
    }
    
    fun getCompiledDexBytes(): ByteArray? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE or Context.MODE_MULTI_PROCESS)
        val base64 = prefs.getString(KEY_COMPILED_DEX, null) ?: return null
        return Base64.decode(base64, Base64.NO_WRAP)
    }
    
    private fun convertToDex(classOutputDir: File, cacheDir: File): File? {
        return try {
            val classFiles = classOutputDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .toList()
            
            if (classFiles.isEmpty()) {
                log("No class files found")
                return null
            }
            
            val jarFile = File(cacheDir, "temp_rules.jar")
            val jarOut = java.util.jar.JarOutputStream(java.io.FileOutputStream(jarFile))
            classFiles.forEach { file ->
                val entryName = file.relativeTo(classOutputDir).path.replace("\\", "/")
                jarOut.putNextEntry(java.util.jar.JarEntry(entryName))
                jarOut.write(file.readBytes())
                jarOut.closeEntry()
            }
            jarOut.close()
            
            val dexOutputDir = File(cacheDir, "dex").also { it.mkdirs() }
            val dexFile = File(dexOutputDir, "classes.dex")
            
            val compileCmd = arrayOf(
                "--lib", setting.rtJarPath,
                "--output", dexOutputDir.absolutePath,
                "--release",
                jarFile.absolutePath
            )
            
            log("Running D8...")
            com.android.tools.r8.D8.run(
                com.android.tools.r8.D8Command.parse(compileCmd, com.android.tools.r8.origin.Origin.root(), null).build()
            )
            
            jarFile.delete()
            
            if (!dexFile.exists()) {
                log("D8 failed to produce dex")
                return null
            }
            
            dexFile
        } catch (e: Exception) {
            log("D8 conversion failed: ${e.message}")
            null
        }
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
    
    private fun log(msg: String) {
        Log.i(TAG, msg)
    }
}

class RuleLoader(private val context: Context) {
    private val TAG = "RuleLoader"
    private var loadedRules: List<LoadedRuleInfo>? = null
    private var classLoader: DexClassLoader? = null
    private var loadedVersion: Long = 0
    private var loadedRuleCount: Int = 0
    
    fun loadRulesFromPrefs(parentClassLoader: ClassLoader): Boolean {
        try {
            val prefs = context.getSharedPreferences("intent_modifier_config", Context.MODE_WORLD_READABLE or Context.MODE_MULTI_PROCESS)
            val version = prefs.getLong("compiled_version", 0)
            val dexBase64 = prefs.getString("compiled_dex", null) ?: return false
            val ruleCount = prefs.getString("rule_count", "0")?.toIntOrNull() ?: 0
            
            if (version == 0L || dexBase64.isEmpty()) {
                Log.w(TAG, "No compiled rules found in prefs")
                return false
            }
            
            if (version == loadedVersion && loadedRules != null) {
                Log.i(TAG, "Using cached rules version=$version")
                return true
            }
            
            Log.i(TAG, "Loading compiled rules version=$version, count=$ruleCount")
            
            val dexBytes = android.util.Base64.decode(dexBase64, android.util.Base64.NO_WRAP)
            
            val cacheDir = File(context.filesDir, "loaded_rules")
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            
            val dexFile = File(cacheDir, "rules.dex")
            dexFile.writeBytes(dexBytes)
            dexFile.setReadOnly()
            
            val optimizedDir = File(context.codeCacheDir, "optimized").also { it.mkdirs() }
            val dexClassLoader = DexClassLoader(
                dexFile.absolutePath,
                optimizedDir.absolutePath,
                dexFile.parentFile?.absolutePath,
                parentClassLoader
            )
            
            val rules = mutableListOf<LoadedRuleInfo>()
            for (i in 0 until ruleCount) {
                try {
                    val ruleClass = dexClassLoader.loadClass("engine.Rule_$i")
                    val evaluateMethod = ruleClass.getMethod("evaluate", android.content.Intent::class.java, android.content.Intent::class.java)
                    val executeMethod = ruleClass.getMethod("execute", android.content.Intent::class.java, android.content.Intent::class.java)
                    rules.add(LoadedRuleInfo(evaluateMethod, executeMethod))
                    Log.i(TAG, "Loaded Rule_$i")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load Rule_$i: ${e.message}")
                }
            }
            
            loadedRules = rules
            classLoader = dexClassLoader
            loadedVersion = version
            loadedRuleCount = ruleCount
            
            Log.i(TAG, "Successfully loaded ${rules.size} rules")
            return rules.isNotEmpty()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rules: ${e.message}")
            return false
        }
    }
    
    fun evaluateAndExecute(intent: android.content.Intent, result: android.content.Intent): Boolean {
        val rules = loadedRules ?: return false
        for (rule in rules) {
            try {
                val evalResult = rule.evaluateMethod.invoke(null, intent, result) as? Boolean ?: false
                if (evalResult) {
                    rule.executeMethod.invoke(null, intent, result)
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rule evaluation failed: ${e.message}")
            }
        }
        return false
    }
    
    fun hasRules(): Boolean = loadedRules?.isNotEmpty() == true
    
    fun getRuleCount(): Int = loadedRuleCount
}