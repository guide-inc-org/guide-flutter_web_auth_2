package com.linusu.flutter_web_auth_2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
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
    fun build(): IntentWrapper
}

interface IntentWrapper {
    val intent: Intent
    fun launch(activity: Activity, launcher: ActivityResultLauncher<Intent>, url: Uri, redirectHost: String, redirectPath: String)
    fun launch(activity: Activity, launcher: ActivityResultLauncher<Intent>, url: Uri, redirectScheme: String)
}

@SuppressLint("UnsafeOptInUsageError", "UnsafeOptInUsageWarning")
class CtBuilderWrapper(
    private val b: CustomTabsIntent.Builder,
    private val context: Context,
    toolbarColor: Int? = null
) : TabBuilderWrapper {

    init {
        // Use provided color or default from resources
        val color = toolbarColor ?: ContextCompat.getColor(context, R.color.toolbarColor)
        val colorSchemeParams = CustomTabColorSchemeParams.Builder().apply {
            setToolbarColor(color)
            setNavigationBarColor(color)
        }.build()
        b.setDefaultColorSchemeParams(colorSchemeParams)

        // Hide share button in menu
        b.setShareState(CustomTabsIntent.SHARE_STATE_OFF)

        // Set iOS-style modal animations for Custom Tabs
        b.setStartAnimations(context, R.anim.slide_in_up, R.anim.fade_in)
        b.setExitAnimations(context, R.anim.fade_in, R.anim.slide_out_down)
    }

    override fun setEphemeralBrowsingEnabled(enabled: Boolean) = apply {
        b.setEphemeralBrowsingEnabled(enabled)
    }

    override fun build(): IntentWrapper {
        val intent = b.build()
        return object : IntentWrapper {

            override val intent: Intent
                get() = intent.intent

            override fun launch(activity: Activity, launcher: ActivityResultLauncher<Intent>, url: Uri, redirectHost: String, redirectPath: String) {
                intent.launchUrl(activity, url)
            }

            override fun launch(activity: Activity, launcher: ActivityResultLauncher<Intent>, url: Uri, redirectScheme: String) {
                intent.launchUrl(activity, url)
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError", "UnsafeOptInUsageWarning")
class AuthTabBuilderWrapper(
    private val b: AuthTabIntent.Builder,
    private val context: Context,
    toolbarColor: Int? = null
) : TabBuilderWrapper {

    init {
        val color = toolbarColor ?: ContextCompat.getColor(context, R.color.toolbarColor)
        val colorSchemeParams = AuthTabColorSchemeParams.Builder().apply {
            setToolbarColor(color)
            setNavigationBarColor(color)
        }.build()
        b.setDefaultColorSchemeParams(colorSchemeParams)
    }

    override fun setEphemeralBrowsingEnabled(enabled: Boolean) = apply { b.setEphemeralBrowsingEnabled(enabled) }

    override fun build(): IntentWrapper {
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
