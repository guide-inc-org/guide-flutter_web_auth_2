package com.linusu.flutter_web_auth_2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

class FlutterWebAuth2Plugin(
    private var context: Context? = null,
    private var channel: MethodChannel? = null,
    private var activity: Activity? = null,
) : MethodCallHandler, FlutterPlugin, ActivityAware {
    companion object {
        val callbacks = mutableMapOf<String, Result>()

        /**
         * Minimize state of the in-flight Custom Tab (picture-in-picture). Only relevant to the
         * Custom Tab flow; the AuthTab flow never touches it. Set from the [customTabsCallback]
         * in [AuthenticationManagementActivity] and read by [cleanUpDanglingCalls] to avoid
         * cancelling while the tab is only minimized (auth still ongoing).
         */
        @Volatile
        var customTabMinimized: CustomTabState = CustomTabState.NONE

        /**
         * True while a Custom Tab (not AuthTab) auth is in flight. Used to bring the host app
         * back to the foreground after the redirect, since a minimized Custom Tab is no longer
         * cleared by the CLEAR_TOP response intent.
         */
        @Volatile
        var customTabInProgress = false

        /** Active Custom Tabs service binding, kept so it can be released when auth ends. */
        var customTabsConnection: CustomTabsServiceConnection? = null

        /**
         * onMinimized can arrive just after the app resumes, so we wait this long before
         * treating a dangling call as cancelled and re-check the minimized state.
         */
        private const val CLEANUP_DELAY_MS = 700L

        /** Cancel every pending authenticate() call with CANCELED. This is upstream behavior. */
        fun cancelAllCallbacks() {
            callbacks.forEach { (_, danglingResultCallback) ->
                danglingResultCallback.error("CANCELED", "User canceled login", null)
            }
            callbacks.clear()
        }

        /** Whether the Custom Tab is currently minimized (picture-in-picture). */
        fun isCustomTabMinimized(): Boolean = (customTabMinimized == CustomTabState.MINIMIZED)

        /** Release the Custom Tabs service binding and reset the Custom Tab state. */
        @SuppressLint("UnsafeOptInUsageError")
        fun releaseCustomTabsSession(context: Context) {
            // Note: customTabInProgress is intentionally NOT reset here. Cleaning up dangling
            // calls must not clear it, or a redirect arriving right after cleanup would find the
            // flag off and skip bringing the host app forward. It is reset in returnToHostApp.
            customTabMinimized = CustomTabState.NONE
            customTabsConnection?.let {
                try {
                    context.applicationContext.unbindService(it)
                } catch (e: Exception) {
                    // Already unbound or never bound; nothing to do.
                }
            }
            customTabsConnection = null
        }

        // ===== Cold-start recovery (process killed while the browser was open) =====

        /** Encrypted store for a redirect that arrived when no live completer existed. */
        private const val RECOVERY_PREFS = "flutter_web_auth_2_recovery"
        private const val KEY_PENDING_URL = "pending_redirect_url"
        private const val KEY_PENDING_TS = "pending_redirect_ts"

        /** A persisted redirect older than this is ignored (auth codes are short-lived). */
        private const val PENDING_TTL_MS = 5 * 60 * 1000L

        private fun recoveryPrefs(context: Context): SharedPreferences? {
            // EncryptedSharedPreferences needs the AES-256 Android Keystore (API 23+).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
            return try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    RECOVERY_PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Exception) {
                // Keystore corruption / unavailable: skip recovery rather than crash the auth flow.
                Log.w(LOG_TAG, "Recovery store unavailable: ${e.message}")
                null
            }
        }

        /**
         * Persist a redirect URL that arrived with no live [callbacks] entry — the process was
         * killed while the browser was open, so the Dart completer is gone. The app reads it back
         * with [consumePendingRedirect] on its next start to resume login. Written with commit()
         * because the caller finishes immediately and the process may be killed again right after.
         */
        fun persistPendingRedirect(context: Context, url: String) {
            val prefs = recoveryPrefs(context) ?: return
            prefs.edit()
                .putString(KEY_PENDING_URL, url)
                .putLong(KEY_PENDING_TS, System.currentTimeMillis())
                .commit()
        }

        /** Read and clear the persisted redirect. Null if none, or older than [PENDING_TTL_MS]. */
        fun consumePendingRedirect(context: Context): String? {
            val prefs = recoveryPrefs(context) ?: return null
            val url = prefs.getString(KEY_PENDING_URL, null)
            val ts = prefs.getLong(KEY_PENDING_TS, 0L)
            prefs.edit().clear().commit() // one-shot: never replay
            return if (url != null && System.currentTimeMillis() - ts <= PENDING_TTL_MS) url else null
        }
    }

    private fun initInstance(messenger: BinaryMessenger, context: Context) {
        this.context = context
        channel = MethodChannel(messenger, "flutter_web_auth_2")
        channel?.setMethodCallHandler(this)
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        initInstance(binding.binaryMessenger, binding.applicationContext)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = null
        channel = null
    }

    override fun onMethodCall(call: MethodCall, resultCallback: Result) {
        when (call.method) {
            "authenticate" -> {
                val url = Uri.parse(call.argument("url"))
                val callbackUrlScheme: String = call.argument<String>("callbackUrlScheme")!!
                val options = call.argument<Map<String, Any>>("options")!!

                callbacks[callbackUrlScheme] = resultCallback
                activity?.startActivity(Intent(activity, AuthenticationManagementActivity::class.java).apply {
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_URI, url)
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_INTENT_FLAGS, options["intentFlags"] as Int)
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_TARGET_PACKAGE, findTargetBrowserPackageName(options))
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_CALLBACK_SCHEME, callbackUrlScheme)
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_CALLBACK_HOST, options["httpsHost"] as String?)
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_CALLBACK_PATH, options["httpsPath"] as String?)
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_PREFER_EPHEMERAL, options["preferEphemeral"] as Boolean? ?: false)
                })
            }

            "cleanUpDanglingCalls" -> {
                if (customTabInProgress) {
                    // ===== Custom Tab flow ONLY =====
                    // The lifecycle observer calls this on every app resume. During a Custom Tab
                    // login a resume does not necessarily mean the user gave up (they may have just
                    // minimized the tab), so we must not cancel blindly.
                    when {
                        // Tab is minimized (picture-in-picture): auth is still ongoing. Keep waiting.
                        isCustomTabMinimized() -> {}

                        // Otherwise this may be a real dismissal (back/swipe). onMinimized can land
                        // just after the resume, so wait briefly and re-check before cancelling.
                        else -> {
                            val appContext = context?.applicationContext
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (customTabInProgress && !isCustomTabMinimized()) {
                                    cancelAllCallbacks()
                                    appContext?.let { releaseCustomTabsSession(it) }
                                }
                            }, CLEANUP_DELAY_MS)
                        }
                    }
                } else {
                    // ===== AuthTab / no auth in progress: unchanged upstream behavior =====
                    // AuthTab delivers its own RESULT_OK/RESULT_CANCELED, so upstream just cancels
                    // any dangling call on resume. Keep that exact behavior.
                    cancelAllCallbacks()
                }
                resultCallback.success(null)
            }

            "isCustomTabMinimized" -> {
                resultCallback.success(isCustomTabMinimized())
            }

            "consumePendingRedirect" -> {
                val ctx = context
                resultCallback.success(if (ctx != null) consumePendingRedirect(ctx) else null)
            }

            else -> resultCallback.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    /**
     * Find Support CustomTabs Browser.
     *
     * Priority:
     * 1. Custom Browser Order (if supported)
     * 2. default Browser
     * 3. Installed Browsers (if supported)
     * 4. null (System backup aka. some obscure browser that may work)
     */
    private fun findTargetBrowserPackageName(options: Map<String, Any>): String? {
        val context = requireNotNull(context) { "Context is null" }

        val selectedPackage = (options["customTabsPackageOrder"] as? Iterable<*>)
            ?.mapNotNull { (it as? String)?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.firstOrNull { isSupportCustomTabs(it) }

        if (selectedPackage != null) {
            return selectedPackage
        }

        // Check default browser
        val defaultBrowserSupported = CustomTabsClient.getPackageName(context, emptyList<String>())
        if (defaultBrowserSupported != null) {
            return defaultBrowserSupported
        }
        // Check installed browser
        val matchedBrowser = getInstalledBrowsers().firstOrNull { isSupportCustomTabs(it) }

        // Don't fall back to Chrome here. It is not installed anyway because it would already be in matchedBrowser.
        // Instead, fall back to null so we can use the system backup (if one is available).
        return matchedBrowser
    }

    private fun getInstalledBrowsers(): List<String> {
        val context = requireNotNull(context) { "Context is null" }

        // Get all apps that can handle VIEW intents
        val activityIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
        val viewIntentHandlers = context.getPackagesForIntent(activityIntent)

        val preferredKnownBrowsers = listOf(
            PackageNames.CHROME_STABLE,
            PackageNames.CHROME_BETA,
            PackageNames.SAMSUNG_INTERNET,
            PackageNames.MICROSOFT_EDGE,
            PackageNames.FIREFOX,
            PackageNames.CHROME_DEV,
        )

        val preferred = mutableListOf<String>()
        val others = mutableListOf<String>()
        val pushToEnd = mutableListOf<String>() // for the least-favorite apps

        for (pkg in viewIntentHandlers) {
            if (preferredKnownBrowsers.contains(pkg)) preferred += pkg
            else others += pkg
        }

        preferred.sortBy { preferredKnownBrowsers.indexOf(it) }

        return buildList {
            addAll(preferred)
            addAll(others)
            addAll(pushToEnd)
        }
    }

    private fun isSupportCustomTabs(packageName: String): Boolean {
        val value = CustomTabsClient.getPackageName(
            context!!,
            arrayListOf(packageName),
            true
        )
        return value == packageName
    }
}

enum class CustomTabState {
    NONE,
    MINIMIZED,
    UNMINIMIZED
}
