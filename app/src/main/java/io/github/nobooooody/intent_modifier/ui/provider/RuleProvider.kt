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

class RuleProvider : ContentProvider() {

    companion object {
        private const val TAG = "RuleProvider"
        const val AUTHORITY = "io.github.nobooooody.intent_modifier.provider"

        const val PATH_VERSION = "version"
        const val PATH_DEX = "dex"
        const val PATH_META = "meta"

        private const val CODE_VERSION = 1
        private const val CODE_DEX = 2
        private const val CODE_DEX_APP = 3
        private const val CODE_META = 4

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")
        val URI_VERSION: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_VERSION)
        val URI_DEX: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_DEX)
        val URI_META: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_META)

        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_VERSION, CODE_VERSION)
            addURI(AUTHORITY, PATH_DEX, CODE_DEX)
            addURI(AUTHORITY, "$PATH_DEX/*", CODE_DEX_APP)
            addURI(AUTHORITY, PATH_META, CODE_META)
        }
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

        return when (URI_MATCHER.match(uri)) {
            CODE_VERSION -> {
                val version = repo.getCompiledVersion()
                val cursor = MatrixCursor(arrayOf("version"))
                cursor.addRow(arrayOf(version))
                cursor
            }
            CODE_DEX -> {
                val dex = repo.getSharedDex() ?: repo.getCompiledDex() ?: return null
                buildDexCursor(dex, uri)
            }
            CODE_DEX_APP -> {
                val pkg = uri.lastPathSegment ?: return null
                val sanitized = repo.sanitizePackageName(pkg)
                val dex = repo.getAppDex(sanitized) ?: return null
                buildDexCursor(dex, uri)
            }
            CODE_META -> {
                val manifest = repo.getCompiledManifest() ?: return null
                val cursor = MatrixCursor(arrayOf("manifest"))
                cursor.addRow(arrayOf(manifest))
                cursor
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = when (URI_MATCHER.match(uri)) {
        CODE_VERSION -> "vnd.android.cursor.item/long"
        CODE_DEX, CODE_DEX_APP -> "vnd.android.cursor.item/string"
        CODE_META -> "vnd.android.cursor.item/json"
        else -> null
    }

    private fun logCaller(uri: Uri) {
        try {
            val uid = Binder.getCallingUid()
            val packages = context?.packageManager?.getPackagesForUid(uid)
                ?.joinToString(",") ?: "unknown"
            Log.i(TAG, "query path=${uri.path} query=${uri.query} uid=$uid packages=$packages")
        } catch (t: Throwable) {
            Log.w(TAG, "query caller log failed", t)
        }
    }

    /**
     * 无 offset/length 参数时保持旧行为：返回完整 Base64 字符串。
     * 带参数时按解码后的 DEX 字节切片，返回 Base64(chunk) 与 totalSize 列；
     * offset/length 的单位都是解码后的字节。
     */
    private fun buildDexCursor(dexBase64: String, uri: Uri): Cursor? {
        val offsetParam = uri.getQueryParameter("offset")
        val lengthParam = uri.getQueryParameter("length")
        if (offsetParam == null && lengthParam == null) {
            val cursor = MatrixCursor(arrayOf("dex"))
            cursor.addRow(arrayOf(dexBase64))
            return cursor
        }

        return try {
            val bytes = Base64.decode(dexBase64, Base64.NO_WRAP)
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
