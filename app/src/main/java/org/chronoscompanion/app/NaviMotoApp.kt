package org.chronoscompanion.app

import android.app.Application

/** Applies the saved theme before any screen opens (including the share-destination one). */
class NaviMotoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.apply(this)
    }
}
