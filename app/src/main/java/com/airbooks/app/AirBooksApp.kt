package com.airbooks.app

import android.app.Application
import com.airbooks.app.tg.Tg

class AirBooksApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Tg.init(this)
    }
}
