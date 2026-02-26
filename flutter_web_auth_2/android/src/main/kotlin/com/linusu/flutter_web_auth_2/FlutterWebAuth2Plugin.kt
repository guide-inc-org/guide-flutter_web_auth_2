package com.linusu.flutter_web_auth_2

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.ContextCompat

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
        private const val SERVICE_CONNECT_TIMEOUT_MS = 800L

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

    // --- CustomTabsService fields ---
    private var customTabsClient: CustomTabsClient? = null
    private var customTabsSession: CustomTabsSession? = null
    private var isBound = false
    private var serviceConnection: CustomTabsServiceConnection? = null

    // Pending launch khi đang chờ service connect
    private var pendingLaunch: PendingLaunchData? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class PendingLaunchData(
        val url: Uri,
        val callbackUrlScheme: String,
        val options: Map<String, Any>,
    )

    // --- Init ---

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
        releaseCustomTabsService()
    }

    // --- CustomTabsService management ---

    private fun bindCustomTabsService(): Boolean {
        if (isBound) return true

        val ctx = context ?: return false
        val packageName = CustomTabsClient.getPackageName(ctx, null)
        if (packageName == null) {
            Log.w(LOG_TAG, "No browser supports Custom Tabs Service")
            return false
        }

        serviceConnection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(
                name: ComponentName,
                client: CustomTabsClient
            ) {
                customTabsClient = client
                client.warmup(0L)
                customTabsSession = client.newSession(CustomTabsCallback())

                Log.d(LOG_TAG, "CustomTabsService connected")

                // Nếu có pending launch → mở ngay
                pendingLaunch?.let { data ->
                    mainHandler.removeCallbacksAndMessages(null)
                    launchCustomTab(data.url, data.callbackUrlScheme, data.options)
                    pendingLaunch = null
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Log.w(LOG_TAG, "CustomTabsService disconnected")
                customTabsClient = null
                customTabsSession = null
                isBound = false
            }
        }

        return try {
            isBound = CustomTabsClient.bindCustomTabsService(
                ctx, packageName, serviceConnection!!
            )
            if (!isBound) {
                Log.w(LOG_TAG, "bindCustomTabsService returned false")
                serviceConnection = null
            }
            isBound
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "SecurityException binding service: ${e.message}")
            serviceConnection = null
            false
        }
    }

    private fun releaseCustomTabsService() {
        if (isBound && serviceConnection != null) {
            try {
                context?.unbindService(serviceConnection!!)
            } catch (e: IllegalArgumentException) {
                Log.w(LOG_TAG, "Service not registered: ${e.message}")
            }
        }
        customTabsClient = null
        customTabsSession = null
        serviceConnection = null
        isBound = false
        pendingLaunch = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    // --- Method handling ---

    override fun onMethodCall(call: MethodCall, resultCallback: Result) {
        when (call.method) {
            "authenticate" -> {
                if (activity == null) {
                    Log.e(LOG_TAG, "Failed to start authentication. Activity is null.")
                    resultCallback.error(
                        "NO_ACTIVITY",
                        "Cannot start authentication because Activity is null.",
                        null
                    )
                    return
                }

                val url = Uri.parse(call.argument("url"))
                val callbackUrlScheme: String = call.argument<String>("callbackUrlScheme")!!
                val options = call.argument<Map<String, Any>>("options")!!
                val callbackPath = options["httpsPath"] as? String

                // Register callback for both keys so either callback path works
                callbacks[callbackUrlScheme] = resultCallback
                if (callbackPath != null && callbackPath != callbackUrlScheme) {
                    callbacks[callbackPath] = resultCallback
                }

                // Chiến lược 2: bind service ngay trước khi mở Custom Tab
                if (customTabsClient != null) {
                    // Đã connected từ trước → mở luôn
                    launchCustomTab(url, callbackUrlScheme, options)
                } else if (bindCustomTabsService()) {
                    // Đang bind → lưu pending, đặt timeout
                    pendingLaunch = PendingLaunchData(url, callbackUrlScheme, options)

                    mainHandler.postDelayed({
                        // Timeout → mở không có session
                        if (pendingLaunch != null) {
                            Log.w(LOG_TAG, "Service connect timeout. Launching without session.")
                            val data = pendingLaunch!!
                            pendingLaunch = null
                            launchCustomTab(data.url, data.callbackUrlScheme, data.options)
                        }
                    }, SERVICE_CONNECT_TIMEOUT_MS)
                } else {
                    // Bind fail → mở không có session
                    Log.w(LOG_TAG, "Cannot bind service. Launching without session.")
                    launchCustomTab(url, callbackUrlScheme, options)
                }
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

    // --- Launch Custom Tab ---

    private fun launchCustomTab(
        url: Uri,
        callbackUrlScheme: String,
        options: Map<String, Any>
    ) {
        val currentActivity = activity
        if (currentActivity == null) {
            Log.e(LOG_TAG, "Activity is null when launching Custom Tab")
            val callback = removeCallback(callbackUrlScheme)
            callback?.error("NO_ACTIVITY", "Activity is null.", null)
            return
        }

        val customTabBuilder = CustomTabsIntent.Builder(customTabsSession)

        val colorSchemeParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(currentActivity, R.color.toolbarColor))
            .setNavigationBarColor(
                ContextCompat.getColor(currentActivity, R.color.navigationBarColor)
            )
            .build()

        customTabBuilder.setDefaultColorSchemeParams(colorSchemeParams)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setStartAnimations(currentActivity, R.anim.slide_in_bottom, R.anim.fade_out)
            .setExitAnimations(currentActivity, R.anim.fade_in, R.anim.slide_out_bottom)
            .setSendToExternalDefaultHandlerEnabled(true)

        val intent = customTabBuilder.build()

        val targetPackage = findTargetBrowserPackageName(options)
        intent.intent.addFlags(options["intentFlags"] as Int)
        if (targetPackage != null) {
            intent.intent.setPackage(targetPackage)
        }

        try {
            Log.d(LOG_TAG, "Launching Custom Tab (session=${customTabsSession != null}): $url")
            intent.launchUrl(currentActivity, url)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(LOG_TAG, "No browser available (Activity not found)")
            val callback = removeCallback(callbackUrlScheme)
            callback?.error(
                "NO_BROWSER",
                "No valid browser available for authentication.",
                e.message
            )
        }
    }

    // --- Activity lifecycle ---

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
        releaseCustomTabsService()
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
