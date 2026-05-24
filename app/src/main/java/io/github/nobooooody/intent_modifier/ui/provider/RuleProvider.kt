package io.github.nobooooody.intent_modifier.ui.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import io.github.nobooooody.intent_modifier.data.ModifierRepository

class RuleProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.github.nobooooody.intent_modifier.provider"

        const val PATH_VERSION = "version"
        const val PATH_DEX = "dex"

        private const val CODE_VERSION = 1
        private const val CODE_DEX = 2
        private const val CODE_DEX_APP = 3

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")
        val URI_VERSION: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_VERSION)
        val URI_DEX: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_DEX)

        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_VERSION, CODE_VERSION)
            addURI(AUTHORITY, PATH_DEX, CODE_DEX)
            addURI(AUTHORITY, "$PATH_DEX/*", CODE_DEX_APP)
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
                val cursor = MatrixCursor(arrayOf("dex"))
                cursor.addRow(arrayOf(dex))
                cursor
            }
            CODE_DEX_APP -> {
                val pkg = uri.lastPathSegment ?: return null
                val sanitized = repo.sanitizePackageName(pkg)
                val dex = repo.getAppDex(sanitized)
                if (dex == null) return null
                val cursor = MatrixCursor(arrayOf("dex"))
                cursor.addRow(arrayOf(dex))
                cursor
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = when (URI_MATCHER.match(uri)) {
        CODE_VERSION -> "vnd.android.cursor.item/long"
        CODE_DEX, CODE_DEX_APP -> "vnd.android.cursor.item/string"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
