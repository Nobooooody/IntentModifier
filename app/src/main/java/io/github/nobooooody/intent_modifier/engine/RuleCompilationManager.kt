package io.github.nobooooody.intent_modifier.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import dalvik.system.DexClassLoader
import io.github.nobooooody.intent_modifier.compiler.JavaEngineSetting
import io.github.nobooooody.intent_modifier.compiler.JavaPrintWriter
import io.github.nobooooody.intent_modifier.data.JavaCodeRule
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import io.github.nobooooody.intent_modifier.data.NormalRule
import org.eclipse.jdt.internal.compiler.batch.Main
import java.io.File
import java.security.MessageDigest

data class LoadedRuleInfo(
    val evaluateMethod: java.lang.reflect.Method,
    val executeMethod: java.lang.reflect.Method
)

data class RuleSource(
    val condition: String?,
    val action: String?,
    val imports: String = "",
    val members: String = ""
)

data class CompilationResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val errorRuleName: String? = null
)

class RuleCompilationManager(private val context: Context) {
    private val TAG = "RuleCompilationManager"
    private val setting = JavaEngineSetting(context)
    private val repo = ModifierRepository(context)

    companion object {
        private const val KEY_COMPILED_VERSION = "compiled_version"
        private const val KEY_COMPILED_DEX = "compiled_dex"
        private const val KEY_RULES_HASH = "rules_hash"
        private const val KEY_RULE_COUNT = "rule_count"
    }

    // ─── 新编译入口：编译所有规则（JavaCodeRule + NormalRule）─────────────────────

