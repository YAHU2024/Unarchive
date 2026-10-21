package com.unarchive.android.ui.theme

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** User-off also serves as Reduce Transparency; system contrast/power settings take precedence. */
@Composable
internal fun rememberSystemGlassInputs(userEnabled: Boolean): LiquidGlassInputs {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun read() = LiquidGlassInputs(
        userEnabled = userEnabled,
        apiLevel = Build.VERSION.SDK_INT,
        performanceTier = if (context.getSystemService(ActivityManager::class.java).isLowRamDevice ||
            context.getSystemService(PowerManager::class.java).isPowerSaveMode) PerformanceTier.Constrained else PerformanceTier.Normal,
        // Android stores High contrast text here; no public Settings constant exists on API 31.
        highContrast = Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) != 0,
    )
    var inputs by remember(context, userEnabled) { mutableStateOf(read()) }
    DisposableEffect(context, lifecycle, userEnabled) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { inputs = read() }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { inputs = read() }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) inputs = read()
        }
        resolver.registerContentObserver(Settings.Secure.getUriFor("high_text_contrast_enabled"), false, observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            resolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
            lifecycle.removeObserver(lifecycleObserver)
        }
    }
    return inputs
}
