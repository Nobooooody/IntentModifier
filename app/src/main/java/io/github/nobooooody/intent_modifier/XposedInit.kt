package io.github.nobooooody.intent_modifier

import android.app.Activity
import android.app.AndroidAppHelper
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nobooooody.intent_modifier.data.DexGroupManifest
import io.github.nobooooody.intent_modifier.data.RuleManifest
import io.github.nobooooody.intent_modifier.ui.provider.RuleProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.UUID

class XposedInit : IXposedHookLoadPackage {

    @Volatile
    var currentContext: Context? = null
    @Volatile
    private var rulesState: RulesState? = null

    private var hasRuleChecked = false
    private var lastRuleCheckAt = 0L
    private var loadFailureCount = 0
    private var nextLoadRetryAt = 0L

    private val ruleReentrancy: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }

    private data class RulesState(val version: Long, val rules: CompiledRules)

    private data class RegistryRule(
        val className: String,
        val priority: Int,
        val ruleId: String,
        val ruleName: String
    )

    private companion object {
        private const val TAG = "IntentModifier"
        private const val KEY_COMPILED_VERSION = "compiled_version"
        private const val KEY_COMPILED_MANIFEST = "compiled_manifest"
        private const val KEY_PROVIDER_TOKEN = "provider_token"
        private const val DEBUG_SKIP_XSP = "debug_skip_xsp"
        private const val CREDENTIAL_FILE = ".credential"
        private const val RULE_VERSION_CHECK_INTERVAL_MS = 3_000L
        private const val PREWARM_WAIT_MS = 1_000L
        private const val LOG_INTENTS = false
        private const val BASE_RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 5 * 60_000L
        private const val CHUNK_SIZE_BYTES = 256 * 1024
        private const val RULES_ROOT_DIR = "intent_modifier_rules"
        private const val CURRENT_POINTER = "current"
        private const val MANIFEST_FILE = "manifest.json"
        private const val SHARED_DEX_FILE = "rules_shared.dex"
        private const val APP_DEX_FILE = "rules_app.dex"
    }

    private fun log(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }

    private fun logThrowable(msg: String, t: Throwable) {
        XposedBridge.log("$TAG: $msg: ${t.javaClass.simpleName}: ${t.message}")
        XposedBridge.log(t)
    }

    private fun recordLoadFailure(now: Long) {
        loadFailureCount++
        val shift = (loadFailureCount - 1).coerceIn(0, 6)
        val delayMs = (BASE_RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
        nextLoadRetryAt = now + delayMs
        log("Rule load failed ($loadFailureCount time(s)), next retry in ${delayMs}ms")
    }

    private fun resetLoadFailure() {
        if (loadFailureCount > 0) {
            log("Rule load recovered after $loadFailureCount failure(s)")
        }
        loadFailureCount = 0
        nextLoadRetryAt = 0L
    }

    private fun verifyDexBytes(name: String, bytes: ByteArray, group: DexGroupManifest?): Boolean {
        if (group == null) {
            log("No manifest hash for $name, accepting legacy DEX")
            return true
        }
        if (group.sha256.isBlank()) {
            log("Manifest hash missing for $name, rejecting DEX")
            return false
        }
        if (group.size > 0 && bytes.size.toLong() != group.size) {
            log("DEX size mismatch for $name: expected=${group.size}, actual=${bytes.size}")
            return false
        }
        val actual = RuleManifest.sha256Hex(bytes)
        if (!actual.equals(group.sha256, ignoreCase = true)) {
            log("DEX hash mismatch for $name: expected=${group.sha256}, actual=$actual")
            return false
        }
        return true
    }

    private fun logIntent(prefix: String, intent: Intent) {
        if (!LOG_INTENTS) return
        try {
            val extraKeys = intent.extras?.keySet()?.joinToString(",") ?: ""
            val dataScheme = intent.data?.scheme ?: ""
            XposedBridge.log(
                "$prefix pkg=${intent.`package`}, component=${intent.component}, action=${intent.action}, " +
                    "dataScheme=$dataScheme, type=${intent.type}, flags=${intent.flags}, " +
                    "categories=${intent.categories}, extraKeys=[$extraKeys]"
            )
        } catch (t: Throwable) {
            log("Intent log failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val currentPkg = lpparam.packageName

        if (currentPkg == "io.github.nobooooody.intent_modifier") {
            return
        }

        val launcherHooks = LauncherHooksLoader.getHooks()
        val hookType = launcherHooks[currentPkg]?.hookType ?: HOOK_INSTRUMENTATION

        when (hookType) {
            HOOK_LAUNCHER3 -> hookLauncher3(lpparam)
            HOOK_INSTRUMENTATION -> hookInstrumentation(lpparam)
            else -> hookCustomClass(lpparam, hookType)
        }

        prewarmRules(lpparam)
    }

    private fun prewarmRules(lpparam: XC_LoadPackage.LoadPackageParam) {
        val thread = Thread({
            try {
                var waited = 0L
                while (waited < PREWARM_WAIT_MS) {
                    val app = AndroidAppHelper.currentApplication()
                    if (app != null) {
                        currentContext = app.applicationContext
                        loadRulesIfNeeded(lpparam, currentContext)
                        return@Thread
                    }
                    Thread.sleep(50)
                    waited += 50
                }
                log("Rule prewarm skipped: application context unavailable")
            } catch (t: Throwable) {
                log("Rule prewarm failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, "IntentModifier-prewarm")
        thread.isDaemon = true
        thread.start()
    }

    // ─── 本地缓存目录 ───────────────────────────────────────────────────────────

    private fun rulesRoot(cacheDir: File): File = File(cacheDir, RULES_ROOT_DIR)

    private fun versionDir(root: File, version: Long): File = File(root, "v$version")

    private fun readCurrentVersion(root: File): Long? {
        val pointer = File(root, CURRENT_POINTER)
        if (!pointer.exists()) return null
        return try {
            pointer.readText().trim().toLongOrNull()
        } catch (t: Throwable) {
            logThrowable("Read current cache pointer failed", t)
            null
        }
    }

    private fun latestVersionDir(root: File): Long? {
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith("v") }
            ?.mapNotNull { it.name.removePrefix("v").toLongOrNull() }
            ?.maxOrNull()
    }

    private fun readLocalManifest(dir: File): RuleManifest? {
        val file = File(dir, MANIFEST_FILE)
        if (!file.exists()) {
            log("Local manifest missing at ${file.absolutePath}")
            return null
        }
        return try {
            RuleManifest.fromJson(file.readText())
        } catch (t: Throwable) {
            logThrowable("Local manifest read failed", t)
            null
        }
    }

    private fun validateLocalDir(dir: File, manifest: RuleManifest, targetPkg: String): Boolean {
        if (manifest.groups.isEmpty()) {
            log("Local manifest has no group hashes, rejecting version dir ${dir.name}")
            return false
        }
        val sharedGroup = manifest.groups[RuleManifest.GROUP_SHARED]
        if (sharedGroup == null) {
            log("Local manifest missing shared group in ${dir.name}")
            return false
        }
        val sharedDex = File(dir, SHARED_DEX_FILE)
        if (!sharedDex.exists()) {
            log("Local shared DEX missing in ${dir.name}")
            return false
        }
        val sharedBytes = try {
            sharedDex.readBytes()
        } catch (t: Throwable) {
            logThrowable("Local shared DEX read failed", t)
            return false
        }
        if (!verifyDexBytes("local_shared/${dir.name}", sharedBytes, sharedGroup)) {
            return false
        }

        val sanitizedPkg = targetPkg.replace('.', '_')
        val appGroup = manifest.groups[sanitizedPkg]
        val appDex = File(dir, APP_DEX_FILE)
        if (appGroup != null) {
            if (!appDex.exists()) {
                log("Local app DEX missing for $targetPkg in ${dir.name}")
                return false
            }
            val appBytes = try {
                appDex.readBytes()
            } catch (t: Throwable) {
                logThrowable("Local app DEX read failed", t)
                return false
            }
            if (!verifyDexBytes("local_app_$targetPkg/${dir.name}", appBytes, appGroup)) {
                return false
            }
        } else if (appDex.exists()) {
            log("Local app DEX not described by manifest, removing it: ${appDex.absolutePath}")
            runCatching { appDex.delete() }
        }
        return true
    }

    private fun writeCurrentVersion(root: File, version: Long): Boolean {
        val atomicFile = AtomicFile(File(root, CURRENT_POINTER))
        var out: FileOutputStream? = null
        return try {
            out = atomicFile.startWrite()
            out.write(version.toString().toByteArray())
            atomicFile.finishWrite(out)
            true
        } catch (t: Throwable) {
            out?.let { runCatching { atomicFile.failWrite(it) } }
            logThrowable("Write current cache pointer failed", t)
            false
        }
    }

    /**
     * Android 14+ 的 ART 拒绝从可写文件加载 DEX；缓存写出后必须去掉写权限。
     * 目标权限等价于 -r--------，与旧版缓存保持一致。
     */
    private fun makeDexReadOnly(file: File) {
        try {
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setExecutable(false, false)
        } catch (t: Throwable) {
            log("Failed to mark DEX read-only: ${file.absolutePath}: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        if (file.canWrite()) {
            log("DEX remains writable: ${file.absolutePath}")
        }
    }

    private fun cleanupVersions(root: File, keepVersion: Long, previousVersion: Long?) {
        val keep = mutableSetOf(keepVersion)
        previousVersion?.let { keep.add(it) }
        root.listFiles()?.forEach { file ->
            when {
                file.name == CURRENT_POINTER || file.name.startsWith("$CURRENT_POINTER.") -> Unit
                file.isDirectory && file.name.startsWith("v") -> {
                    val version = file.name.removePrefix("v").toLongOrNull()
                    if (version != null && version !in keep) {
                        log("Cleaning old cache version ${file.name}")
                        runCatching { file.deleteRecursively() }
                    }
                }
                file.name.startsWith("tmp-") -> runCatching { file.deleteRecursively() }
                file.name == SHARED_DEX_FILE ||
                    file.name == APP_DEX_FILE ||
                    file.name == "meta.json" ||
                    file.name == "optimized" -> runCatching { file.deleteRecursively() }
            }
        }
    }

    private fun buildDerivedManifest(
        version: Long,
        targetPkg: String,
        sharedBytes: ByteArray,
        appBytes: ByteArray?
    ): RuleManifest {
        val groups = mutableMapOf<String, DexGroupManifest>()
        groups[RuleManifest.GROUP_SHARED] = DexGroupManifest(
            sha256 = RuleManifest.sha256Hex(sharedBytes),
            size = sharedBytes.size.toLong(),
            ruleCount = 0,
            targetPackages = emptyList()
        )
        if (appBytes != null) {
            groups[targetPkg.replace('.', '_')] = DexGroupManifest(
                sha256 = RuleManifest.sha256Hex(appBytes),
                size = appBytes.size.toLong(),
                ruleCount = 0,
                targetPackages = listOf(targetPkg)
            )
        }
        return RuleManifest(
            version = version,
            ruleCount = 0,
            compiledAt = System.currentTimeMillis(),
            enabledRuleHash = null,
            groups = groups
        )
    }

    // ─── 规则加载 ───────────────────────────────────────────────────────────────

    @Synchronized
    private fun loadRulesIfNeeded(lpparam: XC_LoadPackage.LoadPackageParam, ctx: Context?) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (hasRuleChecked && now - lastRuleCheckAt < RULE_VERSION_CHECK_INTERVAL_MS) return
            hasRuleChecked = true
            lastRuleCheckAt = now

            val targetPkg = lpparam.packageName
            val cacheDir = ctx?.cacheDir ?: run {
                log("Cannot load rules: currentContext is null")
                return
            }
            val root = rulesRoot(cacheDir)

            if (now < nextLoadRetryAt) {
                log(
                    "Rule refresh backoff active: failures=$loadFailureCount, " +
                        "retryIn=${nextLoadRetryAt - now}ms"
                )
                if (rulesState == null) {
                    tryLoadLocalCached(lpparam, targetPkg)
                }
                return
            }

            ModulePrefsReader.refresh(now)
            ctx?.let { maybeBootstrapCredential(it, targetPkg) }
            val remoteVersion = tryGetRemoteVersion()
            val state = rulesState
            if (state != null && remoteVersion != null && remoteVersion == state.version) {
                // 版本未变：不读 meta.json，直接复用当前不可变快照。
                return
            }

            val localVersion = readCurrentVersion(root) ?: latestVersionDir(root) ?: 0L

            if (remoteVersion == null || remoteVersion == 0L) {
                log("No remote version available, trying local cached DEX")
                if (!tryLoadLocalCached(lpparam, targetPkg)) {
                    recordLoadFailure(now)
                }
                return
            }

            if (remoteVersion == localVersion) {
                if (tryLoadLocalCached(lpparam, targetPkg)) {
                    log("Local rules version=$localVersion up to date")
                    return
                }
            }

            log("Need to update rules: remote=$remoteVersion, local=$localVersion")

            val remoteManifestRaw = tryGetRemoteManifest(ctx)
            var updateVersion = remoteVersion
            if (remoteManifestRaw != null && remoteManifestRaw.version != updateVersion) {
                val refreshedVersion = tryGetRemoteVersion()
                if (refreshedVersion != null && refreshedVersion != updateVersion) {
                    log("Remote version changed during manifest read: $updateVersion -> $refreshedVersion")
                    updateVersion = refreshedVersion
                }
            }
            val remoteManifest = remoteManifestRaw?.takeIf { it.version == updateVersion }
            if (remoteManifest == null) {
                if (remoteManifestRaw != null) {
                    log("Manifest version mismatch: manifest=${remoteManifestRaw.version}, effective=$updateVersion")
                }
                log("No matching manifest, skipping SHA-256 verification for this download")
            }
            if (rulesState?.version == updateVersion) {
                log("Refresh resolved to already-loaded version $updateVersion")
                return
            }

            val sanitizedPkg = targetPkg.replace('.', '_')
            val sharedGroup = remoteManifest?.groups?.get(RuleManifest.GROUP_SHARED)
            val sharedBytes = tryGetRemoteDex(ctx, targetPkg, sharedGroup)
            if (sharedBytes == null) {
                log("No remote DEX available, falling back to local cached DEX")
                if (!tryLoadLocalCached(lpparam, targetPkg)) {
                    recordLoadFailure(now)
                }
                return
            }

            val appGroup = remoteManifest?.groups?.get(sanitizedPkg)
            val appBytes = tryGetRemoteAppDex(ctx, targetPkg, appGroup)

            if (!verifyDexBytes("shared", sharedBytes, sharedGroup)) {
                recordLoadFailure(now)
                return
            }
            if (appBytes != null) {
                if (!verifyDexBytes("app_$targetPkg", appBytes, appGroup)) {
                    recordLoadFailure(now)
                    return
                }
            } else if (appGroup != null) {
                log("Manifest expects app DEX for $targetPkg but provider returned none")
                recordLoadFailure(now)
                return
            }

            if (remoteManifest != null && remoteManifest.groups.isNotEmpty()) {
                if (sharedGroup == null) {
                    log("Manifest missing shared group while remote DEX exists, rejecting update")
                    recordLoadFailure(now)
                    return
                }
                if (appBytes != null && appGroup == null) {
                    log("Manifest missing app group for $targetPkg while app DEX exists, rejecting update")
                    recordLoadFailure(now)
                    return
                }
            }

            val localManifest = remoteManifest?.takeIf { it.groups.isNotEmpty() }
                ?: buildDerivedManifest(updateVersion, targetPkg, sharedBytes, appBytes)

            val staging = File(root, "tmp-${UUID.randomUUID()}").also { it.mkdirs() }
            try {
                val stagedSharedDex = File(staging, SHARED_DEX_FILE)
                stagedSharedDex.writeBytes(sharedBytes)
                makeDexReadOnly(stagedSharedDex)
                if (appBytes != null) {
                    val stagedAppDex = File(staging, APP_DEX_FILE)
                    stagedAppDex.writeBytes(appBytes)
                    makeDexReadOnly(stagedAppDex)
                }
                File(staging, MANIFEST_FILE).writeText(localManifest.toJson())

                if (!validateLocalDir(staging, localManifest, targetPkg)) {
                    log("Staged cache failed integrity validation, keeping previous version")
                    staging.deleteRecursively()
                    recordLoadFailure(now)
                    return
                }

                val finalDir = versionDir(root, updateVersion)
                if (finalDir.exists()) {
                    finalDir.deleteRecursively()
                }
                if (!staging.renameTo(finalDir)) {
                    log("Failed to move staged cache to ${finalDir.absolutePath}")
                    staging.deleteRecursively()
                    recordLoadFailure(now)
                    return
                }

                val previousVersion = rulesState?.version
                if (!loadDexAndBuildRules(lpparam, targetPkg, updateVersion, finalDir)) {
                    log("Failed to load downloaded DEX for version=$updateVersion")
                    runCatching { finalDir.deleteRecursively() }
                    recordLoadFailure(now)
                    return
                }

                if (!writeCurrentVersion(root, updateVersion)) {
                    log("Rules loaded but current cache pointer update failed; keeping new version dir")
                }
                cleanupVersions(root, updateVersion, previousVersion)
            } catch (t: Throwable) {
                runCatching { staging.deleteRecursively() }
                throw t
            }

        } catch (t: Throwable) {
            logThrowable("Failed to load rules", t)
            recordLoadFailure(SystemClock.elapsedRealtime())
        }
    }

    private fun tryLoadLocalCached(lpparam: XC_LoadPackage.LoadPackageParam, targetPkg: String): Boolean {
        val cacheDir = currentContext?.cacheDir ?: return false
        val root = rulesRoot(cacheDir)
        if (!root.isDirectory) return false

        val currentVersion = readCurrentVersion(root)
        val candidates = mutableListOf<Long>()
        currentVersion?.let { candidates.add(it) }
        root.listFiles()
            ?.mapNotNull { file ->
                if (file.isDirectory && file.name.startsWith("v")) {
                    file.name.removePrefix("v").toLongOrNull()
                } else {
                    null
                }
            }
            ?.sortedDescending()
            ?.filter { it != currentVersion }
            ?.let { candidates.addAll(it) }

        for (version in candidates.distinct()) {
            val dir = versionDir(root, version)
            if (!dir.isDirectory) continue
            val manifest = readLocalManifest(dir) ?: continue
            if (manifest.version != version) {
                log("Local manifest version=${manifest.version} does not match dir=${dir.name}, dropping it")
                runCatching { dir.deleteRecursively() }
                continue
            }
            if (!validateLocalDir(dir, manifest, targetPkg)) {
                log("Cleaning invalid local version ${dir.name}")
                runCatching { dir.deleteRecursively() }
                continue
            }

            val previousVersion = rulesState?.version
            if (loadDexAndBuildRules(lpparam, targetPkg, version, dir)) {
                if (currentVersion != version) {
                    if (!writeCurrentVersion(root, version)) {
                        log("Local version $version loaded but current pointer update failed")
                    }
                }
                cleanupVersions(root, version, previousVersion)
                log("Local rules version=$version loaded")
                return true
            }

            log("Local version $version failed to load, deleting")
            runCatching { dir.deleteRecursively() }
        }
        return false
    }

    private fun loadDexAndBuildRules(
        lpparam: XC_LoadPackage.LoadPackageParam,
        targetPkg: String,
        version: Long,
        versionDir: File
    ): Boolean {
        try {
            if (rulesState?.version == version) {
                resetLoadFailure()
                return true
            }

            val sharedDex = File(versionDir, SHARED_DEX_FILE)
            if (!sharedDex.exists()) {
                log("Shared DEX missing at ${sharedDex.absolutePath}")
                return false
            }

            val appDex = File(versionDir, APP_DEX_FILE)
            val dexPaths = mutableListOf(sharedDex.absolutePath)
            if (appDex.exists()) dexPaths.add(appDex.absolutePath)

            // 兼容修复前写出的缓存：加载前统一去掉写权限。
            dexPaths.forEach { makeDexReadOnly(File(it)) }

            val optimizedDir = File(versionDir, "optimized").also { it.mkdirs() }
            val path = dexPaths.joinToString(":")
            val parentLoader = lpparam.classLoader
            val dexClassLoader = dalvik.system.DexClassLoader(path, optimizedDir.absolutePath, null, parentLoader)

            // 加载 shared RuleRegistry
            val registryClasses = mutableListOf<RegistryRule>()
            val sharedRegistry = loadRegistry(dexClassLoader, "engine.RuleRegistry_shared")
                ?: run {
                    log("Shared RuleRegistry missing, refusing local cache")
                    return false
                }
            registryClasses.addAll(sharedRegistry)

            // 如果有 app DEX，加载 app RuleRegistry
            if (appDex.exists()) {
                val sanitizedPkg = targetPkg.replace('.', '_')
                val appRegistry = loadRegistry(dexClassLoader, "engine.RuleRegistry_app_$sanitizedPkg")
                    ?: run {
                        log("App RuleRegistry missing for $targetPkg, refusing local cache")
                        return false
                    }
                registryClasses.addAll(appRegistry)
            }

            // 按 priority 降序排列
            registryClasses.sortByDescending { it.priority }

            // 加载每条规则的方法；Class.forName(initialize=true) 主动触发静态初始化。
            val rules = mutableListOf<LoadedRule>()
            var failedRules = 0
            for (entry in registryClasses) {
                try {
                    val ruleClass = Class.forName(entry.className, true, dexClassLoader)
                    val evaluateMethod = ruleClass.getMethod("evaluate", Context::class.java, Intent::class.java, Intent::class.java)
                    val executeMethod = ruleClass.getMethod("execute", Context::class.java, Intent::class.java, Intent::class.java)
                    rules.add(LoadedRule(entry.ruleId, entry.ruleName, evaluateMethod, executeMethod))
                } catch (t: Throwable) {
                    failedRules++
                    logThrowable(
                        "Failed to load rule id=${entry.ruleId} name=${entry.ruleName} class=${entry.className}",
                        t
                    )
                }
            }

            if (failedRules > 0) {
                log(
                    "$failedRules of ${registryClasses.size} rules failed to load/initialize and were excluded; " +
                        "${rules.size} remaining"
                )
            }
            if (registryClasses.isNotEmpty() && rules.isEmpty()) {
                log("All ${registryClasses.size} rules failed to load, refusing local cache")
                return false
            }

            rulesState = RulesState(version, CompiledRules(rules))
            resetLoadFailure()
            log("Successfully loaded ${rules.size} rules from ${dexPaths.size} DEX file(s), version=$version")
            return true
        } catch (t: Throwable) {
            log("Failed to load DEX: ${t.message}")
            return false
        }
    }

    private fun loadRegistry(classLoader: ClassLoader, registryName: String): List<RegistryRule>? {
        return try {
            val registryClass = classLoader.loadClass(registryName)
            @Suppress("UNCHECKED_CAST")
            val raw = registryClass.getMethod("getRules").invoke(null) as Array<Array<Any?>>
            val mapped = raw.mapNotNull { row ->
                val className = row.getOrNull(0) as? String ?: return@mapNotNull null
                val priority = (row.getOrNull(1) as? Number)?.toInt() ?: 0
                val ruleId = row.getOrNull(2) as? String ?: className
                val ruleName = row.getOrNull(3) as? String ?: className
                RegistryRule(className, priority, ruleId, ruleName)
            }
            if (mapped.size != raw.size) {
                log("Registry $registryName malformed: ${raw.size - mapped.size} invalid entries")
                return null
            }
            mapped
        } catch (t: Throwable) {
            logThrowable("Registry $registryName load failed", t)
            null
        }
    }

    // ─── 远端读取 ───────────────────────────────────────────────────────────────

    private data class CachedCredential(val token: String, val packageName: String)

    /**
     * 进程级单次 enroll 闸门：bootstrap、刷新路径、凭证被拒后的重试共用，
     * 保证每个进程生命周期最多一次真实 /enroll 请求。
     */
    private object EnrollmentGuard {
        val attempted = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    /** bootstrap 只检查一次，避免每次 load 都重复读 XSP/凭证。 */
    private object CredentialBootstrapGuard {
        val checked = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    /**
     * 凭证获取与版本刷新解耦：仅当 XSP 未给出有效版本、且本地没有凭证时，
     * 独立尝试一次 /enroll。成功只保存凭证，不拉 manifest/DEX；
     * 失败只由 tryEnroll 记录一次，不重试，也不影响后续刷新/降级。
     */
    private fun maybeBootstrapCredential(ctx: Context, targetPkg: String) {
        if (!CredentialBootstrapGuard.checked.compareAndSet(false, true)) return
        if (!shouldSkipXsp()) {
            val xspVersion = try {
                ModulePrefsReader.getLong(KEY_COMPILED_VERSION, 0L)
            } catch (t: Throwable) {
                0L
            }
            if (xspVersion > 0L) return
        }
        if (readCredential(ctx, targetPkg) != null) return
        val token = tryEnroll(ctx, targetPkg)
        if (token != null) {
            log("Credential bootstrap: enrolled for $targetPkg")
        }
    }

    /**
     * 仅用于排障/验证：跳过后，本进程不读 XSharedPreferences，直接走 Provider。
     * 不是安全开关，默认必须为 false。
     */
    private fun shouldSkipXsp(): Boolean {
        return try {
            ModulePrefsReader.getBoolean(DEBUG_SKIP_XSP, false)
        } catch (t: Throwable) {
            false
        }
    }

    private fun providerToken(): String? {
        if (shouldSkipXsp()) return null
        return try {
            ModulePrefsReader.getString(KEY_PROVIDER_TOKEN, null)?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            log("XSharedPreferences provider_token failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun withProviderToken(uri: Uri, token: String?): Uri {
        return if (token.isNullOrEmpty()) uri
        else uri.buildUpon().appendQueryParameter("token", token).build()
    }

    private fun providerError(cursor: Cursor): String? {
        val index = cursor.getColumnIndex("error")
        if (index < 0) return null
        return if (cursor.moveToFirst()) cursor.getString(index) else "unknown"
    }

    private fun providerUnavailableHint(t: Throwable?): String {
        val message = t?.message ?: ""
        return if (t is IllegalArgumentException ||
            message.contains("Failed to find provider info", ignoreCase = true) ||
            message.contains("Unable to find provider", ignoreCase = true) ||
            message.contains("Unknown URL", ignoreCase = true)
        ) {
            "；IM 可能处于 stopped 状态，请先打开一次 IM"
        } else {
            ""
        }
    }

    private fun credentialFile(ctx: Context): File =
        File(rulesRoot(ctx.cacheDir), CREDENTIAL_FILE)

    private fun readCredential(ctx: Context, targetPkg: String): CachedCredential? {
        val file = credentialFile(ctx)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            val token = json.optString("token", "")
            val pkg = json.optString("package", "")
            if (token.isEmpty() || pkg.isEmpty()) {
                log("Cached credential malformed, ignoring")
                null
            } else if (pkg != targetPkg) {
                log("Cached credential package=$pkg does not match host=$targetPkg, ignoring")
                null
            } else {
                CachedCredential(token, pkg)
            }
        } catch (t: Throwable) {
            log("Read cached credential failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun saveCredential(ctx: Context, token: String, targetPkg: String): Boolean {
        val root = rulesRoot(ctx.cacheDir)
        if (!root.isDirectory && !root.mkdirs()) {
            log("Save credential failed: cache dir unavailable")
            return false
        }
        val target = File(root, CREDENTIAL_FILE)
        val temp = File(root, "$CREDENTIAL_FILE.tmp")
        return try {
            temp.writeText(JSONObject().put("token", token).put("package", targetPkg).toString())
            // 凭证只允许宿主自己读取，不写日志、不写宿主 SharedPreferences。
            temp.setReadable(false, false)
            temp.setReadable(true, true)
            temp.setWritable(false, false)
            temp.setExecutable(false, false)
            target.delete()
            if (!temp.renameTo(target)) {
                log("Save credential failed: rename failed")
                temp.delete()
                false
            } else {
                log("Cached credential saved for $targetPkg (owner-only)")
                true
            }
        } catch (t: Throwable) {
            log("Save credential failed: ${t.javaClass.simpleName}: ${t.message}")
            runCatching { temp.delete() }
            false
        }
    }

    private fun clearCredential(ctx: Context) {
        val file = credentialFile(ctx)
        if (file.exists()) {
            val deleted = file.delete()
            log(if (deleted) "Cached credential cleared" else "Cached credential delete failed")
        }
    }

    private fun currentProviderToken(ctx: Context, targetPkg: String): String? {
        if (shouldSkipXsp()) return readCredential(ctx, targetPkg)?.token
        return providerToken() ?: readCredential(ctx, targetPkg)?.token
    }

    private fun tryEnroll(ctx: Context, targetPkg: String): String? {
        if (!EnrollmentGuard.attempted.compareAndSet(false, true)) return null
        val uri = RuleProvider.URI_ENROLL.buildUpon()
            .appendQueryParameter("package", targetPkg)
            .build()
        return try {
            val cursor = ctx.contentResolver.query(uri, null, null, null, null) ?: run {
                log("Enroll failed: Provider returned no cursor")
                return null
            }
            cursor.use {
                providerError(it)?.let { error ->
                    log("Enroll denied: $error; 需要在 IM 中为该应用开启授权窗口")
                    return null
                }
                if (!it.moveToFirst()) {
                    log("Enroll failed: empty response")
                    return null
                }
                val token = it.getString(it.getColumnIndex("token")) ?: ""
                val pkg = it.getString(it.getColumnIndex("package")) ?: ""
                if (token.isEmpty() || pkg.isEmpty()) {
                    log("Enroll failed: malformed response")
                    return null
                }
                if (pkg != targetPkg) {
                    log("Enroll failed: issued package=$pkg expected=$targetPkg")
                    return null
                }
                if (!saveCredential(ctx, token, pkg)) {
                    log("Enroll succeeded but credential save failed")
                    return null
                }
                log("Enroll succeeded for $pkg")
                token
            }
        } catch (t: Throwable) {
            log("Enroll failed: ${t.javaClass.simpleName}: ${t.message}${providerUnavailableHint(t)}")
            null
        }
    }

    /**
     * 处理缺 token/无效 token：必要时清掉被拒的缓存凭证，并在防抖允许时 enroll。
     * 返回可重试的新 token；null 表示按原有降级链（本地缓存 → 默认行为）处理。
     */
    private fun handleProviderAuthFailure(
        ctx: Context,
        targetPkg: String,
        tokenUsed: String?,
        error: String,
        allowEnroll: Boolean
    ): String? {
        if (error != "missing_token" && error != "invalid_token") {
            log("Provider denied ($error), not enrolling")
            return null
        }
        val credential = readCredential(ctx, targetPkg)
        val usedCredential = tokenUsed != null && credential?.token == tokenUsed
        if (usedCredential) {
            clearCredential(ctx)
        }
        if (!allowEnroll) return null
        val token = tryEnroll(ctx, targetPkg)
        if (token == null) {
            log("Enroll unavailable; falling back to local cache/default behavior")
        }
        return token
    }

    /**
     * 带凭证的 Provider 查询：首次失败且为缺 token/无效 token 时，最多 enroll 一次并重试。
     */
    private fun queryProviderAuthed(
        ctx: Context,
        uri: Uri,
        targetPkg: String,
        allowEnroll: Boolean = true
    ): Pair<Cursor?, String?> {
        val token = currentProviderToken(ctx, targetPkg)
        val cursor = ctx.contentResolver.query(withProviderToken(uri, token), null, null, null, null)
        val error = cursor?.let { providerError(it) }
        if (error == null) return cursor to null
        cursor?.close()
        val retryToken = handleProviderAuthFailure(ctx, targetPkg, token, error, allowEnroll)
            ?: return null to error
        val retryCursor = ctx.contentResolver.query(
            withProviderToken(uri, retryToken), null, null, null, null
        )
        val retryError = retryCursor?.let { providerError(it) }
        if (retryError != null) {
            retryCursor?.close()
            return null to retryError
        }
        return retryCursor to null
    }

    private fun tryGetRemoteVersion(): Long? {
        if (!shouldSkipXsp()) {
            try {
                val version = ModulePrefsReader.getLong(KEY_COMPILED_VERSION, 0L)
                if (version > 0) {
                    log("Got remote version=$version via XSharedPreferences")
                    return version
                }
                log("XSharedPreferences version is 0")
            } catch (e: Exception) {
                log("XSharedPreferences version failed: ${e.message}")
            }
        } else {
            log("debug_skip_xsp enabled, bypassing XSharedPreferences")
        }

        try {
            val ctx = currentContext ?: return null
            log("Falling back to ContentProvider for version (anonymous)")
            val cursor = ctx.contentResolver.query(RuleProvider.URI_VERSION, null, null, null, null)
            if (cursor == null) {
                log("ContentProvider version returned null；IM 可能处于 stopped 状态，请先打开一次 IM")
            }
            cursor?.use {
                providerError(it)?.let { err ->
                    log("Provider version denied: $err")
                    return null
                }
                if (it.moveToFirst()) {
                    val version = it.getLong(0)
                    if (version > 0) {
                        log("Got remote version=$version via ContentProvider (anonymous)")
                        return version
                    }
                }
            }
        } catch (e: Exception) {
            log("ContentProvider version failed: ${e.message}${providerUnavailableHint(e)}")
        }

        return null
    }

    private fun tryGetRemoteManifest(ctx: Context?): RuleManifest? {
        if (!shouldSkipXsp()) {
            try {
                val json = ModulePrefsReader.getString(KEY_COMPILED_MANIFEST, null)
                if (!json.isNullOrEmpty()) {
                    val manifest = RuleManifest.fromJson(json)
                    if (manifest != null) {
                        log("Got manifest via XSharedPreferences, version=${manifest.version}")
                        return manifest
                    }
                    log("XSharedPreferences manifest invalid")
                } else {
                    log("XSharedPreferences compiled_manifest is empty")
                }
            } catch (t: Throwable) {
                logThrowable("XSharedPreferences manifest failed", t)
            }
        }

        try {
            if (ctx == null) return null
            val targetPkg = ctx.packageName
            val token = currentProviderToken(ctx, targetPkg)
            log(
                "Falling back to ContentProvider for manifest " +
                    "(${if (token != null) "with token" else "no token"})"
            )
            val (cursor, error) = queryProviderAuthed(ctx, RuleProvider.URI_META, targetPkg)
            if (error != null) {
                log("Provider manifest denied: $error")
                return null
            }
            if (cursor == null) {
                log("Provider manifest returned null；IM 可能处于 stopped 状态，请先打开一次 IM")
                return null
            }
            cursor?.use {
                if (it.moveToFirst()) {
                    val manifest = RuleManifest.fromJson(it.getString(0))
                    if (manifest != null) {
                        log("Got manifest via ContentProvider, version=${manifest.version}")
                        return manifest
                    }
                }
            }
        } catch (t: Throwable) {
            logThrowable("ContentProvider manifest failed${providerUnavailableHint(t)}", t)
        }

        return null
    }

    private fun tryGetRemoteDex(
        ctx: Context?,
        targetPkg: String,
        group: DexGroupManifest?
    ): ByteArray? {
        if (!shouldSkipXsp()) {
            try {
                val dex = ModulePrefsReader.getString("shared_dex", null)
                if (!dex.isNullOrEmpty()) {
                    log("Got shared DEX via XSharedPreferences")
                    return Base64.decode(dex, Base64.NO_WRAP)
                }
                log("XSharedPreferences shared_dex is empty")
            } catch (t: Throwable) {
                logThrowable("XSharedPreferences shared_dex failed", t)
            }
        }

        if (ctx == null) return null
        log("Falling back to ContentProvider for shared DEX")
        return fetchProviderDex(ctx, RuleProvider.URI_DEX, group, targetPkg)
    }

    private fun tryGetRemoteAppDex(
        ctx: Context?,
        targetPkg: String,
        group: DexGroupManifest?
    ): ByteArray? {
        if (group == null) {
            log("No app DEX group for $targetPkg, skipping Provider app DEX")
            return null
        }
        if (!shouldSkipXsp()) {
            try {
                val sanitized = targetPkg.replace('.', '_')
                val dex = ModulePrefsReader.getString("app_dex_$sanitized", null)
                if (!dex.isNullOrEmpty()) {
                    log("Got app DEX for $targetPkg via XSharedPreferences")
                    return Base64.decode(dex, Base64.NO_WRAP)
                }
                log("XSharedPreferences app_dex_$sanitized is empty")
            } catch (t: Throwable) {
                logThrowable("XSharedPreferences app_dex failed", t)
            }
        }

        if (ctx == null) return null
        log("Falling back to ContentProvider for app DEX ($targetPkg)")
        val uri = Uri.withAppendedPath(RuleProvider.URI_DEX, targetPkg)
        return fetchProviderDex(ctx, uri, group, targetPkg)
    }

    private sealed class ProviderDexResult {
        class Success(val bytes: ByteArray) : ProviderDexResult()
        object NoData : ProviderDexResult()
        class Denied(val error: String, val tokenUsed: String?) : ProviderDexResult()
    }

    private fun fetchProviderDex(
        ctx: Context,
        uri: Uri,
        group: DexGroupManifest?,
        targetPkg: String
    ): ByteArray? {
        var token: String? = currentProviderToken(ctx, targetPkg)
        var result = fetchProviderDexWithToken(ctx, uri, group, token)
        if (result is ProviderDexResult.Denied) {
            log("Provider DEX denied: ${result.error}")
            val retryToken = handleProviderAuthFailure(
                ctx, targetPkg, result.tokenUsed, result.error, allowEnroll = true
            )
            if (retryToken != null) {
                token = retryToken
                result = fetchProviderDexWithToken(ctx, uri, group, retryToken)
            }
        }
        val tokenState = if (token != null) "with token" else "no token"
        return when (result) {
            is ProviderDexResult.Success -> {
                log("Got DEX via ContentProvider ($tokenState), ${result.bytes.size} bytes")
                result.bytes
            }
            is ProviderDexResult.Denied -> {
                log("Got no DEX via ContentProvider ($tokenState): ${result.error}")
                null
            }
            ProviderDexResult.NoData -> {
                log("Got no DEX via ContentProvider ($tokenState)")
                null
            }
        }
    }

    private fun fetchProviderDexWithToken(
        ctx: Context,
        uri: Uri,
        group: DexGroupManifest?,
        token: String?
    ): ProviderDexResult {
        val authedUri = withProviderToken(uri, token)
        log("Provider DEX request (${if (token != null) "with token" else "no token"})")
        val knownSize = group?.size?.takeIf { it > 0L }
        if (knownSize != null && knownSize > CHUNK_SIZE_BYTES) {
            return fetchProviderDexChunked(ctx, authedUri, knownSize, token)
        }

        return try {
            val cursor = ctx.contentResolver.query(authedUri, null, null, null, null)
                ?: return ProviderDexResult.NoData
            cursor.use {
                providerError(it)?.let { err ->
                    return ProviderDexResult.Denied(err, token)
                }
                if (it.moveToFirst()) {
                    val dex = it.getString(0)
                    if (!dex.isNullOrEmpty()) {
                        return ProviderDexResult.Success(Base64.decode(dex, Base64.NO_WRAP))
                    }
                }
            }
            ProviderDexResult.NoData
        } catch (t: Throwable) {
            logThrowable("Provider full DEX query failed, retrying in chunks${providerUnavailableHint(t)}", t)
            fetchProviderDexChunked(ctx, authedUri, knownSize, token)
        }
    }

    private fun fetchProviderDexChunked(
        ctx: Context,
        baseUri: Uri,
        knownSize: Long?,
        token: String?
    ): ProviderDexResult {
        try {
            val output = ByteArrayOutputStream()
            var offset = 0L
            var total = knownSize

            while (true) {
                if (total != null && offset >= total) break

                val chunkUri = baseUri.buildUpon()
                    .appendQueryParameter("offset", offset.toString())
                    .appendQueryParameter("length", CHUNK_SIZE_BYTES.toString())
                    .build()
                val cursor = ctx.contentResolver.query(chunkUri, null, null, null, null)
                    ?: return ProviderDexResult.NoData
                var chunkBytes: ByteArray? = null
                cursor.use {
                    providerError(it)?.let { err ->
                        return ProviderDexResult.Denied(err, token)
                    }
                    if (!it.moveToFirst()) return ProviderDexResult.NoData
                    val encoded = it.getString(0) ?: return ProviderDexResult.NoData
                    val totalIndex = it.getColumnIndex("totalSize")
                    if (total == null && totalIndex >= 0) {
                        val responseTotal = it.getLong(totalIndex)
                        if (responseTotal > 0L) total = responseTotal
                    }
                    chunkBytes = Base64.decode(encoded, Base64.NO_WRAP)
                }

                val bytes = chunkBytes ?: return ProviderDexResult.NoData
                if (bytes.isEmpty()) break
                output.write(bytes)
                if (total == null && bytes.size > CHUNK_SIZE_BYTES) {
                    log("Provider returned an oversized chunk; assuming chunk parameters are ignored")
                    break
                }
                offset += bytes.size
                if (total != null && offset >= total) break
                if (total == null && bytes.size < CHUNK_SIZE_BYTES) break
            }

            val result = output.toByteArray()
            if (total != null && result.size.toLong() != total) {
                log("Chunked DEX size mismatch: expected=$total, actual=${result.size}")
                return ProviderDexResult.NoData
            }
            log("Fetched chunked DEX: ${result.size} bytes, chunks<=$CHUNK_SIZE_BYTES")
            return ProviderDexResult.Success(result)
        } catch (t: Throwable) {
            logThrowable("Provider chunked DEX query failed${providerUnavailableHint(t)}", t)
            return ProviderDexResult.NoData
        }
    }

    // ─── 规则执行 ───────────────────────────────────────────────────────────────

    private fun applyRules(intent: Intent): Intent {
        val rules = rulesState?.rules
        if (rules == null || rules.list.isEmpty()) return intent

        val ruleIntent = Intent(intent)
        val resultIntent = Intent(intent)
        var executed = false
        for (rule in rules.list) {
            try {
                val matched = rule.evaluateMethod.invoke(null, currentContext, ruleIntent, resultIntent) as? Boolean ?: false
                if (matched) {
                    val shouldBlock = rule.executeMethod.invoke(null, currentContext, ruleIntent, resultIntent) as? Boolean ?: true
                    executed = true
                    if (shouldBlock) break
                }
            } catch (t: Throwable) {
                logThrowable(
                    "Rule execution failed id=${rule.ruleId} name=${rule.ruleName}",
                    t
                )
            }
        }
        return if (executed) resultIntent else intent
    }

    // ─── Hook 方法 ──────────────────────────────────────────────────────────────

    private fun hookInstrumentation(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation", lpparam.classLoader, "execStartActivity",
                Context::class.java, IBinder::class.java, IBinder::class.java, Activity::class.java, Intent::class.java, Int::class.javaPrimitiveType, Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args[4] as? Intent ?: return
                        val modifiedIntent = applyHookRules(lpparam, "Instrumentation", intent) ?: return
                        param.args[4] = modifiedIntent
                    }
                })
        } catch (t: Throwable) {
            logThrowable("Failed to hook Instrumentation.execStartActivity", t)
        }
    }

    private fun hookLauncher3(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val classLoader = lpparam.classLoader
            val cls = Class.forName("com.android.launcher3.Launcher", false, classLoader)
            var hooked = 0
            for (method in cls.declaredMethods) {
                if (method.name != "startActivitySafely") continue
                val intentIndex = method.parameterTypes.indexOf(Intent::class.java)
                if (intentIndex < 0) continue
                hookMethodWithIntent(lpparam, "Launcher3", method, intentIndex)
                hooked++
            }
            if (hooked == 0) {
                log("Launcher3.startActivitySafely with Intent parameter not found")
            }
        } catch (t: Throwable) {
            logThrowable("Failed to hook Launcher3", t)
        }
    }

    private fun hookCustomClass(lpparam: XC_LoadPackage.LoadPackageParam, hookClassName: String) {
        try {
            val cls = Class.forName(hookClassName, false, lpparam.classLoader)
            val methods = cls.declaredMethods
            var hooked = 0
            for (method in methods) {
                if (!method.name.contains("startActivity")) continue
                val intentIndex = method.parameterTypes.indexOf(Intent::class.java)
                if (intentIndex < 0) continue
                hookMethodWithIntent(lpparam, "Custom", method, intentIndex)
                hooked++
            }
            if (hooked == 0) {
                for (method in methods) {
                    if (!method.name.contains("startActivity")) continue
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            for (i in param.args.indices) {
                                val intent = param.args[i] as? Intent ?: continue
                                val modifiedIntent = applyHookRules(lpparam, "Custom", intent) ?: return
                                param.args[i] = modifiedIntent
                                return
                            }
                        }
                    })
                    log("Hooked $hookClassName.${method.name} (fallback)")
                }
                if (methods.none { it.name.contains("startActivity") }) {
                    log("No startActivity method found in $hookClassName")
                }
            }
        } catch (t: Throwable) {
            logThrowable("Failed to hook custom class $hookClassName", t)
        }
    }

    private fun hookMethodWithIntent(
        lpparam: XC_LoadPackage.LoadPackageParam,
        label: String,
        method: Method,
        intentIndex: Int
    ) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val intent = param.args.getOrNull(intentIndex) as? Intent ?: return
                val modifiedIntent = applyHookRules(lpparam, label, intent) ?: return
                param.args[intentIndex] = modifiedIntent
            }
        })
        log("Hooked $label ${method.declaringClass.name}#${method.name} intentArg=$intentIndex")
    }

    private fun applyHookRules(
        lpparam: XC_LoadPackage.LoadPackageParam,
        label: String,
        intent: Intent
    ): Intent? {
        val depth = ruleReentrancy.get() ?: 0
        if (depth > 0) return null
        ruleReentrancy.set(depth + 1)
        return try {
            if (currentContext == null) {
                currentContext = AndroidAppHelper.currentApplication()?.applicationContext
            }
            loadRulesIfNeeded(lpparam, currentContext)
            logIntent("$TAG $label: Original", intent)
            val modifiedIntent = applyRules(intent)
            if (modifiedIntent !== intent) {
                logIntent("$TAG $label: Modified", modifiedIntent)
            }
            modifiedIntent
        } catch (t: Throwable) {
            logThrowable("Rule hook failed label=$label", t)
            null
        } finally {
            ruleReentrancy.set((ruleReentrancy.get() ?: 1) - 1)
        }
    }
}

