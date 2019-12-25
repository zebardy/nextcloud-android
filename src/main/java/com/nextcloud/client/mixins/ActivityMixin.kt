package com.nextcloud.client.mixins

import android.content.Intent
import android.os.Bundle

/**
 * Interface allowing to implement part of [android.app.Activity] logic as
 * a mix-in.
 */
interface ActivityMixin {
    fun onNewIntent(intent: Intent) {}
    fun onSaveInstanceState(outState: Bundle) {}
    fun onCreate(savedInstanceState: Bundle?) {}
    fun onRestart() {}
    fun onStart() {}
    fun onResume() {}
    fun onPause() {}
    fun onStop() {}
    fun onDestroy() {}
}
