package org.chronoscompanion.app.notification

import android.content.Context

/**
 * Which installed apps forward their notifications to the watch, picked by the user
 * from [org.chronoscompanion.app.NotificationAppsActivity]. Backed by its own
 * SharedPreferences file so that screen and [ChronosNotificationListener] (running in
 * the background, independently of any UI) stay in sync through plain reads/writes.
 */
object NotificationAppsPrefs {
    private const val PREFS_NAME = "notification_apps_prefs"
    private const val KEY_ENABLED_PACKAGES = "enabled_packages"

    // The apps ChronosNotificationListener forwarded before this picker screen existed -
    // used to seed the default set so installing this update doesn't silently stop
    // notifications the user already relied on.
    val DEFAULT_ENABLED_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.google.android.gm",
        "com.android.email",
        "org.telegram.messenger",
        "com.facebook.orca",
        "com.facebook.katana",
        "com.instagram.android",
        "com.twitter.android",
        "com.viber.voip",
        "com.skype.raider",
        "com.kakao.talk",
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getEnabledPackages(context: Context): Set<String> {
        val p = prefs(context)
        if (!p.contains(KEY_ENABLED_PACKAGES)) {
            p.edit().putStringSet(KEY_ENABLED_PACKAGES, DEFAULT_ENABLED_PACKAGES).apply()
            return DEFAULT_ENABLED_PACKAGES
        }
        // getStringSet's own default is only used if the key is missing (handled above) -
        // this null-coalesce just satisfies the nullable return type.
        return p.getStringSet(KEY_ENABLED_PACKAGES, null) ?: DEFAULT_ENABLED_PACKAGES
    }

    fun isEnabled(context: Context, packageName: String): Boolean =
        packageName in getEnabledPackages(context)

    fun setEnabled(context: Context, packageName: String, enabled: Boolean) {
        // SharedPreferences forbids mutating the Set instance returned by getStringSet()
        // in place, so copy it before editing.
        val current = getEnabledPackages(context).toMutableSet()
        if (enabled) current.add(packageName) else current.remove(packageName)
        prefs(context).edit().putStringSet(KEY_ENABLED_PACKAGES, current).apply()
    }
}