    fun compileAllRules(
        javaRules: List<JavaCodeRule>,
        normalRules: List<NormalRule>,
        onProgress: ((String) -> Unit)? = null
    ): CompilationResult {
        try {
            log("Starting compilation: ${javaRules.size} JavaCodeRule + ${normalRules.size} NormalRule")

            if (!setting.ensureAllJarsInstalled()) {
                log("Failed to install required JAR files")
                return CompilationResult(false, "Failed to install required JAR files")
            }

            val cacheDir = File(context.filesDir, "compile_cache").also { it.mkdirs() }
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()

            val classOutputDir = File(cacheDir, "classes").also { it.mkdirs() }

            val ruleEntries = mutableListOf<RuleEntry>()

            // 编译 JavaCodeRule
            for (rule in javaRules) {
                if (!rule.enabled) continue
                val prefix = rule.id.take(8)
                val ruleName = "Rule_$prefix"
                val sourceCode = buildJavaCodeTemplate(ruleName, rule)
                val sourceFile = File(cacheDir, "$ruleName.java")
                sourceFile.writeText(sourceCode)

                onProgress?.invoke("Compiling $ruleName...")
                if (!compileSingleSource(sourceFile, ruleName, classOutputDir, cacheDir)) {
                    val errorLog = File(cacheDir, "compile_$ruleName.log").readText()
                    log("Compilation failed for $ruleName:\n$errorLog")
                    return CompilationResult(false, errorLog, ruleName)
                }

                val classFile = findClassFile(classOutputDir, ruleName)
                if (classFile == null) {
                    log("Class file not found for $ruleName")
                    return CompilationResult(false, "Class file not found for $ruleName", ruleName)
                }

                ruleEntries.add(RuleEntry(rule.id, ruleName, classFile, rule.targetPackages, rule.priority))
            }

            // 编译 NormalRule
            for (rule in normalRules) {
                if (!rule.enabled) continue
                val prefix = rule.id.take(8)
                val ruleName = "Rule_$prefix"
                val sourceCode = buildNormalTemplate(ruleName, rule)
                val sourceFile = File(cacheDir, "$ruleName.java")
                sourceFile.writeText(sourceCode)

                onProgress?.invoke("Compiling $ruleName...")
                if (!compileSingleSource(sourceFile, ruleName, classOutputDir, cacheDir)) {
                    val errorLog = File(cacheDir, "compile_$ruleName.log").readText()
                    log("Compilation failed for $ruleName:\n$errorLog")
                    return CompilationResult(false, errorLog, ruleName)
                }

                val classFile = findClassFile(classOutputDir, ruleName)
                if (classFile == null) {
                    log("Class file not found for $ruleName")
                    return CompilationResult(false, "Class file not found for $ruleName", ruleName)
                }

                ruleEntries.add(RuleEntry(rule.id, ruleName, classFile, rule.targetPackages, rule.priority))
            }

            if (ruleEntries.isEmpty()) {
                log("No enabled rules to compile")
                return CompilationResult(false, "No enabled rules to compile")
            }

            // 按 targetPackages 分组
            val sharedEntries = ruleEntries.filter { it.targetPackages.isEmpty() }
            val appGroups = mutableMapOf<String, MutableList<RuleEntry>>()
            for (entry in ruleEntries) {
                for (pkg in entry.targetPackages) {
                    val sanitized = repo.sanitizePackageName(pkg)
                    appGroups.getOrPut(sanitized) { mutableListOf() }.add(entry)
                }
            }

            val version = System.currentTimeMillis()
            onProgress?.invoke("Converting to DEX...")

            // 编译 shared DEX
            val sharedDex = if (sharedEntries.isNotEmpty()) {
                buildGroupDex(sharedEntries, classOutputDir, cacheDir, "shared")?.let { dexFile ->
                    repo.saveSharedDex(Base64.encodeToString(dexFile.readBytes(), Base64.NO_WRAP))
                    true
                } ?: run {
                    log("Failed to create shared DEX")
                    return CompilationResult(false, "Failed to create shared DEX")
                }
            } else {
                false
            }

            // 编译 per-app DEX
            for ((sanitizedPkg, entries) in appGroups) {
                val registryName = "RuleRegistry_app_$sanitizedPkg"
                onProgress?.invoke("Building $registryName")
                buildGroupDex(entries, classOutputDir, cacheDir, "app_$sanitizedPkg")?.let { dexFile ->
                    repo.saveAppDex(sanitizedPkg, Base64.encodeToString(dexFile.readBytes(), Base64.NO_WRAP))
                } ?: run {
                    log("Failed to create app DEX for $sanitizedPkg")
                    return CompilationResult(false, "Failed to create app DEX for $sanitizedPkg")
                }
            }

            // 存储版本号
            repo.saveVersion(version)

            log("Compilation complete: version=$version, shared=${sharedEntries.size} rules, ${appGroups.size} app groups")
            return CompilationResult(true)

        } catch (e: Exception) {
            log("Compilation failed: ${e.message}")
            return CompilationResult(false, e.message ?: "Unknown error")
        }
    }

    // ─── 旧版编译入口（仅供测试编译/向后兼容）────────────────────────────────────

    fun compileAndStore(rules: List<RuleSource>, onProgress: ((String) -> Unit)? = null): CompilationResult {
        val javaRules = rules.mapIndexed { index, src ->
            JavaCodeRule(
                id = "test_$index",
                enabled = true,
                name = "Test_$index",
                condition = src.condition ?: "",
                action = src.action ?: "",
                imports = src.imports,
                members = src.members
            )
        }
        return compileAllRules(javaRules, emptyList(), onProgress)
    }

    // ─── 辅助方法 ───────────────────────────────────────────────────────────────

    private fun compileSingleSource(sourceFile: File, ruleName: String, classOutputDir: File, cacheDir: File): Boolean {
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
        return success
    }

    private fun findClassFile(classOutputDir: File, ruleName: String): File? {
        val classFile = File(classOutputDir, "engine/$ruleName.class")
        if (classFile.exists()) return classFile
        val altClassFile = File(classOutputDir, "$ruleName.class")
        if (altClassFile.exists()) {
            val engineDir = File(classOutputDir, "engine").also { it.mkdirs() }
            altClassFile.copyTo(File(engineDir, "$ruleName.class"), overwrite = true)
            altClassFile.delete()
            return File(engineDir, "$ruleName.class")
        }
        return null
    }

