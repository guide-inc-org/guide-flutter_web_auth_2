package com.linusu.flutter_web_auth_2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

class FlutterWebAuth2Plugin(
    private var context: Context? = null,
    private var channel: MethodChannel? = null,
    private var activity: Activity? = null,
) : MethodCallHandler, FlutterPlugin, ActivityAware, PluginRegistry.ActivityResultListener {
    companion object {
        val callbacks = mutableMapOf<String, Result>()

        // Option D: launch the AuthTab with the HOST activity (MainActivity, the task
        // root) as the activity-result target instead of the translucent trampoline
        // AuthenticationManagementActivity. On aggressive OEMs (e.g. MIUI) the
        // trampoline's ActivityRecord is force-removed on process death ("app died,
        // no saved state"), so the AuthTab result is delivered to a dead task (t-1)
        // and the auth token is dropped. The root record survives the same kill, so
        // results targeted at it are re-delivered after the process is recreated.
        // Set to false to roll back to the trampoline-based launch.
        const val LAUNCH_AUTH_TAB_FROM_HOST_ACTIVITY = true

        // Above 0xFFFF on purpose: plugins conventionally pick manual request codes in
        // the 16-bit range, and geolocator even draws RANDOM codes via
        // SecureRandom.nextInt(1 << 16) — staying outside 0..65535 makes a collision
        // structurally impossible. Safe because the host activity is a plain
        // android.app.Activity (the "lower 16 bits only" limit is a FragmentActivity
        // constraint). Verify this again if MainActivity ever migrates to
        // FlutterFragmentActivity.
        const val AUTH_TAB_REQUEST_CODE = 0x00015777

        // Scheme of the in-flight host-activity AuthTab launch. Wiped on process
        // death together with `callbacks` — onActivityResult handles both being gone.
        private var pendingAuthTabScheme: String? = null

        fun removeCallback(key: String): Result? {
            val callback = callbacks.remove(key)
            if (callback != null) {
                // Remove all other entries with the same callback to prevent duplicate calls
                callbacks
                    .filterValues { it == callback }
                    .keys
                    .toList()
                    .forEach { callbacks.remove(it) }
            }
            return callback
        }
    }

    private var activityBinding: ActivityPluginBinding? = null

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
                val callbackPath = options["httpsPath"] as? String

                // Register callback for both keys so either callback path works
                callbacks[callbackUrlScheme] = resultCallback
                if (callbackPath != null && callbackPath != callbackUrlScheme) {
                    callbacks[callbackPath] = resultCallback
                }

                val targetPackage = findTargetBrowserPackageName(options)
                val preferEphemeral = options["preferEphemeral"] as Boolean? ?: false
                val useAuthTab = context?.shouldUseAuthTab(preferEphemeral, targetPackage) ?: true

                if (useAuthTab && LAUNCH_AUTH_TAB_FROM_HOST_ACTIVITY) {
                    // Option D: AuthTab result targets the host activity's record.
                    launchAuthTabForResult(
                        url = url,
                        callbackUrlScheme = callbackUrlScheme,
                        callbackHost = options["httpsHost"] as String?,
                        callbackPath = callbackPath,
                        intentFlags = options["intentFlags"] as Int,
                        targetPackage = targetPackage,
                        preferEphemeral = preferEphemeral,
                    )
                    return
                }

                // Custom Tabs fallback (old browsers) keeps the trampoline path: its
                // result comes back as an OS deep link, not an activity result, so it
                // is not affected by the trampoline record being dropped.
                activity?.startActivity(Intent(activity, AuthenticationManagementActivity::class.java).apply {
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_URI, url)
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_INTENT_FLAGS, options["intentFlags"] as Int)
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_TARGET_PACKAGE, targetPackage)
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_CALLBACK_SCHEME, callbackUrlScheme)
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_CALLBACK_HOST, options["httpsHost"] as String?)
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_CALLBACK_PATH, callbackPath)
                    putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_PREFER_EPHEMERAL, preferEphemeral)
                })
            }

            "cleanUpDanglingCalls" -> {
                // Use IdentityHashMap to track processed Result instances by reference.
                // The same Result callback may be registered under multiple keys
                // (e.g., both callbackUrlScheme and callbackPath), so we must ensure
                // .error() is only called once per unique Result instance.
                val processedResults = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Result, Boolean>())
                callbacks.values.forEach { danglingResultCallback ->
                    if (processedResults.add(danglingResultCallback)) {
                        danglingResultCallback.error("CANCELED", "User canceled login", null)
                    }
                }
                callbacks.clear()
                resultCallback.success(null)
            }

            else -> resultCallback.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        // Receive the AuthTab activity result on the host activity (Option D). Must be
        // registered here (during onCreate of the recreated activity) so it is in place
        // before the framework re-delivers a pending result after process death.
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
        activity = null
    }

    /**
     * Option D launch: build the AuthTab intent exactly like
     * AuthTabIntent.launch(launcher, ...) does (setData + redirect extras; the androidx
     * launcher is just startActivityForResult under the hood) and start it from the
     * host activity so the activity result targets the task-root record.
     */
    private fun launchAuthTabForResult(
        url: Uri,
        callbackUrlScheme: String,
        callbackHost: String?,
        callbackPath: String?,
        intentFlags: Int,
        targetPackage: String?,
        preferEphemeral: Boolean,
    ) {
        val hostActivity = activity
        if (hostActivity == null) {
            Log.e(LOG_TAG, "launchAuthTabForResult: no host activity available")
            removeCallback(callbackUrlScheme)?.error("FAILED", "No foreground activity available for authentication.", null)
            return
        }

        val builder = AuthTabBuilderWrapper(AuthTabIntent.Builder())
        if (preferEphemeral) {
            try {
                builder.setEphemeralBrowsingEnabled(true)
                Log.d(LOG_TAG, "Ephemeral browsing enabled")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to enable ephemeral browsing: ${e.message}")
            }
        }

        val tabIntent = builder.build(hostActivity).intent
        tabIntent.addFlags(intentFlags)
        if (targetPackage != null) {
            tabIntent.setPackage(targetPackage)
        }
        tabIntent.data = url
        if (callbackUrlScheme == "https" && callbackHost != null && callbackPath != null) {
            Log.d(LOG_TAG, "Using https host and path: $callbackHost, $callbackPath")
            tabIntent.putExtra(AuthTabIntent.EXTRA_HTTPS_REDIRECT_HOST, callbackHost)
            tabIntent.putExtra(AuthTabIntent.EXTRA_HTTPS_REDIRECT_PATH, callbackPath)
        } else {
            Log.d(LOG_TAG, "Using custom scheme: $callbackUrlScheme")
            tabIntent.putExtra(AuthTabIntent.EXTRA_REDIRECT_SCHEME, callbackUrlScheme)
        }

        pendingAuthTabScheme = callbackUrlScheme
        try {
            hostActivity.startActivityForResult(tabIntent, AUTH_TAB_REQUEST_CODE)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(LOG_TAG, "Failed to start authentication. No browser available (Activity not found)")
            pendingAuthTabScheme = null
            removeCallback(callbackUrlScheme)?.error("NO_BROWSER", "No valid browser available for authentication.", e.message)
        }
    }

    /**
     * AuthTab result, delivered to the host activity's record. Runs both in the live
     * process (normal flow) and after process death, when the framework re-delivers
     * the pending result to the recreated host activity (Case 2 recovery) — in that
     * case `callbacks`/`pendingAuthTabScheme` are gone; completeAuthTabResult handles
     * both situations (see Utils.kt).
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != AUTH_TAB_REQUEST_CODE) return false

        val scheme = pendingAuthTabScheme
        pendingAuthTabScheme = null
        val caller = activity ?: context ?: return true
        completeAuthTabResult(caller, resultCode, data?.data, scheme)
        return true
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
