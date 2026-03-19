package com.linusu.flutter_web_auth_2

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object PackageNames {
    const val CHROME_STABLE = "com.android.chrome"
    const val CHROME_BETA = "com.chrome.beta"
    const val CHROME_DEV = "com.chrome.dev"
    const val MICROSOFT_EDGE = "com.microsoft.emmx"
    const val FIREFOX = "org.mozilla.firefox"
    const val SAMSUNG_INTERNET = "com.sec.android.app.sbrowser"
}

val Any.LOG_TAG: String
    get() = "flutter_web_auth_2"

/**
 * Determine whether to use AuthTab or CustomTab for authentication.
 *
 * AuthTab is preferred when available because it handles redirect natively
 * without needing CustomTabsSession. Falls back to CustomTab (with session)
 * when AuthTab API is not in the classpath or the browser doesn't support it.
 *
 * @return true if AuthTab should be used, false for CustomTab with session.
 */
fun shouldUseAuthTabs(context: Context, preferEphemeral: Boolean, targetPackage: String?): Boolean {
    // TODO: Enable AuthTab in branch +4, remove return false and uncomment below
    return false
    /*

    if (!preferEphemeral || targetPackage == null) return true

    val packageMajorVersion = context.getInstalledVersion(targetPackage)
        ?.substringBefore(".")?.toIntOrNull() ?: 0

    val chromePackages = setOf(
        PackageNames.CHROME_STABLE,
        PackageNames.CHROME_BETA,
        PackageNames.CHROME_DEV,
    )

    if (chromePackages.contains(targetPackage)) {
        return packageMajorVersion >= 141
    } else if (targetPackage == PackageNames.MICROSOFT_EDGE) {
        return packageMajorVersion >= 141
    } else if (targetPackage == PackageNames.SAMSUNG_INTERNET) {
        return packageMajorVersion >= 28
    } else if (targetPackage == PackageNames.FIREFOX) {
        return packageMajorVersion >= 143
    }

    return true
    */
}

fun Context.getInstalledVersion(packageName: String): String? {
    try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return packageInfo.versionName
    } catch (_: Exception) {
        return null
    }
}

fun Context.getPackagesForIntent(intent: Intent): List<String> {
    try {
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
        return list.map { it.activityInfo.packageName }
    } catch (_: Exception) {
        return emptyList()
    }
}
