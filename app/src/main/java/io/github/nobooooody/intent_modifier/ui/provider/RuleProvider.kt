package io.github.nobooooody.intent_modifier.ui.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build

class RuleProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.github.nobooooody.intent_modifier.provider"
        const val PATH_VERSION = "version"
        const val PATH_DEX = "dex"
        const val PATH_META = "meta"

        private const val CODE_VERSION = 1
        private const val CODE_DEX = 2
        private const val CODE_META = 3

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")
        val URI_VERSION: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_VERSION)
        val URI_DEX: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_DEX)
        val URI_META: Uri = Uri.withAppendedPath(CONTENT_URI, PATH_META)

        private const val PREFS_NAME = "intent_modifier_config"
        private const val KEY_COMPILED_VERSION = "compiled_version"
        private const val KEY_COMPILED_DEX = "compiled_dex"
        private const val KEY_RULES_HASH = "rules_hash"
        private const val KEY_RULE_COUNT = "rule_count"

        private const val MODULE_PKG = "io.github.nobooooody.intent_modifier"

        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_VERSION, CODE_VERSION)
            addURI(AUTHORITY, PATH_DEX, CODE_DEX)
            addURI(AUTHORITY, PATH_META, CODE_META)
        }
    }

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            grantAccessToCaller(ctx)
        }
        return true
    }

    private fun grantAccessToCaller(ctx: Context) {
        try {
            val callingUid = Binder.getCallingUid()
            if (callingUid <= 0) return

            val callingPkg = ctx.packageManager.getNameForUid(callingUid) ?: return

            val callingSig = getPackageSignatures(ctx, callingPkg) ?: return
            val moduleSig = getPackageSignatures(ctx, MODULE_PKG) ?: return

            if (signaturesMatch(callingSig, moduleSig)) {
                context?.contentResolver?.takePersistableUriPermission(
                    URI_VERSION,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                context?.contentResolver?.takePersistableUriPermission(
                    URI_DEX,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                context?.contentResolver?.takePersistableUriPermission(
                    URI_META,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun getPackageSignatures(ctx: Context, pkg: String): Array<Signature>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ctx.packageManager.getPackageInfo(
                    pkg,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)?.signatures
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun signaturesMatch(a: Array<Signature>, b: Array<Signature>): Boolean {
        if (a.size != b.size) return false
        val aSet = a.map { it.toByteArray().contentHashCode() }.toSet()
        return b.all { bSig -> aSet.contains(bSig.toByteArray().contentHashCode()) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null

        grantAccessToCaller(ctx)

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: return null

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
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = when (URI_MATCHER.match(uri)) {
        CODE_VERSION -> "vnd.android.cursor.item/long"
        CODE_DEX -> "vnd.android.cursor.item/string"
        CODE_META -> "vnd.android.cursor.item/string"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}