package com.linusu.flutter_web_auth_2

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.auth.AuthTabIntent.AuthResult
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("UnsafeOptInUsageError", "UnsafeOptInUsageWarning")
class AuthenticationManagementActivity : ComponentActivity() {
    companion object {
        const val KEY_AUTH_STARTED: String = "authStarted"
        const val KEY_AUTH_URI: String = "authUri"
        const val KEY_AUTH_OPTION_INTENT_FLAGS: String = "authOptionsIntentFlags"
        const val KEY_AUTH_OPTION_TARGET_PACKAGE: String = "authOptionsTargetPackage"
        const val KEY_AUTH_OPTION_PREFER_EPHEMERAL: String = "authOptionsPreferEphemeral"
        const val KEY_AUTH_CALLBACK_SCHEME: String = "authCallbackScheme"
        const val KEY_AUTH_CALLBACK_HOST: String = "authCallbackHost"
        const val KEY_AUTH_CALLBACK_PATH: String = "authCallbackPath"

        /** Fallback timeout before launching without a session if the service never connects. */
        private const val SERVICE_CONNECT_TIMEOUT_MS = 1500L

        fun createResponseHandlingIntent(context: Context): Intent {
            val intent = Intent(context, AuthenticationManagementActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return intent
        }
    }

    private var authStarted: Boolean = false
    private lateinit var authenticationUri: Uri
    private var intentFlags: Int = 0
    private var targetPackage: String? = null
    private var preferEphemeral: Boolean = false
    private lateinit var callbackScheme: String
    private var callbackHost: String? = null
    private var callbackPath: String? = null

    private lateinit var authLauncher: ActivityResultLauncher<Intent>

    /** Guards against launching the tab twice (service connection vs. timeout fallback). */
    private val tabLaunched = AtomicBoolean(false)

    /**
     * Receives Custom Tab lifecycle callbacks. We only track minimize/unminimize so the
     * lifecycle-based [FlutterWebAuth2Plugin.cleanUpDanglingCalls] does not cancel the call
     * while the tab is minimized (auth is still ongoing).
     */
    private val customTabsCallback = object : CustomTabsCallback() {
        override fun onMinimized(extras: Bundle) {
            FlutterWebAuth2Plugin.customTabMinimized = CustomTabState.MINIMIZED
        }

        override fun onUnminimized(extras: Bundle) {
            FlutterWebAuth2Plugin.customTabMinimized = CustomTabState.UNMINIMIZED
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register the activity result launcher
        authLauncher = AuthTabIntent.registerActivityResultLauncher(this, this::handleAuthResult)

        if (savedInstanceState == null) {
            extractState(intent.extras)
        } else {
            extractState(savedInstanceState)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // The response-handling intent carries no extras (see createResponseHandlingIntent).
        // A minimize never delivers a new intent here, so this only runs on the redirect: bring
        // the host app forward to clear a Custom Tab that survived picture-in-picture, then finish.
        if (FlutterWebAuth2Plugin.customTabInProgress && intent.extras == null) {
            returnToHostAppIfCustomTab()
            finish()
        }
    }

    private fun handleAuthResult(result: AuthResult) {
        val callback = FlutterWebAuth2Plugin.callbacks[callbackScheme]
        if (callback == null) {
            // Cold-start recovery: the process was killed while the Auth Tab was open, so the Dart
            // completer is gone. The AndroidX ActivityResultRegistry still redelivered the result to
            // this freshly recreated activity — persist a successful redirect so the app can resume
            // login on its next start (see FlutterWebAuth2Plugin.consumePendingRedirect).
            if (result.resultCode == AuthTabIntent.RESULT_OK) {
                result.resultUri?.let {
                    FlutterWebAuth2Plugin.persistPendingRedirect(applicationContext, it.toString())
                }
            }
            finish()
            return
        }

        when (result.resultCode) {
            AuthTabIntent.RESULT_OK -> {
                val uri = result.resultUri
                if (uri != null) {
                    callback.success(uri.toString())
                } else {
                    callback.error("FAILED", "Authentication returned no URI", null)
                }
            }

            AuthTabIntent.RESULT_CANCELED -> {
                callback.error("CANCELED", "User canceled authentication", null)
            }

            else -> {
                callback.error("FAILED", "Authentication failed with code: ${result.resultCode}", null)
            }
        }

        FlutterWebAuth2Plugin.callbacks.remove(callbackScheme)
        finish()
    }

    override fun onResume() {
        super.onResume()

        if (!authStarted) {

            val useAuthTabs = shouldUseAuthTabs()

            FlutterWebAuth2Plugin.customTabInProgress = !useAuthTabs
            if (!useAuthTabs) {
                // ===== Custom Tab flow: mark in-progress and reset the minimize state so the
                // resume/cleanup handling keeps the login alive across a minimize (PiP). =====
                FlutterWebAuth2Plugin.customTabMinimized = CustomTabState.NONE

                // With a known browser package, bind a Custom Tabs session to receive
                // minimize/unminimize callbacks. Without one, fall through to the plain launch below.
                if (targetPackage != null) {
                    launchCustomTabWithSession(targetPackage!!)
                    authStarted = true
                    return
                }
            }

            val intentBuilder = if (useAuthTabs) {
                Log.d(LOG_TAG, "Using AuthTabIntent")
                AuthTabBuilderWrapper(AuthTabIntent.Builder())
            } else {
                Log.d(LOG_TAG, "Using CustomTabsIntent")
                CtBuilderWrapper(CustomTabsIntent.Builder())
            }

            // Set ephemeral browsing if requested and supported
            if (preferEphemeral) {
                try {
                    intentBuilder.setEphemeralBrowsingEnabled(true)
                    Log.d(LOG_TAG, "Ephemeral browsing enabled")
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Failed to enable ephemeral browsing: ${e.message}")
                }
            }

            val intent = intentBuilder.build()

            intent.intent.addFlags(intentFlags)
            if (targetPackage != null) {
                intent.intent.setPackage(targetPackage)
            }

            try {
                if (callbackScheme == "https" && callbackHost != null && callbackPath != null) {
                    Log.d(LOG_TAG, "Using https host and path: $callbackHost, $callbackPath")
                    intent.launch(this, authLauncher, authenticationUri, callbackHost!!, callbackPath!!)
                } else {
                    Log.d(LOG_TAG, "Using custom scheme: $callbackScheme")
                    intent.launch(this, authLauncher, authenticationUri, callbackScheme)
                }
            } catch (e: android.content.ActivityNotFoundException){
                handleNoBrowser(e)
            }

            authStarted = true
            return
        }
        /* If the authentication was already started and we've returned here, the user either
         * completed or cancelled authentication.
         * Either way we want to return to our original flutter activity, so just finish here
         */
        finish()
    }

    /**
     * Launches a Custom Tab attached to a [CustomTabsSession] so the [customTabsCallback]
     * receives minimize/unminimize events. The service connection is kept in
     * [FlutterWebAuth2Plugin.customTabsConnection] (bound to the application context) so it
     * survives this activity finishing while the tab is minimized.
     */
    private fun launchCustomTabWithSession(packageName: String) {
        val connection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(
                name: ComponentName,
                client: CustomTabsClient
            ) {
                try {
                    client.warmup(0L)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Custom Tabs warmup failed: ${e.message}")
                }

                // A null session just means no minimize detection; launch either way.
                val session = client.newSession(customTabsCallback)
                if (session == null) {
                    Log.w(
                        LOG_TAG,
                        "Could not create Custom Tabs session; launching without minimize detection"
                    )
                }
                launchCustomTab(session)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // Required override; the binding is released in releaseCustomTabsSession.
            }
        }

        FlutterWebAuth2Plugin.customTabsConnection = connection

        val bound = try {
            CustomTabsClient.bindCustomTabsService(applicationContext, packageName, connection)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to bind Custom Tabs service: ${e.message}")
            false
        }

        if (!bound) {
            Log.d(LOG_TAG, "Custom Tabs service unavailable; launching without minimize detection")
            FlutterWebAuth2Plugin.customTabsConnection = null
            launchCustomTab()
            return
        }

        // Safety net: if the service never connects, fall back to a plain launch so the
        // user is never left without a tab.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!tabLaunched.get()) {
                Log.w(
                    LOG_TAG,
                    "Custom Tabs service did not connect in time; launching without minimize detection"
                )
                launchCustomTab()
            }
        }, SERVICE_CONNECT_TIMEOUT_MS)
    }

    /**
     * Launches the Custom Tab. With a [session] the tab reports minimize/unminimize events, so
     * the login is not treated as cancelled while the tab is minimized (picture-in-picture).
     * Without a session it is a plain Custom Tab with no minimize detection.
     */
    private fun launchCustomTab(session: CustomTabsSession? = null) {
        if (!tabLaunched.compareAndSet(false, true)) return

        val builder = CustomTabsIntent.Builder(session)

        // Set ephemeral browsing if requested and supported
        if (preferEphemeral) {
            try {
                builder.setEphemeralBrowsingEnabled(true)
                Log.d(LOG_TAG, "Ephemeral browsing enabled")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to enable ephemeral browsing: ${e.message}")
            }
        }

        val customTabsIntent = builder.build()
        customTabsIntent.intent.addFlags(intentFlags)
        targetPackage?.let { customTabsIntent.intent.setPackage(it) }
        try {
            customTabsIntent.launchUrl(this, authenticationUri)
            FlutterWebAuth2Plugin.customTabMinimized = CustomTabState.UNMINIMIZED
        } catch (e: ActivityNotFoundException) {
            handleNoBrowser(e)
        }
    }

    private fun handleNoBrowser(e: ActivityNotFoundException) {
        Log.e(LOG_TAG, "Failed to start authentication. No browser available (Activity not found)")
        val callback = FlutterWebAuth2Plugin.callbacks[callbackScheme]
        callback?.error("NO_BROWSER", "No valid browser available for authentication.", e.message)
        FlutterWebAuth2Plugin.callbacks.remove(callbackScheme)
        finish()
    }

    /**
     * After a Custom Tab redirect, bring the host app back to the foreground. This is only
     * needed for the Custom Tab flow: if the user minimized the tab (picture-in-picture), the
     * CLEAR_TOP response intent no longer tears the tab down, so without this the Custom Tab
     * re-expands on top of the app. No-op for the AuthTab flow.
     */
    private fun returnToHostAppIfCustomTab() {
        if (!FlutterWebAuth2Plugin.customTabInProgress) return
        FlutterWebAuth2Plugin.customTabInProgress = false

        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                // CLEAR_TOP also tears down a Custom Tab left above the host activity (e.g. one
                // that went through picture-in-picture, which the response intent's CLEAR_TOP no
                // longer removes). SINGLE_TOP delivers onNewIntent instead of recreating the host.
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                Log.d(LOG_TAG, "Returning to host app after Custom Tab redirect")
                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to return to host app: ${e.message}")
        }
    }

    fun shouldUseAuthTabs(): Boolean {

        // Auth Tab is only used when support is positively confirmed:
        //  - No target package -> support cannot be verified, so fall back to Custom Tab.
        //  - A concrete target must declare the AuthTab category on its CustomTabsService
        //    intent-filter; otherwise launching an AuthTabIntent at it would fail.
        // (The version checks below only guard the ephemeral path.)
        val pkg = targetPackage
        if (pkg == null) {
            Log.d(LOG_TAG, "No target package; using Custom Tab")
            return false
        }
        if (!isAuthTabSupported(pkg)) {
            Log.d(LOG_TAG, "Auth Tab not supported by $pkg; using Custom Tab")
            return false
        }

        if (!preferEphemeral) return true
        val packageMajorVersion = getInstalledVersion(targetPackage!!)?.substringBefore(".")?.toIntOrNull() ?: 0
        Log.d(LOG_TAG, "Chosen package: $targetPackage with version: $packageMajorVersion")

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
    }

    /**
     * Whether [packageName] supports the Auth Tab API. Auth-Tab-capable browsers declare the
     * [CustomTabsService.CATEGORY_AUTH_TAB] category on their `CustomTabsService` intent-filter,
     * so a service query scoped to that package + category returns a match only when Auth Tab is
     * supported.
     */
    private fun isAuthTabSupported(packageName: String): Boolean {
        val intent = Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).apply {
            setPackage(packageName)
            addCategory(CustomTabsService.CATEGORY_AUTH_TAB)
        }

        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, PackageManager.GET_RESOLVED_FILTER)
        }

        return resolveInfos.isNotEmpty()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTH_STARTED, authStarted)
        outState.putParcelable(KEY_AUTH_URI, authenticationUri)
        outState.putInt(KEY_AUTH_OPTION_INTENT_FLAGS, intentFlags)
        outState.putString(KEY_AUTH_OPTION_TARGET_PACKAGE, targetPackage)
        outState.putBoolean(KEY_AUTH_OPTION_PREFER_EPHEMERAL, preferEphemeral)
        outState.putString(KEY_AUTH_CALLBACK_SCHEME, callbackScheme)
        outState.putString(KEY_AUTH_CALLBACK_HOST, callbackHost)
        outState.putString(KEY_AUTH_CALLBACK_PATH, callbackPath)
    }

    private fun extractState(state: Bundle?) {
        if (state == null) {
            // No extras: this is the response-handling intent created after the redirect.
            // Bring the host app back to the foreground for the Custom Tab flow.
            returnToHostAppIfCustomTab()
            finish()
            return
        }
        authStarted = state.getBoolean(KEY_AUTH_STARTED, false)
        authenticationUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            state.getParcelable(KEY_AUTH_URI, Uri::class.java)
        } else {
            @Suppress("deprecation")
            state.getParcelable(KEY_AUTH_URI)
        } ?: throw IllegalStateException("Authentication URI is null")
        intentFlags = state.getInt(KEY_AUTH_OPTION_INTENT_FLAGS, 0)
        targetPackage = state.getString(KEY_AUTH_OPTION_TARGET_PACKAGE)
        preferEphemeral = state.getBoolean(KEY_AUTH_OPTION_PREFER_EPHEMERAL, false)
        callbackScheme = state.getString(KEY_AUTH_CALLBACK_SCHEME)!!
        callbackHost = state.getString(KEY_AUTH_CALLBACK_HOST)
        callbackPath = state.getString(KEY_AUTH_CALLBACK_PATH)
    }
}
