package com.linusu.flutter_web_auth_2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.browser.auth.AuthTabIntent

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

/**
 * Single source of truth for the AuthTab-vs-CustomTabs decision, shared by
 * FlutterWebAuth2Plugin (host-activity launch, Option D) and
 * AuthenticationManagementActivity (trampoline) so the two can never disagree.
 */
fun Context.shouldUseAuthTab(preferEphemeral: Boolean, targetPackage: String?): Boolean {
    if (!preferEphemeral || targetPackage == null) return true
    val packageMajorVersion = getInstalledVersion(targetPackage)?.substringBefore(".")?.toIntOrNull() ?: 0
    Log.d(LOG_TAG, "Chosen package: $targetPackage with version: $packageMajorVersion")

    val chromePackages = setOf(
        PackageNames.CHROME_STABLE,
        PackageNames.CHROME_BETA,
        PackageNames.CHROME_DEV,
    )

    return when {
        chromePackages.contains(targetPackage) -> packageMajorVersion >= 141
        targetPackage == PackageNames.MICROSOFT_EDGE -> packageMajorVersion >= 141
        targetPackage == PackageNames.SAMSUNG_INTERNET -> packageMajorVersion >= 28
        targetPackage == PackageNames.FIREFOX -> packageMajorVersion >= 143
        else -> true
    }
}

/**
 * Shared completion logic for an AuthTab result, used by both delivery doors:
 * FlutterWebAuth2Plugin.onActivityResult (host-activity path, Option D) and
 * AuthenticationManagementActivity.handleAuthResult (trampoline path, rollback).
 *
 * The in-memory callback may be null when the process was killed while the AuthTab
 * was foreground and the framework re-delivered the result after recreation. On a
 * successful result the URI is therefore ALWAYS re-dispatched as an explicit-package
 * ACTION_VIEW deep link: MainActivity.handlePasskeyCallback both completes the live
 * Dart callback and stores the URL for cold-start recovery. Other results
 * complete/clear the callback here directly.
 *
 * @return true if the result was re-dispatched as a deep link (the callback will be
 *   completed by the app's deep-link handler, not here)
 */
@SuppressLint("UnsafeOptInUsageError")
fun completeAuthTabResult(
    caller: Context,
    resultCode: Int,
    resultUri: Uri?,
    scheme: String?,
): Boolean {
    val callback = scheme?.let { FlutterWebAuth2Plugin.callbacks[it] }

    if (resultCode == AuthTabIntent.RESULT_OK && resultUri != null) {
        try {
            // Explicit package so the one-time token never enters public intent
            // resolution, where another app could squat on the custom scheme.
            val deepLink = Intent(Intent.ACTION_VIEW, resultUri).setPackage(caller.packageName)
            if (caller !is Activity) {
                deepLink.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            caller.startActivity(deepLink)
            return true
        } catch (e: Exception) {
            Log.e(caller.LOG_TAG, "Failed to launch main activity with auth result: ${e.message}")
        }
        // Fallback only if the deep-link intent could not be launched (this direct
        // completion was upstream's original primary path).
        callback?.success(resultUri.toString())
    } else if (resultCode == AuthTabIntent.RESULT_OK) {
        callback?.error("FAILED", "Authentication returned no URI", null)
    } else if (resultCode == AuthTabIntent.RESULT_CANCELED) {
        callback?.error("CANCELED", "User canceled authentication", null)
    } else {
        callback?.error("FAILED", "Authentication failed with code: $resultCode", null)
    }

    scheme?.let { FlutterWebAuth2Plugin.removeCallback(it) }
    return false
}
