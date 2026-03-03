import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_web_auth_2_platform_interface/flutter_web_auth_2_platform_interface.dart';

/// Method channel implementation of the [FlutterWebAuth2Platform].
class FlutterWebAuth2MethodChannel extends FlutterWebAuth2Platform {
  static const MethodChannel _channel = MethodChannel('flutter_web_auth_2');

  /// Callbacks for browser opened events, keyed by callbackUrlScheme
  static final Map<String, VoidCallback> _browserOpenedCallbacks = {};

  /// Initialize method call handler for receiving events from native
  static bool _initialized = false;
  static void _ensureInitialized() {
    if (_initialized) return;
    _initialized = true;
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  static Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onBrowserOpened':
        final args = call.arguments as Map<dynamic, dynamic>;
        final callbackScheme = args['callbackScheme'] as String?;
        if (callbackScheme != null) {
          final callback = _browserOpenedCallbacks.remove(callbackScheme);
          callback?.call();
        }
        break;
    }
  }

  /// Register a callback to be called when browser is opened
  static void registerBrowserOpenedCallback(
    String callbackUrlScheme,
    VoidCallback callback,
  ) {
    _ensureInitialized();
    _browserOpenedCallbacks[callbackUrlScheme] = callback;
  }

  /// Unregister the browser opened callback
  static void unregisterBrowserOpenedCallback(String callbackUrlScheme) {
    _browserOpenedCallbacks.remove(callbackUrlScheme);
  }

  @override
  Future<String> authenticate({
    required String url,
    required String callbackUrlScheme,
    required Map<String, dynamic> options,
  }) async =>
      await _channel.invokeMethod<String>('authenticate', <String, dynamic>{
        'url': url,
        'callbackUrlScheme': callbackUrlScheme,
        'options': options,
      }) ??
      '';

  @override
  Future clearAllDanglingCalls() async =>
      _channel.invokeMethod('cleanUpDanglingCalls');
}
