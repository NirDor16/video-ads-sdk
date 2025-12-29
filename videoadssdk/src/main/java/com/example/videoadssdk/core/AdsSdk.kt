package com.example.videoadssdk.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.videoadssdk.api.AdsApi
import com.example.videoadssdk.model.Ad
import com.example.videoadssdk.model.AppConfig
import com.example.videoadssdk.model.Trigger
import com.example.videoadssdk.model.UpdateConfigRequest
import com.example.videoadssdk.player.AdPlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AdsSdk {

    private var initialized = false
    private lateinit var api: AdsApi
    private lateinit var appId: String

    // server config (source of truth is server, but we keep local copy)
    @Volatile
    private var config: AppConfig = AppConfig()

    // click trigger
    private var clickCounter = 0

    // interval trigger
    private var lastAdShownAtMs = 0L
    private var intervalJob: Job? = null

    // prevent multiple ads at once
    private val isShowing = AtomicBoolean(false)

    // SDK scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // app foreground tracking
    private val isAppOpen = AtomicBoolean(true)

    // ---- auto tracking ----
    private var lifecycleRegistered = false
    private var resumedActivities = 0
    private val windowCallbacks = WeakHashMap<Activity, Window.Callback>()
    private var currentActivityRef: WeakReference<Activity>? = null

    /**
     * Call once at app start
     */
    fun init(context: Context, baseUrl: String, appId: String) {
        this.appId = appId.trim()
        val fixedBaseUrl = baseUrl.trim().trimEnd('/') + "/"

        val okHttp = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        api = Retrofit.Builder()
            .baseUrl(fixedBaseUrl)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AdsApi::class.java)

        initialized = true

        // ✅ Multi-screen apps: auto hook all Activities + all taps
        registerAutoTracking(context.applicationContext)

        // ✅ Pull config once in background (so dev doesn't have to call refreshConfig)
        scope.launch {
            try {
                refreshConfig()
            } catch (_: Exception) {
                // ignore (optional: add retry if you want)
            }
        }
    }

    /**
     * Fetch latest config from server
     */
    suspend fun refreshConfig(): AppConfig {
        check(initialized) { "AdsSdk not initialized. Call init() first." }

        val resp = withContext(Dispatchers.IO) { api.getConfig(appId) }
        config = resp.config

        // reset counters when config changes
        clickCounter = 0
        lastAdShownAtMs = 0L

        // ✅ if INTERVAL is enabled while app is open, start scheduler
        startOrStopIntervalScheduler()

        return config
    }

    /**
     * Count a click. Only relevant when trigger=CLICKS.
     */
    fun registerClick() {
        if (!initialized) return
        if (!isAppOpen.get()) return
        if (!config.trigger.type.equals("CLICKS", ignoreCase = true)) return

        clickCounter++
    }

    /**
     * Main entry point: decide whether to show ad now (CLICKS or INTERVAL).
     */
    fun maybeShowAd(activityProvider: () -> Activity?) {
        if (!initialized) return
        if (!isAppOpen.get()) return
        if (isShowing.get()) return

        when (config.trigger.type.trim().uppercase()) {
            "CLICKS" -> {
                val needed = (config.trigger.count ?: 15).coerceAtLeast(1)
                if (clickCounter >= needed) {
                    clickCounter = 0
                    val act = activityProvider() ?: return
                    scope.launch { requestAndShow(act) }
                }
            }

            "INTERVAL" -> {
                // In INTERVAL mode, the scheduler handles timing.
                // Still allow manual checks (e.g., on resume).
                val seconds = (config.trigger.seconds ?: 120).coerceAtLeast(10)
                val now = System.currentTimeMillis()
                val elapsed = now - lastAdShownAtMs

                if (lastAdShownAtMs == 0L || elapsed >= seconds * 1000L) {
                    val act = activityProvider() ?: return
                    scope.launch {
                        requestAndShow(act)
                        lastAdShownAtMs = System.currentTimeMillis()
                    }
                }
            }

            else -> Unit
        }
    }

    /**
     * Developer-controlled preferences:
     * Updates server config (PUT) and refreshes local config from server.
     */
    suspend fun setPreferences(
        categories: List<String>,
        triggerType: String,
        clicksCount: Int? = null,
        intervalSeconds: Int? = null,
        xDelaySeconds: Int? = null
    ): AppConfig {
        check(initialized) { "AdsSdk not initialized. Call init() first." }

        val cleanedCats = categories
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()

        val t = triggerType.trim().uppercase()

        val newTrigger = if (t == "INTERVAL") {
            val sec = (intervalSeconds ?: 120).coerceAtLeast(10)
            Trigger(type = "INTERVAL", count = null, seconds = sec)
        } else {
            val cnt = (clicksCount ?: 15).coerceAtLeast(1)
            Trigger(type = "CLICKS", count = cnt, seconds = null)
        }

        val newConfig = AppConfig(
            categories = if (cleanedCats.isEmpty()) config.categories else cleanedCats,
            trigger = newTrigger,
            x_delay_seconds = (xDelaySeconds ?: config.x_delay_seconds).coerceIn(5, 30)
        )

        withContext(Dispatchers.IO) {
            api.updateConfig(appId, UpdateConfigRequest(newConfig))
        }

        val refreshed = refreshConfig()
        // scheduler might need to start/stop after config change
        startOrStopIntervalScheduler()
        return refreshed
    }

    private suspend fun requestAd(): Ad? {
        val categoriesParam = config.categories
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .joinToString(",")
            .takeIf { it.isNotBlank() }

        val resp = withContext(Dispatchers.IO) {
            api.serveAd(
                appId = appId,
                categories = categoriesParam,
                mode = "RANDOM",
                adId = null
            )
        }
        return resp.ad
    }

    private suspend fun requestAndShow(activity: Activity) {
        if (!isShowing.compareAndSet(false, true)) return

        try {
            val ad = requestAd() ?: return
            showAd(activity, ad)
        } catch (_: Exception) {
            // ignore
        } finally {
            isShowing.set(false)
        }
    }

    fun showAd(activity: Activity, ad: Ad) {
        val xDelay = config.x_delay_seconds.coerceIn(5, 30)
        val intent = AdPlayerActivity.createIntent(
            context = activity,
            videoUrl = ad.video_url,
            targetUrl = ad.target_url,
            xDelaySeconds = xDelay
        )
        activity.startActivity(intent)
    }

    // ---------------------------
    // Auto tracking (all screens)
    // ---------------------------
    private fun registerAutoTracking(appContext: Context) {
        if (lifecycleRegistered) return
        val app = appContext as? Application ?: return

        lifecycleRegistered = true

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityResumed(activity: Activity) {
                resumedActivities++
                isAppOpen.set(true)
                currentActivityRef = WeakReference(activity)

                // don't track clicks inside the ad screen itself
                if (activity !is AdPlayerActivity) {
                    hookWindowCallback(activity)
                }

                // helpful on resume
                maybeShowAd { activity }

                // ✅ INTERVAL scheduler start
                startOrStopIntervalScheduler()
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
                if (resumedActivities == 0) {
                    isAppOpen.set(false)
                }

                if (activity !is AdPlayerActivity) {
                    unhookWindowCallback(activity)
                }

                // ✅ INTERVAL scheduler stop if background
                startOrStopIntervalScheduler()
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (activity !is AdPlayerActivity) {
                    unhookWindowCallback(activity)
                    windowCallbacks.remove(activity)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    private fun hookWindowCallback(activity: Activity) {
        if (windowCallbacks.containsKey(activity)) return
        val window = activity.window ?: return
        val original = window.callback ?: return

        windowCallbacks[activity] = original
        window.callback = TouchInterceptingCallback(original, activity)
    }

    private fun unhookWindowCallback(activity: Activity) {
        val window = activity.window ?: return
        val original = windowCallbacks[activity] ?: return

        if (window.callback is TouchInterceptingCallback) {
            window.callback = original
        }
    }

    private class TouchInterceptingCallback(
        private val base: Window.Callback,
        private val activity: Activity
    ) : Window.Callback {

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                // ✅ any tap counts as a click + may show ad
                AdsSdk.registerClick()
                AdsSdk.maybeShowAd { activity }
            }
            return base.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean = base.dispatchKeyEvent(event)
        override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean = base.dispatchKeyShortcutEvent(event)
        override fun dispatchTrackballEvent(event: MotionEvent): Boolean = base.dispatchTrackballEvent(event)
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = base.dispatchGenericMotionEvent(event)
        override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean =
            base.dispatchPopulateAccessibilityEvent(event)

        override fun onCreatePanelView(featureId: Int): View? = base.onCreatePanelView(featureId)
        override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = base.onCreatePanelMenu(featureId, menu)
        override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean =
            base.onPreparePanel(featureId, view, menu)
        override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = base.onMenuOpened(featureId, menu)
        override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = base.onMenuItemSelected(featureId, item)
        override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) = base.onWindowAttributesChanged(attrs)
        override fun onContentChanged() = base.onContentChanged()
        override fun onWindowFocusChanged(hasFocus: Boolean) = base.onWindowFocusChanged(hasFocus)
        override fun onAttachedToWindow() = base.onAttachedToWindow()
        override fun onDetachedFromWindow() = base.onDetachedFromWindow()
        override fun onPanelClosed(featureId: Int, menu: Menu) = base.onPanelClosed(featureId, menu)
        override fun onSearchRequested(): Boolean = base.onSearchRequested()
        override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = base.onSearchRequested(searchEvent)
        override fun onWindowStartingActionMode(callback: ActionMode.Callback): ActionMode? =
            base.onWindowStartingActionMode(callback)
        override fun onWindowStartingActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
            base.onWindowStartingActionMode(callback, type)
        override fun onActionModeStarted(mode: ActionMode) = base.onActionModeStarted(mode)
        override fun onActionModeFinished(mode: ActionMode) = base.onActionModeFinished(mode)
    }

    // ---------------------------
    // INTERVAL scheduler
    // ---------------------------
    private fun startOrStopIntervalScheduler() {
        if (!initialized || !isAppOpen.get()) {
            intervalJob?.cancel()
            intervalJob = null
            return
        }

        val isInterval = config.trigger.type.equals("INTERVAL", ignoreCase = true)
        if (!isInterval) {
            intervalJob?.cancel()
            intervalJob = null
            return
        }

        if (intervalJob?.isActive == true) return

        intervalJob = scope.launch {
            while (isActive && initialized && isAppOpen.get()
                && config.trigger.type.equals("INTERVAL", ignoreCase = true)) {

                val act = currentActivityRef?.get()

                // if no activity or we're on ad screen, wait a bit and retry
                if (act == null || act is AdPlayerActivity) {
                    delay(500)
                    continue
                }

                val seconds = (config.trigger.seconds ?: 120).coerceAtLeast(10)
                val now = System.currentTimeMillis()

                // first start: begin counting from now
                if (lastAdShownAtMs == 0L) {
                    lastAdShownAtMs = now
                }

                val nextAt = lastAdShownAtMs + seconds * 1000L
                val waitMs = (nextAt - now).coerceAtLeast(0L)

                delay(waitMs)

                if (!isAppOpen.get()) continue
                if (isShowing.get()) {
                    delay(300)
                    continue
                }

                requestAndShow(act)
                lastAdShownAtMs = System.currentTimeMillis()
            }
        }
    }
}
