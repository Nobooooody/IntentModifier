package io.github.nobooooody.intent_modifier.ui.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.util.Base64
import android.util.Log
import io.github.nobooooody.intent_modifier.data.ModifierRepository
import java.security.MessageDigest

class RuleProvider : ContentProvider() {

    companion object {
        private const val TAG = "RuleProvider"
        const val AUTHORITY = "io.github.nobooooody.intent_modifier.provider"

        const val PATH_VERSION = "version"
        const val PATH_DEX = "dex"
        const val PATH_META = "meta"
        const val PATH_ENROLL = "enroll"

        private const val CODE_VERSION = 1
        private const val CODE_DEX = 2
        private const val CODE_DEX_APP = 3
        private const val CODE_META = 4
        private const val CODE_ENROLL = 5

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")
        val URI_VERSION: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_VERSION)
        val URI_DEX: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_DEX)
        val URI_META: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_META)
        val URI_ENROLL: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_ENROLL)

        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_VERSION, CODE_VERSION)
            addURI(AUTHORITY, PATH_DEX, CODE_DEX)
            addURI(AUTHORITY, "$PATH_DEX/*", CODE_DEX_APP)
            addURI(AUTHORITY, PATH_META, CODE_META)
            addURI(AUTHORITY, PATH_ENROLL, CODE_ENROLL)
        }

        private val ENROLL_LOCK = Any()
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        logCaller(uri)
        val ctx = context ?: return null
        val repo = ModifierRepository(ctx)
        val code = URI_MATCHER.match(uri)

        if (code == CODE_META || code == CODE_DEX || code == CODE_DEX_APP) {
            val auth = checkAuth(uri, repo)
            if (!auth.allowed) {
                Log.w(
                    TAG,
                    "auth denied path=${uri.path} status=${auth.status} uid=${Binder.getCallingUid()}"
                )
                return errorCursor(auth.error ?: "unauthorized")
            }
            Log.i(TAG, "auth ok path=${uri.path} status=${auth.status} uid=${Binder.getCallingUid()}")
        }

        return when (code) {
            CODE_VERSION -> {
                val version = repo.getCompiledVersion()
                val cursor = MatrixCursor(arrayOf("version"))
                cursor.addRow(arrayOf(version))
                cursor
            }
            CODE_DEX -> {
                val dex = repo.getSharedDex() ?: repo.getCompiledDex() ?: return null
                buildDexCursor(dex, uri, repo.getCompiledVersion(), "shared")
            }
            CODE_DEX_APP -> {
                val pkg = uri.lastPathSegment ?: return null
                val sanitized = repo.sanitizePackageName(pkg)
                val dex = repo.getAppDex(sanitized) ?: return null
                buildDexCursor(dex, uri, repo.getCompiledVersion(), sanitized)
            }
            CODE_META -> {
                val manifest = repo.getCompiledManifest() ?: return null
                val cursor = MatrixCursor(arrayOf("manifest"))
                cursor.addRow(arrayOf(manifest))
                cursor
            }
            CODE_ENROLL -> handleEnroll(uri, repo)
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = when (URI_MATCHER.match(uri)) {
        CODE_VERSION -> "vnd.android.cursor.item/long"
        CODE_DEX, CODE_DEX_APP -> "vnd.android.cursor.item/string"
        CODE_META -> "vnd.android.cursor.item/json"
        CODE_ENROLL -> "vnd.android.cursor.item/string"
        else -> null
    }

    private data class AuthResult(
        val allowed: Boolean,
        val status: String,
        val error: String? = null
    )

    /**
     * token 证明的是“调用方能够读取模块私有 prefs（XSP），或曾在授权窗口内为目标包领取过凭证”，
     * 不是“调用方一定是 hook 代码”。同一 UID 下的普通代码同样可能持有凭证。
     */
    private fun checkAuth(uri: Uri, repo: ModifierRepository): AuthResult {
        if (!repo.isProviderTokenRequired()) {
            return AuthResult(true, "disabled")
        }
        val globalToken = repo.getProviderToken()
        if (globalToken.isNullOrEmpty()) {
            // 旧数据没有 token：保持旧行为，避免升级即失效。
            return AuthResult(true, "legacy_no_token")
        }
        val provided = uri.getQueryParameter("token")
        if (provided.isNullOrEmpty()) {
            return AuthResult(false, "missing_token", "missing_token")
        }
        if (secureEquals(provided, globalToken)) {
            return AuthResult(true, "ok_global")
        }
        val callerPkgs = callerPackages()
        var packageMatch = false
        for ((pkg, token) in repo.getProviderTokens()) {
            if (secureEquals(provided, token) && callerPkgs.contains(pkg)) {
                packageMatch = true
            }
        }
        return if (packageMatch) {
            AuthResult(true, "ok_package")
        } else {
            AuthResult(false, "invalid_token", "invalid_token")
        }
    }

    private fun callerPackages(): Set<String> {
        return try {
            context?.packageManager
                ?.getPackagesForUid(Binder.getCallingUid())
                ?.toSet()
                ?: emptySet()
        } catch (t: Throwable) {
            emptySet()
        }
    }

    private fun secureEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }

    private fun errorCursor(message: String): Cursor {
        return MatrixCursor(arrayOf("error")).apply { addRow(arrayOf(message)) }
    }

    /**
     * 本地模式授权：窗口开启 + 调用方 UID 的包与窗口目标包一致时发放一次性凭证；
     * 凭证表与窗口关闭在同一次 commit 内完成。窗口每次只发一个。
     */
    private fun handleEnroll(uri: Uri, repo: ModifierRepository): Cursor {
        synchronized(ENROLL_LOCK) {
            val now = System.currentTimeMillis()
            val until = repo.getEnrollWindowUntil()
            val claimed = uri.getQueryParameter("package")
            if (until <= 0L || now >= until) {
                Log.w(
                    TAG,
                    "enroll denied: window closed uid=${Binder.getCallingUid()} " +
                        "until=$until claimed=$claimed"
                )
                return errorCursor("enroll_window_closed")
            }
            val target = repo.getEnrollTargetPackage()
            if (target.isNullOrBlank()) {
                Log.w(
                    TAG,
                    "enroll denied: target package missing uid=${Binder.getCallingUid()} " +
                        "claimed=$claimed"
                )
                return errorCursor("enroll_target_missing")
            }
            val callerPkgs = callerPackages()
            if (!callerPkgs.contains(target)) {
                Log.w(
                    TAG,
                    "enroll denied: package mismatch target=$target uid=${Binder.getCallingUid()} " +
                        "packages=$callerPkgs claimed=$claimed"
                )
                return errorCursor("enroll_package_mismatch")
            }
            val token = repo.issueEnrollmentToken(target)
            if (token == null) {
                Log.w(TAG, "enroll failed: write error target=$target")
                return errorCursor("enroll_write_failed")
            }
            Log.i(TAG, "enroll issued: target=$target uid=${Binder.getCallingUid()}")
            return MatrixCursor(arrayOf("token", "package")).apply {
                addRow(arrayOf(token, target))
            }
        }
    }

    private fun logCaller(uri: Uri) {
        try {
            val uid = Binder.getCallingUid()
            val packages = context?.packageManager?.getPackagesForUid(uid)
                ?.joinToString(",") ?: "unknown"
            // 只记录参数名，避免把 token 写进日志。
            val paramNames = uri.queryParameterNames?.joinToString(",") ?: ""
            Log.i(TAG, "query path=${uri.path} params=[$paramNames] uid=$uid packages=$packages")
        } catch (t: Throwable) {
            Log.w(TAG, "query caller log failed", t)
        }
    }

    /**
     * 无 offset/length 参数时保持旧行为：返回完整 Base64 字符串。
     * 带参数时按解码后的 DEX 字节切片，返回 Base64(chunk) 与 totalSize 列；
     * offset/length 的单位都是解码后的字节。
     */
    private fun buildDexCursor(dexBase64: String, uri: Uri, version: Long, group: String): Cursor? {
        val offsetParam = uri.getQueryParameter("offset")
        val lengthParam = uri.getQueryParameter("length")
        if (offsetParam == null && lengthParam == null) {
            val cursor = MatrixCursor(arrayOf("dex"))
            cursor.addRow(arrayOf(dexBase64))
            return cursor
        }

        return try {
            val bytes = DexDecodeCache.get(version, group) {
                Base64.decode(dexBase64, Base64.NO_WRAP)
            }
            val total = bytes.size
            val offset = (offsetParam?.toLongOrNull() ?: 0L).coerceIn(0L, total.toLong())
            val requestedLength = lengthParam?.toLongOrNull() ?: (total.toLong() - offset)
            val length = requestedLength.coerceIn(0L, total.toLong() - offset)
            val start = offset.toInt()
            val end = (offset + length).toInt()
            val chunk = bytes.copyOfRange(start, end)
            val cursor = MatrixCursor(arrayOf("dex", "totalSize"))
            cursor.addRow(arrayOf(Base64.encodeToString(chunk, Base64.NO_WRAP), total))
            cursor
        } catch (t: Throwable) {
            null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * Provider 进程内的 DEX 解码缓存，key 为 (compiled_version, group)。
 * 版本变化时旧条目立即失效；最多 4 个条目、16 MB，超出后按 LRU 淘汰。
 */
private object DexDecodeCache {
    private data class Key(val version: Long, val group: String)

    private const val MAX_ENTRIES = 4
    private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024

    private val cache = LinkedHashMap<Key, ByteArray>(8, 0.75f, true)
    private var totalBytes = 0L

    @Synchronized
    fun get(version: Long, group: String, loader: () -> ByteArray): ByteArray {
        cache.keys.filter { it.version != version }.forEach { key ->
            cache.remove(key)?.let { totalBytes -= it.size }
        }

        val key = Key(version, group)
        cache[key]?.let { return it }

        val bytes = loader()
        if (bytes.size <= MAX_TOTAL_BYTES) {
            cache[key] = bytes
            totalBytes += bytes.size
            trim()
        }
        return bytes
    }

    private fun trim() {
        while (cache.size > MAX_ENTRIES || totalBytes > MAX_TOTAL_BYTES) {
            val eldestKey = cache.keys.firstOrNull() ?: return
            val removed = cache.remove(eldestKey) ?: return
            totalBytes -= removed.size
        }
    }
}
