package com.linusu.flutter_web_auth_2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabColorSchemeParams
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat

interface TabBuilderWrapper {
    fun setEphemeralBrowsingEnabled(enabled: Boolean): TabBuilderWrapper
    fun build(activity: Activity): IntentWrapper
}

interface IntentWrapper {
    val intent: Intent
    fun launch(activity: Activity, launcher: ActivityResultLauncher<Intent>, url: Uri, redirectHost: String, redirectPath: String)
    fun launch(activity: Activity, launcher: ActivityResultLauncher<Intent>, url: Uri, redirectScheme: String)
}

@SuppressLint("UnsafeOptInUsageError", "UnsafeOptInUsageWarning")
class CtBuilderWrapper(private val b: CustomTabsIntent.Builder) : TabBuilderWrapper {
    override fun setEphemeralBrowsingEnabled(enabled: Boolean) = apply {
        b.setEphemeralBrowsingEnabled(enabled)
    }

    override fun build(activity: Activity): IntentWrapper {
        val colorSchemeParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(activity, R.color.toolbarColor))
            .setNavigationBarColor(ContextCompat.getColor(activity, R.color.navigationBarColor))
            .build()

        // Set as Partial Custom Tab (full screen height) to enable startActivityForResult
        // This allows receiving result when Custom Tab is closed/minimized
        // Use full display height including system bars to eliminate top gap
        val screenHeight = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            activity.windowManager.currentWindowMetrics.bounds.height()
        } else {
            val realMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealMetrics(realMetrics)
            realMetrics.heightPixels
        }
        b.setInitialActivityHeightPx(screenHeight, CustomTabsIntent.ACTIVITY_HEIGHT_FIXED)
            .setToolbarCornerRadiusDp(0)

        b.setDefaultColorSchemeParams(colorSchemeParams)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)

        val intent = b.build()
        return object : IntentWrapper {

            override val intent: Intent
                get() = intent.intent

            override fun launch(activity: Activity, launcher: ActivityResultLauncher<Intent>, url: Uri, redirectHost: String, redirectPath: String) {
                intent.intent.setData(url)
                launcher.launch(intent.intent)
            }

            override fun launch(activity: Activity, launcher: ActivityResultLauncher<Intent>, url: Uri, redirectScheme: String) {
                intent.intent.setData(url)
                launcher.launch(intent.intent)
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError", "UnsafeOptInUsageWarning")
class AuthTabBuilderWrapper(private val b: AuthTabIntent.Builder) : TabBuilderWrapper {

    override fun setEphemeralBrowsingEnabled(enabled: Boolean) =
        apply { b.setEphemeralBrowsingEnabled(enabled) }

    override fun build(activity: Activity): IntentWrapper {
        val colorSchemeParams = AuthTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(activity, R.color.toolbarColor))
            .setNavigationBarColor(ContextCompat.getColor(activity, R.color.navigationBarColor))
            .build()
        b.setDefaultColorSchemeParams(colorSchemeParams)
        val intent = b.build()
        return object : IntentWrapper {

            override val intent: Intent
                get() = intent.intent

            override fun launch(activity: Activity, launcher: ActivityResultLauncher<Intent>, url: Uri, redirectHost: String, redirectPath: String) {
                intent.launch(launcher, url, redirectHost, redirectPath)
            }

            override fun launch(activity: Activity, launcher: ActivityResultLauncher<Intent>, url: Uri, redirectScheme: String) {
                intent.launch(launcher, url, redirectScheme)
            }
        }
    }
}
