package com.linusu.flutter_web_auth_2

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

class CallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent?.data ?: fixAutoVerifyNotWorks(intent)
        val scheme = url?.scheme

        if (scheme != null) {
            val cb = FlutterWebAuth2Plugin.callbacks.remove(scheme)
            if (cb != null) {
                cb.success(url.toString())
            } else {
                // Cold-start recovery: no live completer (the process was killed while the Custom
                // Tab was open). Persist the redirect so the app can resume login on its next start.
                FlutterWebAuth2Plugin.persistPendingRedirect(this, url.toString())
            }
            // The redirect arrived: the Custom Tab session (if any) is no longer needed.
            FlutterWebAuth2Plugin.releaseCustomTabsSession(this)
        }
        startActivity(AuthenticationManagementActivity.createResponseHandlingIntent(this))
        finish()
    }


    /** Fix sometimes android:autoVerify="true" cannot works when it can't access Google after installation.
     * See https://stackoverflow.com/questions/76383106/auto-verify-not-always-working-in-app-links-using-android
     *
     * must register in AndroidManifest.xml :
     * <intent-filter>
     *     <action android:name="android.intent.action.SEND" />
     *     <category android:name="android.intent.category.DEFAULT" />
     *     <data android:mimeType="text/plain" />
     *</intent-filter>
     */
    private fun fixAutoVerifyNotWorks(intent: Intent?): Uri? {
        if (intent?.action == Intent.ACTION_SEND && "text/plain" == intent.type) {
            return intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                try {
                    //scheme://host/path#id_token=xxx
                    return Uri.parse(it)
                } catch (e: Exception) {
                    return null
                }
            }
        }
        return null
    }

}
