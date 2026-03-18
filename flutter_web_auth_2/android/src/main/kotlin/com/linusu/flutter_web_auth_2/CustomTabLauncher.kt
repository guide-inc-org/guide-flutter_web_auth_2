package com.linusu.flutter_web_auth_2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.ContextCompat

/**
 * Manages Chrome Custom Tab with a bound CustomTabsSession.
 *
 * Session binding enables applinks redirect back to app
 * (Chrome knows CCT belongs to this app).
 *
 * This class is independent of Activity lifecycle to avoid conflicts
 * with AuthTab and PiP scenarios.
 */
@SuppressLint("UnsafeOptInUsageError", "UnsafeOptInUsageWarning")
class CustomTabLauncher(
    private val activity: Activity,
    private val targetPackage: String?
) {
    companion object {
        private const val TAG = "CustomTabLauncher"
    }

    private var customTabsClient: CustomTabsClient? = null
    private var customTabsSession: CustomTabsSession? = null
    private var serviceConnection: CustomTabsServiceConnection? = null

    var isReady: Boolean = false
        private set

    private var pendingLaunch: (() -> Unit)? = null

    /**
     * Bind to CustomTabsService and create a session.
     * If there's a pending launch queued before service was ready, it will execute automatically.
     *
     * @return false if no Custom Tabs package is available.
     */
    fun bind(): Boolean {
        val packageName = targetPackage ?: CustomTabsClient.getPackageName(activity, emptyList())

        if (packageName == null) {
            Log.w(TAG, "No Custom Tabs package available")
            return false
        }

        serviceConnection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                customTabsClient = client
                client.warmup(0)
                customTabsSession = client.newSession(null)
                isReady = true

                pendingLaunch?.invoke()
                pendingLaunch = null
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                customTabsClient = null
                customTabsSession = null
                isReady = false
            }
        }

        CustomTabsClient.bindCustomTabsService(activity, packageName, serviceConnection!!)
        return true
    }

    /**
     * Launch CustomTabsIntent with the bound session.
     * If service is not yet connected, queues the launch for when it's ready.
     *
     * @return false only if launch failed immediately (no browser). true if launched or queued.
     */
    fun launch(
        uri: Uri,
        intentFlags: Int,
        toolbarColor: Int?,
        preferEphemeral: Boolean
    ): Boolean {
        if (isReady) {
            return doLaunch(uri, intentFlags, toolbarColor, preferEphemeral)
        }

        pendingLaunch = { doLaunch(uri, intentFlags, toolbarColor, preferEphemeral) }
        return true
    }

    fun unbind() {
        serviceConnection?.let {
            try {
                activity.unbindService(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding service: ${e.message}")
            }
        }
        serviceConnection = null
        customTabsClient = null
        customTabsSession = null
        isReady = false
        pendingLaunch = null
    }

    private fun doLaunch(
        uri: Uri,
        intentFlags: Int,
        toolbarColor: Int?,
        preferEphemeral: Boolean
    ): Boolean {
        val builder = CustomTabsIntent.Builder()
        customTabsSession?.let { builder.setSession(it) }

        // Styling
        val color = toolbarColor ?: ContextCompat.getColor(activity, R.color.toolbarColor)
        val colorSchemeParams = CustomTabColorSchemeParams.Builder().apply {
            setToolbarColor(color)
            setNavigationBarColor(color)
        }.build()
        builder.setDefaultColorSchemeParams(colorSchemeParams)
        builder.setShareState(CustomTabsIntent.SHARE_STATE_OFF)
        builder.setStartAnimations(activity, R.anim.slide_in_up, R.anim.fade_in)
        builder.setExitAnimations(activity, R.anim.fade_in, R.anim.slide_out_down)

        if (preferEphemeral) {
            try {
                // setEphemeralBrowsingEnabled not available in androidx.browser:1.8.0
                // builder.setEphemeralBrowsingEnabled(true)
                Log.d(LOG_TAG, "Ephemeral browsing enabled")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to enable ephemeral browsing: ${e.message}")
            }
        }

        val customTabsIntent = builder.build()
        customTabsIntent.intent.addFlags(intentFlags)
        targetPackage?.let { customTabsIntent.intent.setPackage(it) }

        return try {
            customTabsIntent.launchUrl(activity, uri)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "No browser available: ${e.message}")
            false
        }
    }
}
