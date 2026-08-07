package com.linusu.flutter_web_auth_2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.browser.auth.AuthTabCallback
import androidx.browser.auth.AuthTabIntent
import androidx.browser.auth.AuthTabSession
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

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

        // CustomTabsCallback navigation-event codes (shared by AuthTabCallback).
        private const val NAV_STARTED = 1
        private const val NAV_ABORTED = 4

        // Navigation events observed via the AuthTab session for the in-flight launch,
        // captured through a CONNECTED AuthTabSession (see launchAuthTabForResult). This is
        // the signal that distinguishes a genuine user cancel from a completed-but-swallowed
        // callback: the auth page load is one navigation (STARTED,TAB_SHOWN,FINISHED); when
        // the passkey ceremony completes the page redirects to the callback, producing a
        // SECOND NAVIGATION_STARTED and (for a custom-scheme callback) a NAVIGATION_ABORTED.
        // Measured on Moto Secure (interceptor ON): the second navigation is still reported
        // to the bound session even though the callback itself is severed. Cleared at launch.
        private val authTabNavEvents = mutableListOf<Int>()

        // Live CustomTabsService connection for the in-flight AuthTab, unbound in
        // onActivityResult to avoid leaking the ServiceConnection.
        private var authTabConnection: CustomTabsServiceConnection? = null

        private fun navName(e: Int): String = when (e) {
            1 -> "NAVIGATION_STARTED"
            2 -> "NAVIGATION_FINISHED"
            3 -> "NAVIGATION_FAILED"
            4 -> "NAVIGATION_ABORTED"
            5 -> "TAB_SHOWN"
            6 -> "TAB_HIDDEN"
            else -> "EVENT_$e"
        }

        // True when the observed navigation events indicate the auth page navigated AGAIN
        // after its initial load — i.e. a redirect to the callback was attempted, which only
        // happens once the passkey ceremony completed. A genuine cancel never reaches this.
        private fun authTabRedirectObserved(): Boolean {
            val startedCount = authTabNavEvents.count { it == NAV_STARTED }
            return startedCount >= 2 || authTabNavEvents.contains(NAV_ABORTED)
        }

        // Scheme of the in-flight host-activity AuthTab launch. Wiped on process
        // death together with `callbacks` — onActivityResult handles both being gone.
        private var pendingAuthTabScheme: String? = null

        // Original request of the in-flight AuthTab launch, kept so we can REPLAY it as a
        // CustomTabs flow if the AuthTab returns no result (RESULT_CANCELED / null data).
        // On interceptor OEMs (Moto Secure) a swallowed callback is delivered as exactly
        // that outcome — indistinguishable from a genuine user cancel — so the retry is
        // the only recovery available. See onActivityResult.
        private var pendingAuthTabRequest: PendingAuthRequest? = null

        // Schemes already retried-with-CustomTab in the current login attempt, so a retry
        // happens at most ONCE (a genuine cancel of the CustomTab must not loop). Cleared
        // for a scheme whenever a fresh `authenticate` starts for it.
        private val authTabRetriedSchemes = mutableSetOf<String>()

        data class PendingAuthRequest(
            val url: Uri,
            val callbackUrlScheme: String,
            val callbackHost: String?,
            val callbackPath: String?,
            val intentFlags: Int,
            val targetPackage: String?,
            val preferEphemeral: Boolean,
        )

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

                // Fresh attempt for this scheme — allow a retry-with-CustomTab again.
                authTabRetriedSchemes.remove(callbackUrlScheme)

                val targetPackage = findTargetBrowserPackageName(options)
                val preferEphemeral = options["preferEphemeral"] as Boolean? ?: false
                val useAuthTab = context?.shouldUseAuthTab(preferEphemeral, targetPackage) ?: true

                if (useAuthTab && LAUNCH_AUTH_TAB_FROM_HOST_ACTIVITY) {
                    // Remember the request so onActivityResult can replay it as CustomTabs
                    // if the AuthTab comes back with no result.
                    pendingAuthTabRequest = PendingAuthRequest(
                        url = url,
                        callbackUrlScheme = callbackUrlScheme,
                        callbackHost = options["httpsHost"] as String?,
                        callbackPath = callbackPath,
                        intentFlags = options["intentFlags"] as Int,
                        targetPackage = targetPackage,
                        preferEphemeral = preferEphemeral,
                    )
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

        // MEASUREMENT (corrected): to receive AuthTab navigation events, the callback binder
        // must be REGISTERED with the browser. A bare PendingSession writes a NULL binder into
        // the intent (AuthTabIntent.Builder.setPendingSession -> setSessionParameters(null, id)),
        // so no callback ever fires. The correct path is to bind the CustomTabsService, create a
        // CONNECTED session via newAuthTabSession (whose binder is registered), and setSession().
        // We fall back to launching without a session if the service does not connect in time,
        // so login is never blocked by the probe.
        authTabNavEvents.clear()

        val navCallback = object : AuthTabCallback {
            override fun onNavigationEvent(navigationEvent: Int, extras: Bundle) {
                authTabNavEvents.add(navigationEvent)
                Log.d("FIDO-DEBUG", "AuthTab nav event=$navigationEvent (${navName(navigationEvent)}) extras=${extras.keySet()?.toList()}")
            }

            override fun onExtraCallback(callbackName: String, args: Bundle) {
                Log.d("FIDO-DEBUG", "AuthTab extraCallback=$callbackName keys=${args.keySet()?.toList()}")
            }

            override fun onExtraCallbackWithResult(callbackName: String, args: Bundle): Bundle {
                Log.d("FIDO-DEBUG", "AuthTab extraCallbackWithResult=$callbackName")
                return Bundle()
            }

            override fun onWarmupCompleted(extras: Bundle) {
                Log.d("FIDO-DEBUG", "AuthTab warmupCompleted")
            }
        }

        val launched = AtomicBoolean(false)
        val bindPackage = targetPackage ?: CustomTabsClient.getPackageName(hostActivity, emptyList())

        val connection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: android.content.ComponentName, client: CustomTabsClient) {
                var session: AuthTabSession? = null
                try {
                    client.warmup(0)
                    session = client.newAuthTabSession(navCallback, ContextCompat.getMainExecutor(hostActivity))
                    Log.d("FIDO-DEBUG", "CustomTabsService connected ($name); newAuthTabSession created=${session != null}")
                } catch (e: Exception) {
                    Log.w("FIDO-DEBUG", "newAuthTabSession failed: ${e.message}")
                }
                if (launched.compareAndSet(false, true)) {
                    actuallyLaunchAuthTab(hostActivity, session, url, callbackUrlScheme, callbackHost, callbackPath, intentFlags, targetPackage, preferEphemeral)
                }
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                Log.d("FIDO-DEBUG", "CustomTabsService disconnected")
            }
        }

        var bound = false
        if (bindPackage != null) {
            try {
                bound = CustomTabsClient.bindCustomTabsService(hostActivity, bindPackage, connection)
                if (bound) authTabConnection = connection
                Log.d("FIDO-DEBUG", "bindCustomTabsService pkg=$bindPackage bound=$bound")
            } catch (e: Exception) {
                Log.w("FIDO-DEBUG", "bindCustomTabsService failed: ${e.message}")
            }
        } else {
            Log.d("FIDO-DEBUG", "No CustomTabs-capable package to bind")
        }

        if (!bound) {
            // Nothing to wait for — launch immediately without a session.
            if (launched.compareAndSet(false, true)) {
                Log.d("FIDO-DEBUG", "Not bound — launching AuthTab WITHOUT session")
                actuallyLaunchAuthTab(hostActivity, null, url, callbackUrlScheme, callbackHost, callbackPath, intentFlags, targetPackage, preferEphemeral)
            }
            return
        }

        // Fallback: if the service does not connect within the timeout, launch without a session.
        val timeoutMs = 1500L
        Handler(Looper.getMainLooper()).postDelayed({
            if (launched.compareAndSet(false, true)) {
                Log.d("FIDO-DEBUG", "Service bind timeout (${timeoutMs}ms) — launching AuthTab WITHOUT session")
                actuallyLaunchAuthTab(hostActivity, null, url, callbackUrlScheme, callbackHost, callbackPath, intentFlags, targetPackage, preferEphemeral)
            }
        }, timeoutMs)
    }

    private fun actuallyLaunchAuthTab(
        hostActivity: Activity,
        session: AuthTabSession?,
        url: Uri,
        callbackUrlScheme: String,
        callbackHost: String?,
        callbackPath: String?,
        intentFlags: Int,
        targetPackage: String?,
        preferEphemeral: Boolean,
    ) {
        val rawBuilder = AuthTabIntent.Builder()
        if (session != null) {
            rawBuilder.setSession(session)
            Log.d("FIDO-DEBUG", "AuthTab launching WITH connected session (binder registered)")
        } else {
            Log.d("FIDO-DEBUG", "AuthTab launching WITHOUT session (probe inactive)")
        }
        val builder = AuthTabBuilderWrapper(rawBuilder)
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

        val redirectObserved = authTabRedirectObserved()
        Log.d("FIDO-DEBUG", "onActivityResult resultCode=$resultCode (${if (resultCode == AuthTabIntent.RESULT_CANCELED) "CANCELED" else if (resultCode == AuthTabIntent.RESULT_OK) "OK" else "code$resultCode"}) uri=${data?.data} navEvents=${authTabNavEvents.map { navName(it) }} redirectObserved=$redirectObserved")

        // Unbind the CustomTabsService connection opened for this launch.
        authTabConnection?.let { conn ->
            try {
                activity?.unbindService(conn)
            } catch (e: Exception) {
                Log.w("FIDO-DEBUG", "unbindService failed: ${e.message}")
            }
        }
        authTabConnection = null

        val scheme = pendingAuthTabScheme
        pendingAuthTabScheme = null
        val request = pendingAuthTabRequest
        pendingAuthTabRequest = null

        // AuthTab returned WITHOUT a redirect URI (RESULT_CANCELED + null data). On an
        // interceptor OEM (Moto Secure / ESET LinkScanner …) this is the symptom of a
        // callback swallowed after the passkey completed — indistinguishable from a genuine
        // user cancel by the result code ALONE. We disambiguate with the navigation events
        // observed via the bound AuthTabSession: a second navigation / NAVIGATION_ABORTED
        // means the page redirected to the callback (ceremony completed) but the redirect
        // was severed. In that case only, retry the SAME login ONCE forcing CustomTabs,
        // whose redirect returns as a real OS deep link (-> MainActivity.handlePasskeyCallback)
        // that the interceptor cannot sever; the Dart callback stays pending across the retry.
        // A genuine cancel (single navigation, no redirect observed) falls through to
        // completeAuthTabResult and is reported as CANCELED — so there is no reopen-on-cancel.
        val act = activity
        if (resultCode == AuthTabIntent.RESULT_CANCELED && data?.data == null &&
            request != null && scheme != null && !authTabRetriedSchemes.contains(scheme) &&
            redirectObserved && act != null
        ) {
            authTabRetriedSchemes.add(scheme)
            Log.d(LOG_TAG, "AuthTab callback swallowed (redirect observed) — prompting user to retry with CustomTabs")
            promptRetryWithCustomTab(act, request, scheme, resultCode)
            return true
        }

        val caller = activity ?: context ?: return true
        completeAuthTabResult(caller, resultCode, data?.data, scheme)
        return true
    }

    /**
     * Ask the user (English dialog) whether to retry the login. Only OK triggers the
     * CustomTabs retry; Cancel completes the pending Dart callback as CANCELED. Shown only
     * when a swallowed-callback is detected (redirect observed but result CANCELED), so a
     * genuine cancel never sees this dialog.
     */
    private fun promptRetryWithCustomTab(
        activity: Activity,
        request: PendingAuthRequest,
        scheme: String,
        resultCode: Int,
    ) {
        android.app.AlertDialog.Builder(activity)
            .setMessage("Your device may be protected by a security app. Please log in again.")
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                Log.d(LOG_TAG, "User accepted retry — relaunching login with CustomTabs")
                retryWithCustomTab(request)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                Log.d(LOG_TAG, "User declined retry — reporting CANCELED")
                completeAuthTabResult(activity, resultCode, null, scheme)
            }
            .show()
    }

    /**
     * Replay a failed AuthTab login as a CustomTabs flow via the trampoline activity,
     * forcing CustomTabs regardless of browser version (KEY_FORCE_CUSTOM_TAB). The
     * CustomTabs redirect is delivered as an OS deep link — not an activity result — so
     * it survives interceptor OEMs that sever the AuthTab resultTo chain. The in-memory
     * Dart callback stays registered and is completed by MainActivity.handlePasskeyCallback.
     */
    private fun retryWithCustomTab(request: PendingAuthRequest) {
        val act = activity
        if (act == null) {
            Log.e(LOG_TAG, "retryWithCustomTab: no host activity available")
            removeCallback(request.callbackUrlScheme)
                ?.error("FAILED", "No foreground activity available for authentication.", null)
            return
        }
        act.startActivity(Intent(act, AuthenticationManagementActivity::class.java).apply {
            putExtra(AuthenticationManagementActivity.KEY_AUTH_URI, request.url)
            putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_INTENT_FLAGS, request.intentFlags)
            putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_TARGET_PACKAGE, request.targetPackage)
            putExtra(AuthenticationManagementActivity.KEY_AUTH_CALLBACK_SCHEME, request.callbackUrlScheme)
            putExtra(AuthenticationManagementActivity.KEY_AUTH_CALLBACK_HOST, request.callbackHost)
            putExtra(AuthenticationManagementActivity.KEY_AUTH_CALLBACK_PATH, request.callbackPath)
            putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_PREFER_EPHEMERAL, request.preferEphemeral)
            putExtra(AuthenticationManagementActivity.KEY_FORCE_CUSTOM_TAB, true)
        })
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