    private fun buildGroupDex(
        entries: List<RuleEntry>,
        classOutputDir: File,
        cacheDir: File,
        groupName: String
    ): File? {
        return try {
            // 收集该组的所有 class 文件
            val classFiles = entries.map { it.classFile }.toMutableList()

            // 生成 RuleRegistry
            val registryJava = buildRegistrySource(groupName, entries)
            val registryFile = File(cacheDir, "RuleRegistry_$groupName.java")
            registryFile.writeText(registryJava)

            // 编译 RuleRegistry
            if (!compileSingleSource(registryFile, "RuleRegistry_$groupName", classOutputDir, cacheDir)) {
                log("Failed to compile RuleRegistry_$groupName")
                return null
            }

            val registryClass = findClassFile(classOutputDir, "RuleRegistry_$groupName")
            if (registryClass != null) {
                classFiles.add(registryClass)
            }

            // 打包为 JAR -> D8
            val jarFile = File(cacheDir, "rules_$groupName.jar")
            val jarOut = java.util.jar.JarOutputStream(java.io.FileOutputStream(jarFile))
            classFiles.forEach { file ->
                val entryName = file.relativeTo(classOutputDir).path.replace("\\", "/")
                jarOut.putNextEntry(java.util.jar.JarEntry(entryName))
                jarOut.write(file.readBytes())
                jarOut.closeEntry()
            }
            jarOut.close()

            log("Created JAR for $groupName: ${jarFile.length()} bytes, ${classFiles.size} classes")

            val dexOutputDir = File(cacheDir, "dex_$groupName").also { it.mkdirs() }
            val dexFile = File(dexOutputDir, "classes.dex")

            val builder = D8Command.builder()
            builder.addProgramFiles(jarFile.toPath())
            builder.setOutput(dexOutputDir.toPath(), OutputMode.DexIndexed)
            D8.run(builder.build())

            jarFile.delete()

            if (!dexFile.exists()) {
                log("D8 failed for $groupName. Files: ${dexOutputDir.listFiles()?.map { it.name }}")
                return null
            }

            log("DEX created for $groupName: ${dexFile.length()} bytes")
            dexFile
        } catch (e: Exception) {
            log("D8 conversion failed for $groupName: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // ─── 模板生成 ───────────────────────────────────────────────────────────────

    private fun buildJavaCodeTemplate(ruleName: String, rule: JavaCodeRule): String {
        val importsSection = processCodeBlock(rule.imports)
        val membersSection = processCodeBlock(rule.members)
        val conditionBody = processCondition(rule.condition)
        val actionBody = processAction(rule.action)

        val baseImports = "import android.content.Context;\nimport android.content.Intent;"
        val allImports = if (importsSection.isNotEmpty()) {
            "$baseImports\n$importsSection"
        } else {
            baseImports
        }

        return """
            package engine;

            $allImports

            public class $ruleName {

                $membersSection

                $conditionBody

                $actionBody
            }
        """.trimIndent()
    }

    private fun buildNormalTemplate(ruleName: String, rule: NormalRule): String {
        val esc: (String?) -> String = { it?.replace("\"", "\\\"") ?: "" }

        return """
            package engine;

            import android.content.Context;
            import android.content.Intent;
            import android.net.Uri;
            import android.os.Bundle;

            public class $ruleName {

                private static final String MATCH_PKG = "${esc(rule.matchPackage)}";
                private static final String MATCH_ACTION = "${esc(rule.matchAction)}";
                private static final String MATCH_DATA = "${esc(rule.matchData)}";
                private static final String MATCH_CLASS = "${esc(rule.matchClass)}";
                private static final String MATCH_TYPE = "${esc(rule.matchType)}";

                private static final String CUSTOM_ACTION = "${esc(rule.customAction)}";
                private static final String CUSTOM_DATA = "${esc(rule.customData)}";
                private static final String CUSTOM_PKG = "${esc(rule.customPackage)}";
                private static final String CUSTOM_CLASS = "${esc(rule.customClass)}";
                private static final int CUSTOM_FLAGS = ${rule.customFlags ?: 0};
                private static final String CUSTOM_TYPE = "${esc(rule.customType)}";
                private static final boolean BLOCK_SUBSEQUENT = ${rule.blockSubsequent};

                public static boolean evaluate(Context ctx, Intent intent, Intent result) {
                    if (intent.getComponent() == null) return false;
                    String pkg = intent.getComponent().getPackageName();
                    if (pkg == null) return false;

                    boolean matches = true;
                    if (!MATCH_PKG.isEmpty())
                        matches = matches && pkg.equals(MATCH_PKG);
                    if (!MATCH_ACTION.isEmpty())
                        matches = matches && MATCH_ACTION.equals(intent.getAction());
                    if (!MATCH_CLASS.isEmpty())
                        matches = matches && MATCH_CLASS.equals(intent.getComponent().getClassName());
                    if (!MATCH_DATA.isEmpty() && intent.getData() != null)
                        matches = matches && MATCH_DATA.equals(intent.getData().toString());
                    if (!MATCH_TYPE.isEmpty())
                        matches = matches && MATCH_TYPE.equals(intent.getType());
                    return matches;
                }

                public static boolean execute(Context ctx, Intent intent, Intent result) {
                    if (!CUSTOM_ACTION.isEmpty()) result.setAction(CUSTOM_ACTION);
                    if (!CUSTOM_DATA.isEmpty()) result.setData(Uri.parse(CUSTOM_DATA));
                    if (!CUSTOM_PKG.isEmpty() && !CUSTOM_CLASS.isEmpty())
                        result.setClassName(CUSTOM_PKG, CUSTOM_CLASS);
                    if (CUSTOM_FLAGS != 0) result.addFlags(CUSTOM_FLAGS);
                    if (!CUSTOM_TYPE.isEmpty()) result.setType(CUSTOM_TYPE);
                    return BLOCK_SUBSEQUENT;
                }
            }
        """.trimIndent()
    }

    private fun buildRegistrySource(groupName: String, entries: List<RuleEntry>): String {
        val entriesCode = entries.joinToString(",\n            ") { entry ->
            """{"engine.${entry.className}", ${entry.priority}}"""
        }

        return """
            package engine;

            public class RuleRegistry_$groupName {
                public static Object[][] getRules() {
                    return new Object[][]{
                        $entriesCode
                    };
                }
            }
        """.trimIndent()
    }

    // ─── 代码块处理 ─────────────────────────────────────────────────────────────

    private fun processCodeBlock(code: String): String {
        return code.trim().lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.endsWith(";") && !trimmed.endsWith("{") && !trimmed.endsWith("}")) {
                "$trimmed;"
            } else {
                trimmed
            }
        }
    }

    private fun processCondition(condition: String?): String {
        val code = condition?.trim()
        if (code.isNullOrEmpty()) {
            return """
                public static boolean evaluate(Context ctx, Intent intent, Intent result) {
                    return false;
                }
            """.trimIndent()
        }
        val processed = code.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.endsWith(";") && !trimmed.endsWith("{") && !trimmed.endsWith("}")) {
                "$trimmed;"
            } else {
                trimmed
            }
        }
        return """
            public static boolean evaluate(Context ctx, Intent intent, Intent result) {
                $processed
            }
        """.trimIndent()
    }

    private fun processAction(action: String?): String {
        val code = action?.trim()
        if (code.isNullOrEmpty()) {
            return """
                public static boolean execute(Context ctx, Intent intent, Intent result) {
                    return false;
                }
            """.trimIndent()
        }
        val processed = code.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.endsWith(";") && !trimmed.endsWith("{") && !trimmed.endsWith("}")) {
                "$trimmed;"
            } else {
                trimmed
            }
        }
        return """
            public static boolean execute(Context ctx, Intent intent, Intent result) {
                $processed
            }
        """.trimIndent()
    }

    // ─── 读取方法 ───────────────────────────────────────────────────────────────

    fun getStoredVersion(): Long = repo.getCompiledVersion()

    fun getStoredRulesHash(): String? {
        val prefs = context.getSharedPreferences("intent_modifier_config", Context.MODE_PRIVATE)
        return prefs.getString(KEY_RULES_HASH, null)
    }

    fun getCompiledDexBytes(): ByteArray? {
        val base64 = repo.getSharedDex() ?: repo.getCompiledDex() ?: return null
        return Base64.decode(base64, Base64.NO_WRAP)
    }

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

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    private data class RuleEntry(
        val id: String,
        val className: String,
        val classFile: File,
        val targetPackages: List<String>,
        val priority: Int
    )
}

