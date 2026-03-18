package com.linusu.flutter_web_auth_2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient

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
         * Tracks whether the current auth flow uses AuthTab (true) or CustomTab (false).
         * Used by CallbackActivity to decide whether to notify AuthenticationManagementActivity.
         */
        var usingAuthTab: Boolean = false
    }

    private var customTabLauncher: CustomTabLauncher? = null

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
                val targetPackage = findTargetBrowserPackageName(options)
                val preferEphemeral = options["preferEphemeral"] as Boolean? ?: false

                // Cancel previous auth with same scheme if exists
                callbacks.remove(callbackUrlScheme)?.error("CANCELED", "New authentication started", null)

                callbacks[callbackUrlScheme] = resultCallback

                if (shouldUseAuthTabs(context!!, preferEphemeral, targetPackage)) {
                    // AuthTab: needs Activity for registerActivityResultLauncher
                    usingAuthTab = true
                    activity?.startActivity(Intent(activity, AuthenticationManagementActivity::class.java).apply {
                        putExtra(AuthenticationManagementActivity.KEY_AUTH_URI, url)
                        putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_INTENT_FLAGS, options["intentFlags"] as Int)
                        putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_TARGET_PACKAGE, targetPackage)
                        putExtra(AuthenticationManagementActivity.KEY_AUTH_CALLBACK_SCHEME, callbackUrlScheme)
                        putExtra(AuthenticationManagementActivity.KEY_AUTH_CALLBACK_HOST, options["httpsHost"] as String?)
                        putExtra(AuthenticationManagementActivity.KEY_AUTH_CALLBACK_PATH, options["httpsPath"] as String?)
                        putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_PREFER_EPHEMERAL, preferEphemeral)
                        putExtra(AuthenticationManagementActivity.KEY_AUTH_OPTION_TOOLBAR_COLOR, (options["toolbarColor"] as? Number)?.toInt())
                    })
                } else {
                    // CustomTab: launch directly from Flutter activity (no intermediate Activity)
                    usingAuthTab = false
                    launchCustomTab(
                        url = url,
                        callbackUrlScheme = callbackUrlScheme,
                        intentFlags = options["intentFlags"] as Int,
                        targetPackage = targetPackage,
                        preferEphemeral = preferEphemeral,
                        toolbarColor = (options["toolbarColor"] as? Number)?.toInt()
                    )
                }
            }

            "cleanUpDanglingCalls" -> {
                // Skip cleanup if CustomTab session is active — session must stay
                // alive for applinks redirect to work when user returns to CCT
                if (customTabLauncher == null) {
                    callbacks.forEach { (_, danglingResultCallback) ->
                        danglingResultCallback.error("CANCELED", "User canceled login", null)
                    }
                    callbacks.clear()
                }
                resultCallback.success(null)
            }

            else -> resultCallback.notImplemented()
        }
    }

    private fun launchCustomTab(
        url: Uri,
        callbackUrlScheme: String,
        intentFlags: Int,
        targetPackage: String?,
        preferEphemeral: Boolean,
        toolbarColor: Int?
    ) {
        val currentActivity = activity
        if (currentActivity == null) {
            callbacks.remove(callbackUrlScheme)?.error("NO_ACTIVITY", "No activity available", null)
            return
        }

        // Clean up previous launcher if any
        cleanUpLauncher()

        customTabLauncher = CustomTabLauncher(currentActivity, targetPackage)

        val bound = customTabLauncher?.bind() ?: false
        if (!bound) {
            callbacks.remove(callbackUrlScheme)?.error("NO_BROWSER", "No Custom Tabs package available.", null)
            cleanUpLauncher()
            return
        }

        val launched = customTabLauncher?.launch(
            uri = url,
            intentFlags = intentFlags,
            toolbarColor = toolbarColor,
            preferEphemeral = preferEphemeral
        ) ?: false

        if (!launched) {
            callbacks.remove(callbackUrlScheme)?.error("NO_BROWSER", "No valid browser available for authentication.", null)
            cleanUpLauncher()
        }
    }

    private fun cleanUpLauncher() {
        customTabLauncher?.unbind()
        customTabLauncher = null
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
        cleanUpLauncher()
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
