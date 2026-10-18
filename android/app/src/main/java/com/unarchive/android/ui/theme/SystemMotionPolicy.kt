package com.unarchive.android.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Observe the Android "Remove animations" setting, including changes while backgrounded. */
@Composable
internal fun rememberSystemMotionPolicy(): MotionPolicy {
    val resolver = LocalContext.current.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun readPolicy() = MotionPolicy.fromAnimatorDurationScale(
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
    )
    var policy by remember(resolver) { mutableStateOf(readPolicy()) }
    DisposableEffect(resolver, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { policy = readPolicy() }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) policy = readPolicy()
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            resolver.unregisterContentObserver(observer)
            lifecycle.removeObserver(lifecycleObserver)
        }
    }
    return policy
}