// ─── RuleLoader（Xposed 端用，Stage 4 完善）───────────────────────────────────

class RuleLoader(private val context: Context) {
    private val TAG = "RuleLoader"
    private var loadedRules: List<LoadedRuleInfo>? = null
    private var loadedVersion: Long = 0

    fun loadRulesFromPrefs(parentClassLoader: ClassLoader): Boolean {
        try {
            val prefs = context.getSharedPreferences("intent_modifier_config", Context.MODE_WORLD_READABLE or Context.MODE_MULTI_PROCESS)
            val version = prefs.getLong("compiled_version", 0)
            val sharedDex = prefs.getString("shared_dex", null)

            if (version == 0L || sharedDex.isNullOrEmpty()) {
                Log.w(TAG, "No compiled rules found")
                return false
            }

            if (version == loadedVersion && loadedRules != null) {
                Log.i(TAG, "Using cached rules version=$version")
                return true
            }

            Log.i(TAG, "Loading compiled rules version=$version")

            val dexBytes = android.util.Base64.decode(sharedDex, android.util.Base64.NO_WRAP)

            val cacheDir = File(context.filesDir, "loaded_rules")
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()

            val dexFile = File(cacheDir, "rules_shared.dex")
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
            try {
                val registryClass = dexClassLoader.loadClass("engine.RuleRegistry_shared")
                val allRules = registryClass.getMethod("getRules").invoke(null) as Array<Array<Any>>
                for (entry in allRules) {
                    try {
                        val className = entry[0] as String
                        val ruleClass = dexClassLoader.loadClass(className)
                        val evaluateMethod = ruleClass.getMethod("evaluate", android.content.Context::class.java, android.content.Intent::class.java, android.content.Intent::class.java)
                        val executeMethod = ruleClass.getMethod("execute", android.content.Context::class.java, android.content.Intent::class.java, android.content.Intent::class.java)
                        rules.add(LoadedRuleInfo(evaluateMethod, executeMethod))
                        Log.i(TAG, "Loaded $className")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load rule ${entry[0]}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RuleRegistry not found, falling back to sequential: ${e.message}")
                // 兼容：逐条规则加载（暂不做）
            }

            loadedRules = rules
            loadedVersion = version

            Log.i(TAG, "Successfully loaded ${rules.size} rules")
            return rules.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rules: ${e.message}")
            return false
        }
    }

    fun evaluateAndExecute(intent: android.content.Intent, result: android.content.Intent): Boolean {
        val rules = loadedRules ?: return false
        var executed = false
        for (rule in rules) {
            try {
                val evalResult = rule.evaluateMethod.invoke(null, null as Any?, intent, result) as? Boolean ?: false
                if (evalResult) {
                    val shouldBlock = rule.executeMethod.invoke(null, null as Any?, intent, result) as? Boolean ?: true
                    executed = true
                    if (shouldBlock) break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rule evaluation failed: ${e.message}")
            }
        }
        return executed
    }

    fun hasRules(): Boolean = loadedRules?.isNotEmpty() == true
}