/**
 * 进程级 XSharedPreferences 读取器：避免每次 hook 都重新解析整份
 * 含 Base64 DEX 的 XML；reload 按 3 秒窗口节流。
 */
private object ModulePrefsReader {
    private const val MODULE_PACKAGE = "io.github.nobooooody.intent_modifier"
    private const val PREFS_NAME = "intent_modifier_config"
    private const val RELOAD_INTERVAL_MS = 3_000L

    private val prefs: XSharedPreferences by lazy {
        XSharedPreferences(MODULE_PACKAGE, PREFS_NAME).also { it.makeWorldReadable() }
    }

    private var loadedOnce = false
    private var lastReloadAt = 0L

    @Synchronized
    fun refresh(now: Long = SystemClock.elapsedRealtime(), force: Boolean = false) {
        if (!loadedOnce) {
            // 构造函数已经触发首次加载，这里只记录窗口起点。
            loadedOnce = true
            lastReloadAt = now
            return
        }
        if (force || now - lastReloadAt >= RELOAD_INTERVAL_MS) {
            prefs.reload()
            lastReloadAt = now
        }
    }

    fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    fun getString(key: String, default: String?): String? = prefs.getString(key, default)

    fun all(): Map<String, *> = prefs.all
}

data class LoadedRule(
    val ruleId: String,
    val ruleName: String,
    val evaluateMethod: java.lang.reflect.Method,
    val executeMethod: java.lang.reflect.Method
)

