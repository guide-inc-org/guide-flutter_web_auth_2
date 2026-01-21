package com.linusu.flutter_web_auth_2

import android.app.Activity
import android.net.Uri
import android.os.Bundle

class CallbackActivity: Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val url = intent?.data
    val scheme = url?.scheme

    if (scheme != null) {
      val callback = FlutterWebAuth2Plugin.callbacks.remove(scheme)
      if (callback != null) {
        // Remove all other entries with the same callback to prevent duplicate calls
        FlutterWebAuth2Plugin.callbacks.entries.removeIf { it.value == callback }
        callback.success(url.toString())
      }
    }

    finish()
  }
}
