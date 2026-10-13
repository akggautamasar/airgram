package com.airbooks.app

import android.content.Context

object Prefs {
    fun get(context: Context, key: String, defaultValue: String = ""): String =
        context.getSharedPreferences("airgram", Context.MODE_PRIVATE).getString(key, defaultValue) ?: defaultValue
}
