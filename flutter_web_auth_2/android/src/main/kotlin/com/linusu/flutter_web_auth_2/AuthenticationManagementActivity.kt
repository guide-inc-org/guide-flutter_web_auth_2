package com.linusu.flutter_web_auth_2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.browser.customtabs.EngagementSignalsCallback

/**
 * Activity that manages Chrome Custom Tab authentication flow.
 *
 * CCT close detection:
 * - Primary: EngagementSignalsCallback.onSessionEnded() - called when CCT closes
 * - Fallback: ActivityResult RESULT_CANCELED (Android 16+)
 *
 * PiP handling:
 * - TAB_HIDDEN: CCT minimized to PiP (auth continues)
 * - TAB_SHOWN: CCT reopened from PiP (auth continues)
 * - onSessionEnded: CCT actually closed (cancel auth)
 */
@SuppressLint("UnsafeOptInUsageError", "UnsafeOptInUsageWarning")
class AuthenticationManagementActivity : ComponentActivity() {
    companion object {
        private const val TAG = "Tien_AuthActivity"
        const val KEY_AUTH_STARTED: String = "authStarted"
        const val KEY_AUTH_URI: String = "authUri"
        const val KEY_AUTH_FINISH: String = "authFinish"
        const val KEY_AUTH_OPTION_INTENT_FLAGS: String = "authOptionsIntentFlags"
        const val KEY_AUTH_OPTION_TARGET_PACKAGE: String = "authOptionsTargetPackage"
        const val KEY_AUTH_OPTION_PREFER_EPHEMERAL: String = "authOptionsPreferEphemeral"
        const val KEY_AUTH_CALLBACK_SCHEME: String = "authCallbackScheme"
        const val KEY_AUTH_CALLBACK_HOST: String = "authCallbackHost"
        const val KEY_AUTH_CALLBACK_PATH: String = "authCallbackPath"
        const val KEY_AUTH_OPTION_TOOLBAR_COLOR: String = "authOptionsToolbarColor"

        fun createResponseHandlingIntent(context: Context): Intent {
            val intent = Intent(context, AuthenticationManagementActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            intent.putExtra(KEY_AUTH_FINISH, true)
            return intent
        }
    }

    // Auth flow state
    private var authStarted: Boolean = false
    private var authFinishing: Boolean = false  // Prevent duplicate cancel/finish

    // Intent configuration
    private lateinit var authenticationUri: Uri
    private var intentFlags: Int = 0
    private var targetPackage: String? = null
    private var preferEphemeral: Boolean = false
    private lateinit var callbackScheme: String
    private var callbackHost: String? = null
    private var callbackPath: String? = null
    private var toolbarColor: Int? = null

    private lateinit var authLauncher: ActivityResultLauncher<Intent>

    // CustomTabs service
    private var customTabsClient: CustomTabsClient? = null
    private var customTabsSession: CustomTabsSession? = null
    private var serviceConnection: CustomTabsServiceConnection? = null
    private var serviceConnected: Boolean = false
    private var pendingLaunch: Boolean = false
    private var browserOpenedNotified: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: savedInstanceState=${savedInstanceState != null}")

        // Register ActivityResultLauncher
        // This handles the RESULT_CANCELED case (works reliably on Android 16)
        authLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            Log.d(TAG, "ActivityResult: resultCode=${result.resultCode}, authStarted=$authStarted")

            if (!authStarted) {
                Log.d(TAG, "ActivityResult: authStarted=false, ignoring")
                return@registerForActivityResult
            }

            // Android 16: RESULT_CANCELED is reliably triggered when CCT is closed
            if (result.resultCode == Activity.RESULT_CANCELED) {
                Log.d(TAG, "ActivityResult: RESULT_CANCELED received, triggering cancel")
                cancelAuth()
            }
        }

        if (savedInstanceState == null) {
            extractState(intent.extras)
        } else {
            extractState(savedInstanceState)
        }

