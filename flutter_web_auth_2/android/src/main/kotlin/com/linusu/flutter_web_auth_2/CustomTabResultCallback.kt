package com.linusu.flutter_web_auth_2

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback

class CustomTabResultCallback(
    private val getAuthStarted: () -> Boolean,
    private val getCallbackScheme: () -> String,
    private val isPiPMode: () -> Boolean,
    private val onFinish: () -> Unit
) : ActivityResultCallback<ActivityResult> {

    override fun onActivityResult(result: ActivityResult) {
        Log.d(LOG_TAG, "registerForActivityResult: resultCode=${result.resultCode}, authStarted=${getAuthStarted()}")

        if (!getAuthStarted()) return

        if (result.resultCode == Activity.RESULT_CANCELED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isPiPMode()) {
                Log.d(LOG_TAG, "Ignoring canceled result while in PiP")
                return
            }
            val callback = FlutterWebAuth2Plugin.removeCallback(getCallbackScheme())
            callback?.error("CANCELED", "User canceled authentication", null)
        }
        onFinish()
    }
}