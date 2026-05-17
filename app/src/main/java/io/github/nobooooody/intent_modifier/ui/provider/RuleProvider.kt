package io.github.nobooooody.intent_modifier.ui.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class RuleProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.github.nobooooody.intent_modifier.provider"

        const val PATH_VERSION = "version"
        const val PATH_DEX = "dex"
        const val PATH_META = "meta"
        const val PATH_HASH = "hash"
        const val PATH_COUNT = "count"

        private const val CODE_VERSION = 1
        private const val CODE_DEX = 2
        private const val CODE_META = 3
        private const val CODE_HASH = 4
        private const val CODE_COUNT = 5

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")
        val URI_VERSION: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_VERSION)
        val URI_DEX: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_DEX)
        val URI_META: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_META)
        val URI_HASH: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_HASH)
        val URI_COUNT: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_COUNT)

        private const val PREFS_NAME = "intent_modifier_config"
        private const val KEY_COMPILED_VERSION = "compiled_version"
        private const val KEY_COMPILED_DEX = "compiled_dex"
        private const val KEY_RULES_HASH = "rules_hash"
        private const val KEY_RULE_COUNT = "rule_count"

        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_VERSION, CODE_VERSION)
            addURI(AUTHORITY, PATH_DEX, CODE_DEX)
            addURI(AUTHORITY, PATH_META, CODE_META)
            addURI(AUTHORITY, PATH_HASH, CODE_HASH)
            addURI(AUTHORITY, PATH_COUNT, CODE_COUNT)
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
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return null

        return when (URI_MATCHER.match(uri)) {
            CODE_VERSION -> {
                val version = prefs.getLong(KEY_COMPILED_VERSION, 0L)
                val cursor = MatrixCursor(arrayOf("version"))
                cursor.addRow(arrayOf(version))
                cursor
            }
            CODE_DEX -> {
                val dexBase64 = prefs.getString(KEY_COMPILED_DEX, null) ?: return null
                val cursor = MatrixCursor(arrayOf("dex"))
                cursor.addRow(arrayOf(dexBase64))
                cursor
            }
            CODE_META -> {
                val version = prefs.getLong(KEY_COMPILED_VERSION, 0L)
                val rulesHash = prefs.getString(KEY_RULES_HASH, "") ?: ""
                val ruleCount = prefs.getInt(KEY_RULE_COUNT, 0)
                val cursor = MatrixCursor(arrayOf("version", "hash", "count"))
                cursor.addRow(arrayOf(version, rulesHash, ruleCount))
                cursor
            }
            CODE_HASH -> {
                val rulesHash = prefs.getString(KEY_RULES_HASH, "") ?: ""
                val cursor = MatrixCursor(arrayOf("hash"))
                cursor.addRow(arrayOf(rulesHash))
                cursor
            }
            CODE_COUNT -> {
                val ruleCount = prefs.getInt(KEY_RULE_COUNT, 0)
                val cursor = MatrixCursor(arrayOf("count"))
                cursor.addRow(arrayOf(ruleCount))
                cursor
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = when (URI_MATCHER.match(uri)) {
        CODE_VERSION -> "vnd.android.cursor.item/long"
        CODE_DEX -> "vnd.android.cursor.item/string"
        CODE_META -> "vnd.android.cursor.item/string"
        CODE_HASH -> "vnd.android.cursor.item/string"
        CODE_COUNT -> "vnd.android.cursor.item/int"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}