data class CompiledRules(val list: List<LoadedRule>)

const val HOOK_INSTRUMENTATION = "android.app.Instrumentation"
const val HOOK_LAUNCHER3 = "com.android.launcher3.Launcher"

object LauncherHooksLoader {
    private const val TAG = "IntentModifier"
    private var hooks = mapOf<String, LoadedLauncherHook>()
    private var lastLoad = 0L
    private const val CACHE_DURATION = 5000L

    @Synchronized
    fun getHooks(): Map<String, LoadedLauncherHook> {
        if (System.currentTimeMillis() - lastLoad > CACHE_DURATION) loadHooks()
        return hooks
    }

    private fun log(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }

    private fun loadHooks() {
        lastLoad = System.currentTimeMillis()
        val allPrefs = try {
            ModulePrefsReader.all()
        } catch (t: Throwable) {
            log("launcher_hooks read failed, falling back to $HOOK_INSTRUMENTATION: ${t.javaClass.simpleName}: ${t.message}")
            hooks = emptyMap()
            return
        }

        if (allPrefs.isEmpty()) {
            log("launcher_hooks read failed or module prefs empty, falling back to $HOOK_INSTRUMENTATION")
            hooks = emptyMap()
            return
        }

        val jsonStr = allPrefs["launcher_hooks"] as? String
        if (jsonStr == null) {
            log("launcher_hooks not configured, falling back to $HOOK_INSTRUMENTATION")
            hooks = emptyMap()
            return
        }
        if (jsonStr.isBlank() || jsonStr == "{}") {
            log("launcher_hooks is an empty map, falling back to $HOOK_INSTRUMENTATION")
            hooks = emptyMap()
            return
        }

        val newHooks = mutableMapOf<String, LoadedLauncherHook>()
        try {
            val json = JSONObject(jsonStr)
            json.keys().forEach { pkg ->
                val hookJson = json.getJSONObject(pkg)
                newHooks[pkg] = LoadedLauncherHook(pkg, hookJson.optString("hookType", HOOK_INSTRUMENTATION))
            }
        } catch (t: Throwable) {
            log("launcher_hooks parse failed, falling back to $HOOK_INSTRUMENTATION: ${t.javaClass.simpleName}: ${t.message}")
            hooks = emptyMap()
            return
        }

        if (newHooks.isEmpty()) {
            log("launcher_hooks parsed to an empty map, falling back to $HOOK_INSTRUMENTATION")
        } else {
            log("launcher_hooks loaded ${newHooks.size} entries")
        }
        hooks = newHooks
    }
}

data class LoadedLauncherHook(val packageName: String, val hookType: String)
