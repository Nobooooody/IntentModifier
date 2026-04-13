package io.github.nobooooody.intent_modifier

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

class IntentModifierApp : Application() {

    override fun attachBaseContext(base: Context) {
        applyLanguage(base)
        super.attachBaseContext(base)
    }

    private fun applyLanguage(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "system") ?: "system"

        if (lang != "system" && Build.VERSION.SDK_INT >= 33) {
            try {
                val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
                localeManager.applicationLocales = LocaleList.forLanguageTags(lang)
            } catch (e: Exception) {
                // Fallback
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val lang = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("language", "system") ?: "system"
        if (lang != "system") {
            val locale = Locale(lang)
            newConfig.setLocale(locale)
            newConfig.setLocales(LocaleList(locale))
        }
    }
}