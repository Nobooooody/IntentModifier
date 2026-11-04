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
import java.util.UUID

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

data class CompiledArtifact(
    val version: Long,
    val runDir: File,
    val sharedDex: File,
    val appDexFiles: Map<String, File>,
    val ruleCount: Int
)

sealed interface CompileOutcome {
    data class Success(val artifact: CompiledArtifact) : CompileOutcome
    data class Failure(val message: String, val ruleName: String? = null) : CompileOutcome
}

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

    // ─── 编译入口：compile 只产出 artifact，publish 才落 SharedPreferences ────────

    @Synchronized
    fun compile(
        javaRules: List<JavaCodeRule>,
        normalRules: List<NormalRule>,
        onProgress: ((String) -> Unit)? = null
    ): CompileOutcome {
        val runsDir = File(context.filesDir, "compile_runs").also { it.mkdirs() }
        val runDir = File(runsDir, UUID.randomUUID().toString()).also { it.mkdirs() }

        fun fail(message: String, ruleName: String? = null): CompileOutcome {
            log(message)
            discardRun(runDir)
            return CompileOutcome.Failure(message, ruleName)
        }

        try {
            log("Starting compilation: ${javaRules.size} JavaCodeRule + ${normalRules.size} NormalRule")

            if (!setting.ensureAllJarsInstalled()) {
                return fail("Failed to install required JAR files")
            }

            val classOutputDir = File(runDir, "classes").also { it.mkdirs() }
            val ruleEntries = mutableListOf<RuleEntry>()

            // 编译 JavaCodeRule
            for (rule in javaRules) {
                if (!rule.enabled) continue
                val prefix = rule.id.take(8)
                val ruleName = "Rule_$prefix"
                val sourceCode = buildJavaCodeTemplate(ruleName, rule)
                val sourceFile = File(runDir, "$ruleName.java")
                sourceFile.writeText(sourceCode)

                onProgress?.invoke("Compiling $ruleName...")
                if (!compileSingleSource(sourceFile, ruleName, classOutputDir, runDir)) {
                    val errorLog = readCompileLog(runDir, ruleName)
                    log("Compilation failed for $ruleName:\n$errorLog")
                    return fail(errorLog, ruleName)
                }

                val classFile = findClassFile(classOutputDir, ruleName)
                if (classFile == null) {
                    return fail("Class file not found for $ruleName", ruleName)
                }

                ruleEntries.add(RuleEntry(rule.id, rule.name, ruleName, classFile, rule.targetPackages, rule.priority))
            }

            // 编译 NormalRule
            for (rule in normalRules) {
                if (!rule.enabled) continue
                val prefix = rule.id.take(8)
                val ruleName = "Rule_$prefix"
                val sourceCode = buildNormalTemplate(ruleName, rule)
                val sourceFile = File(runDir, "$ruleName.java")
                sourceFile.writeText(sourceCode)

                onProgress?.invoke("Compiling $ruleName...")
                if (!compileSingleSource(sourceFile, ruleName, classOutputDir, runDir)) {
                    val errorLog = readCompileLog(runDir, ruleName)
                    log("Compilation failed for $ruleName:\n$errorLog")
                    return fail(errorLog, ruleName)
                }

                val classFile = findClassFile(classOutputDir, ruleName)
                if (classFile == null) {
                    return fail("Class file not found for $ruleName", ruleName)
                }

                ruleEntries.add(RuleEntry(rule.id, rule.name, ruleName, classFile, rule.targetPackages, rule.priority))
            }

            val appGroups = mutableMapOf<String, MutableList<RuleEntry>>()
            val sharedEntries: List<RuleEntry>
            if (ruleEntries.isEmpty()) {
                log("No enabled rules to compile, generating empty DEX")
                sharedEntries = emptyList()
            } else {
                sharedEntries = ruleEntries.filter { it.targetPackages.isEmpty() }
                for (entry in ruleEntries) {
                    for (pkg in entry.targetPackages) {
                        val sanitized = repo.sanitizePackageName(pkg)
                        appGroups.getOrPut(sanitized) { mutableListOf() }.add(entry)
                    }
                }
            }

            val version = System.currentTimeMillis()
            onProgress?.invoke("Converting to DEX...")

            // 编译 shared DEX（空规则集也会生成只含空 registry 的 DEX）
            val sharedDex = buildGroupDex(sharedEntries, classOutputDir, runDir, "shared")
                ?: return fail("Failed to create shared DEX")

            // 编译 per-app DEX
            val appDexFiles = linkedMapOf<String, File>()
            for ((sanitizedPkg, entries) in appGroups) {
                val registryName = "RuleRegistry_app_$sanitizedPkg"
                onProgress?.invoke("Building $registryName")
                val dexFile = buildGroupDex(entries, classOutputDir, runDir, "app_$sanitizedPkg")
                    ?: return fail("Failed to create app DEX for $sanitizedPkg")
                appDexFiles[sanitizedPkg] = dexFile
            }

            log("Compilation staged: version=$version, ${ruleEntries.size} rules, ${appGroups.size} app groups")
            return CompileOutcome.Success(
                CompiledArtifact(
                    version = version,
                    runDir = runDir,
                    sharedDex = sharedDex,
                    appDexFiles = appDexFiles,
                    ruleCount = ruleEntries.size
                )
            )
        } catch (e: Exception) {
            log("Compilation failed: ${e.message}")
            discardRun(runDir)
            return CompileOutcome.Failure(e.message ?: "Unknown error")
        }
    }

    @Synchronized
    fun publish(artifact: CompiledArtifact): CompilationResult {
        return try {
            repo.saveSharedDex(Base64.encodeToString(artifact.sharedDex.readBytes(), Base64.NO_WRAP))

            for ((sanitizedPkg, dexFile) in artifact.appDexFiles) {
                repo.saveAppDex(sanitizedPkg, Base64.encodeToString(dexFile.readBytes(), Base64.NO_WRAP))
            }

            val stalePackages = repo.getAllAppDexPackages() - artifact.appDexFiles.keys
            for (stalePkg in stalePackages) {
                log("Removing stale app DEX for $stalePkg")
                repo.removeAppDex(stalePkg)
            }

            // 版本号最后写：只有完整依赖全部落盘后才对外可见
            repo.saveVersion(artifact.version)

            log(
                "Compilation published: version=${artifact.version}, ${artifact.ruleCount} rules, " +
                    "${artifact.appDexFiles.size} app groups, ${stalePackages.size} stale removed"
            )
            CompilationResult(true)
        } catch (e: Exception) {
            log("Publish failed: ${e.message}")
            CompilationResult(false, e.message ?: "Publish failed")
        } finally {
            discardRun(artifact.runDir)
        }
    }

    @Synchronized
    fun discard(artifact: CompiledArtifact) {
        discardRun(artifact.runDir)
    }

    /**
     * 兼容旧调用方：编译成功后立即发布。
     * 测试编译不要用这个方法，应改用 compile() + discard()。
     */
    @Synchronized
    fun compileAllRules(
        javaRules: List<JavaCodeRule>,
        normalRules: List<NormalRule>,
        onProgress: ((String) -> Unit)? = null
    ): CompilationResult {
        return when (val outcome = compile(javaRules, normalRules, onProgress)) {
            is CompileOutcome.Failure -> CompilationResult(false, outcome.message, outcome.ruleName)
            is CompileOutcome.Success -> publish(outcome.artifact)
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

    private fun readCompileLog(runDir: File, ruleName: String): String {
        return File(runDir, "compile_$ruleName.log")
            .takeIf { it.exists() }
            ?.readText()
            ?: "Compilation failed for $ruleName"
    }

    private fun discardRun(runDir: File) {
        runCatching { runDir.deleteRecursively() }
    }

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

        val matchCategoriesArray = rule.matchCategories?.filter { it.isNotBlank() }?.joinToString(", ") { "\"${esc(it)}\"" }
            ?.let { "{ $it }" } ?: "{}"

        val customCategoriesArray = rule.customCategories?.filter { it.isNotBlank() }?.joinToString(", ") { "\"${esc(it)}\"" }
            ?.let { "{ $it }" } ?: "{}"

        val extrasCode = rule.extras?.filter { it.key.isNotBlank() }?.joinToString("\n                    ") { extra ->
            val key = esc(extra.key)
            val value = extra.values.firstOrNull() ?: ""
            when (extra.type) {
                "null" -> "result.removeExtra(\"$key\");"
                "boolean" -> "result.putExtra(\"$key\", ${value.toBooleanStrictOrNull() ?: true});"
                "integer" -> {
                    if (value.isBlank()) "// skip empty integer extra \"$key\""
                    else {
                        val intVal = value.toIntOrNull()
                        if (intVal != null) "result.putExtra(\"$key\", $intVal);"
                        else "// skip invalid integer extra \"$key\""
                    }
                }
                "long" -> {
                    if (value.isBlank()) "// skip empty long extra \"$key\""
                    else {
                        val longVal = value.toLongOrNull()
                        if (longVal != null) "result.putExtra(\"$key\", ${longVal}L);"
                        else "// skip invalid long extra \"$key\""
                    }
                }
                "decimal" -> {
                    if (value.isBlank()) "// skip empty decimal extra \"$key\""
                    else {
                        val doubleVal = value.toDoubleOrNull()
                        if (doubleVal != null) "result.putExtra(\"$key\", $doubleVal);"
                        else "// skip invalid decimal extra \"$key\""
                    }
                }
                "uri" -> {
                    if (value.isBlank()) "// skip empty uri extra \"$key\""
                    else "result.putExtra(\"$key\", Uri.parse(\"${esc(value)}\"));"
                }
                "component" -> {
                    if (value.isBlank()) "// skip empty component extra \"$key\""
                    else {
                        val comp = try { android.content.ComponentName.unflattenFromString(value) } catch (e: Exception) { null }
                        if (comp != null) {
                            "result.putExtra(\"$key\", ComponentName.unflattenFromString(\"${esc(value)}\"));"
                        } else {
                            "// invalid component extra \"$key\": \"${esc(value)}\""
                        }
                    }
                }
                else -> "result.putExtra(\"$key\", \"${esc(value)}\");"
            }
        } ?: ""

        return """
            package engine;

            import android.content.ComponentName;
            import android.content.Context;
            import android.content.Intent;
            import android.net.Uri;

            public class $ruleName {

                private static final String MATCH_PKG = "${esc(rule.matchPackage)}";
                private static final String MATCH_ACTION = "${esc(rule.matchAction)}";
                private static final String MATCH_DATA = "${esc(rule.matchData)}";
                private static final String MATCH_CLASS = "${esc(rule.matchClass)}";
                private static final String MATCH_TYPE = "${esc(rule.matchType)}";
                private static final String[] MATCH_CATEGORIES = $matchCategoriesArray;

                private static final String CUSTOM_ACTION = "${esc(rule.customAction)}";
                private static final String CUSTOM_DATA = "${esc(rule.customData)}";
                private static final String CUSTOM_PKG = "${esc(rule.customPackage)}";
                private static final String CUSTOM_CLASS = "${esc(rule.customClass)}";
                private static final int CUSTOM_FLAGS = ${rule.customFlags ?: 0};
                private static final String CUSTOM_TYPE = "${esc(rule.customType)}";
                private static final String[] CUSTOM_CATEGORIES = $customCategoriesArray;
                private static final boolean REPLACE_CATEGORIES = ${rule.replaceCategories};
                private static final boolean REPLACE_EXTRAS = ${rule.replaceExtras};
                private static final boolean BLOCK_SUBSEQUENT = ${rule.blockSubsequent};

                public static boolean evaluate(Context ctx, Intent intent, Intent result) {
                    String pkg = null;
                    if (intent.getComponent() != null) {
                        pkg = intent.getComponent().getPackageName();
                    }
                    if (pkg == null) pkg = intent.getPackage();

                    String cls = intent.getComponent() != null ? intent.getComponent().getClassName() : null;

                    boolean matches = true;
                    if (!MATCH_PKG.isEmpty())
                        matches = matches && pkg != null && pkg.equals(MATCH_PKG);
                    if (!MATCH_ACTION.isEmpty())
                        matches = matches && MATCH_ACTION.equals(intent.getAction());
                    if (!MATCH_CLASS.isEmpty())
                        matches = matches && classMatches(cls, MATCH_CLASS);
                    if (!MATCH_DATA.isEmpty())
                        matches = matches && intent.getData() != null && MATCH_DATA.equals(intent.getData().toString());
                    if (!MATCH_TYPE.isEmpty())
                        matches = matches && MATCH_TYPE.equals(intent.getType());
                    if (MATCH_CATEGORIES.length > 0) {
                        boolean catMatch = false;
                        for (String cat : MATCH_CATEGORIES) {
                            if (intent.hasCategory(cat)) { catMatch = true; break; }
                        }
                        matches = matches && catMatch;
                    }
                    return matches;
                }

                private static boolean classMatches(String actual, String expected) {
                    if (actual == null || expected == null) return false;
                    if (actual.equals(expected)) return true;
                    String actualShort = shortClassName(actual);
                    String expectedShort = shortClassName(expected);
                    if (!actualShort.equals(expectedShort)) return false;
                    // 只配置短名或以 . 开头的类名时，允许跨包按短名匹配。
                    return !expected.contains(".") || expected.startsWith(".");
                }

                private static String shortClassName(String name) {
                    int dot = name.lastIndexOf('.');
                    return dot >= 0 ? name.substring(dot + 1) : name;
                }

                public static boolean execute(Context ctx, Intent intent, Intent result) {
                    if (!CUSTOM_ACTION.isEmpty()) result.setAction(CUSTOM_ACTION);
                    if (!CUSTOM_DATA.isEmpty() || !CUSTOM_TYPE.isEmpty()) {
                        Uri data = !CUSTOM_DATA.isEmpty() ? Uri.parse(CUSTOM_DATA) : result.getData();
                        String type = !CUSTOM_TYPE.isEmpty() ? CUSTOM_TYPE : result.getType();
                        result.setDataAndType(data, type);
                    }
                    if (!CUSTOM_PKG.isEmpty() && !CUSTOM_CLASS.isEmpty())
                        result.setClassName(CUSTOM_PKG, CUSTOM_CLASS);
                    else if (!CUSTOM_PKG.isEmpty())
                        result.setPackage(CUSTOM_PKG);
                    if (CUSTOM_FLAGS != 0) result.addFlags(CUSTOM_FLAGS);
                    if (REPLACE_CATEGORIES) {
                        java.util.Set<String> existingCats = result.getCategories();
                        if (existingCats != null) {
                            for (String cat : new java.util.HashSet<>(existingCats)) {
                                result.removeCategory(cat);
                            }
                        }
                    }
                    for (String cat : CUSTOM_CATEGORIES) {
                        result.addCategory(cat);
                    }
                    if (REPLACE_EXTRAS) {
                        result.replaceExtras(new android.os.Bundle());
                    }
                    $extrasCode
                    return BLOCK_SUBSEQUENT;
                }
            }
        """.trimIndent()
    }

    private fun buildRegistrySource(groupName: String, entries: List<RuleEntry>): String {
        val entriesCode = entries.joinToString(",\n            ") { entry ->
            """{"engine.${entry.className}", ${entry.priority}, ${javaString(entry.id)}, ${javaString(entry.ruleName)}}"""
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

    private fun javaString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        return "\"$escaped\""
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
        val ruleName: String,
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