        // IMPORTANT: Bind to CustomTabsService BEFORE launching
        // This is required for TAB_SHOWN/TAB_HIDDEN events to work
        bindCustomTabsService()
    }

    /**
     * Bind to CustomTabsService to receive navigation events.
     * IMPORTANT: CustomTabsIntent must be launched with a session for events to work.
     */
    private fun bindCustomTabsService() {
        val packageName = targetPackage ?: CustomTabsClient.getPackageName(this, emptyList())
        Log.d(TAG, "bindCustomTabsService: packageName=$packageName")

        if (packageName == null) {
            Log.w(TAG, "bindCustomTabsService: No Custom Tabs package available")
            return
        }

        serviceConnection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                Log.d(TAG, "CustomTabsService connected: $name")
                customTabsClient = client
                client.warmup(0)

                // Create session with callback for navigation events
                customTabsSession = client.newSession(createCustomTabsCallback())

                Log.d(TAG, "CustomTabsSession created: ${customTabsSession != null}")

                // Register EngagementSignalsCallback to detect session end (CCT close)
                // This is more reliable than TAB_HIDDEN for detecting when CCT is closed
                registerEngagementSignalsCallback()

                serviceConnected = true

                // If launch was pending, do it now
                if (pendingLaunch) {
                    Log.d(TAG, "Service connected, launching pending browser")
                    pendingLaunch = false
                    launchBrowser()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d(TAG, "CustomTabsService disconnected")
                customTabsClient = null
                customTabsSession = null
                serviceConnected = false
            }
        }

        val bindResult = CustomTabsClient.bindCustomTabsService(this, packageName, serviceConnection!!)
        Log.d(TAG, "bindCustomTabsService: bindResult=$bindResult")
    }

    /**
     * Create CustomTabsCallback to receive navigation events.
     * NAVIGATION_STARTED is used to notify Flutter that browser has opened.
     */
    private fun createCustomTabsCallback(): CustomTabsCallback {
        return object : CustomTabsCallback() {
            override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
                Log.d(TAG, "onNavigationEvent: event=$navigationEvent")
                when (navigationEvent) {
                    NAVIGATION_STARTED -> {
                        if (!browserOpenedNotified) {
                            browserOpenedNotified = true
                            Log.d(TAG, "onNavigationEvent: NAVIGATION_STARTED - notifying Flutter")
                            FlutterWebAuth2Plugin.notifyBrowserOpened(callbackScheme)
                        }
                    }
                }
            }
        }
    }

    /**
     * Register EngagementSignalsCallback to receive onSessionEnded event.
     *
     * onSessionEnded is called when Custom Tab stops sending engagement signals,
     * including when user closes the Custom Tab. This is more reliable than
     * TAB_HIDDEN for detecting actual close (vs minimize to PiP).
     *
     * Requires androidx.browser:browser:1.6.0-alpha01 or higher.
     */
    private fun registerEngagementSignalsCallback() {
        val session = customTabsSession ?: return

        try {
            // Check if EngagementSignals API is available
            val isAvailable = session.isEngagementSignalsApiAvailable(Bundle.EMPTY)
            Log.d(TAG, "EngagementSignals API available: $isAvailable")

            if (!isAvailable) {
                Log.w(TAG, "EngagementSignals API not available, fallback will be used")
                return
            }

            // Register callback for session end detection
            val result = session.setEngagementSignalsCallback(
                object : EngagementSignalsCallback {
                    override fun onSessionEnded(didUserInteract: Boolean, extras: Bundle) {
                        Log.d(TAG, "onSessionEnded: didUserInteract=$didUserInteract, authFinishing=$authFinishing")
                        if (authStarted && !authFinishing) {
                            runOnUiThread { cancelAuth() }
                        }
                    }
                },
                Bundle.EMPTY
            )
            Log.d(TAG, "setEngagementSignalsCallback result: $result")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register EngagementSignalsCallback: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isAuthFinish = intent.getBooleanExtra(KEY_AUTH_FINISH, false)
        Log.d(TAG, "onNewIntent: KEY_AUTH_FINISH=$isAuthFinish")
        setIntent(intent)

        if (isAuthFinish) {
            // Auth success - callback URL received
            Log.d(TAG, "onNewIntent: Auth SUCCESS, calling finish()")
            authFinishing = true
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: authStarted=$authStarted, authFinishing=$authFinishing")

        if (!authStarted) {
            if (serviceConnected) {
                launchBrowser()
            } else {
                pendingLaunch = true
            }
        }
    }

    /**
     * Cancel the authentication flow and return CANCELED error to Flutter.
     * This method is idempotent - duplicate calls are ignored.
     */
    private fun cancelAuth() {
        Log.d(TAG, "cancelAuth: authFinishing=$authFinishing, authStarted=$authStarted")

        // Prevent duplicate cancel
        if (authFinishing || !authStarted) {
            Log.d(TAG, "cancelAuth: Already finished or not started, skipping")
            return
        }

        authFinishing = true
        Log.d(TAG, "cancelAuth: Sending CANCELED error to Flutter")

        val callback = FlutterWebAuth2Plugin.callbacks[callbackScheme]
        callback?.error("CANCELED", "User canceled authentication", null)
        FlutterWebAuth2Plugin.callbacks.remove(callbackScheme)
        finish()
    }

    /**
     * Launch Chrome Custom Tab with the configured session.
     * IMPORTANT: Must use CustomTabsIntent.Builder(session) for events to work.
     */
    private fun launchBrowser() {
        if (authStarted) {
            Log.d(TAG, "launchBrowser: Already started, skipping")
            return
        }

        Log.d(TAG, "launchBrowser: Launching CCT with session=${customTabsSession != null}")

        // IMPORTANT: Use Builder(session) to enable TAB_SHOWN/TAB_HIDDEN events
        val intentBuilder = CtBuilderWrapper(
            CustomTabsIntent.Builder(),
            this,
            toolbarColor
        ).apply {
            setSession(customTabsSession)
        }

        // Set ephemeral browsing if requested
        if (preferEphemeral) {
            try {
                intentBuilder.setEphemeralBrowsingEnabled(true)
                Log.d(TAG, "launchBrowser: Ephemeral browsing enabled")
            } catch (e: Exception) {
                Log.w(TAG, "launchBrowser: Failed to enable ephemeral browsing: ${e.message}")
            }
        }

        val intent = intentBuilder.build()
        intent.intent.addFlags(intentFlags)
        if (targetPackage != null) {
            intent.intent.setPackage(targetPackage)
        }

        try {
            if (callbackScheme == "https" && callbackHost != null && callbackPath != null) {
                Log.d(TAG, "launchBrowser: Using https callback - host=$callbackHost, path=$callbackPath")
                intent.launch(this, authLauncher, authenticationUri, callbackHost!!, callbackPath!!)
            } else {
                Log.d(TAG, "launchBrowser: Using custom scheme callback - scheme=$callbackScheme")
                intent.launch(this, authLauncher, authenticationUri, callbackScheme)
            }
            authStarted = true
            Log.d(TAG, "launchBrowser: CCT launched, authStarted=true")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "launchBrowser: No browser available: ${e.message}")
            val callback = FlutterWebAuth2Plugin.callbacks[callbackScheme]
            callback?.error("NO_BROWSER", "No valid browser available for authentication.", e.message)
            FlutterWebAuth2Plugin.callbacks.remove(callbackScheme)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: authStarted=$authStarted")

        // Unbind CustomTabsService
        serviceConnection?.let {
            try {
                unbindService(it)
                Log.d(TAG, "onDestroy: Unbound CustomTabsService")
            } catch (e: Exception) {
                Log.w(TAG, "onDestroy: Error unbinding service: ${e.message}")
            }
        }
        serviceConnection = null
        customTabsClient = null
        customTabsSession = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTH_STARTED, authStarted)
        outState.putBoolean("authFinishing", authFinishing)
        outState.putParcelable(KEY_AUTH_URI, authenticationUri)
        outState.putInt(KEY_AUTH_OPTION_INTENT_FLAGS, intentFlags)
        outState.putString(KEY_AUTH_OPTION_TARGET_PACKAGE, targetPackage)
        outState.putBoolean(KEY_AUTH_OPTION_PREFER_EPHEMERAL, preferEphemeral)
        outState.putString(KEY_AUTH_CALLBACK_SCHEME, callbackScheme)
        outState.putString(KEY_AUTH_CALLBACK_HOST, callbackHost)
        outState.putString(KEY_AUTH_CALLBACK_PATH, callbackPath)
        toolbarColor?.let { outState.putInt(KEY_AUTH_OPTION_TOOLBAR_COLOR, it) }
    }

    private fun extractState(state: Bundle?) {
        if (state == null) {
            finish()
            return
        }
        authStarted = state.getBoolean(KEY_AUTH_STARTED, false)
        authFinishing = state.getBoolean("authFinishing", false)
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
        toolbarColor = if (state.containsKey(KEY_AUTH_OPTION_TOOLBAR_COLOR)) {
            state.getInt(KEY_AUTH_OPTION_TOOLBAR_COLOR)
        } else null
    }
}
