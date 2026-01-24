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
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main SDK singleton.
 * Responsible for:
 * - Initializing networking
 * - Fetching remote configuration
 * - Auto-tracking user interactions across all activities
 * - Deciding when and how to show ads
 */
object AdsSdk {

    // Indicates whether the SDK was initialized
    private var initialized = false

    // Retrofit API interface
    private lateinit var api: AdsApi

    // Application identifier (sent with every request)
    private lateinit var appId: String

    // Latest configuration fetched from the server
    // Volatile ensures visibility across threads
    @Volatile
    private var config: AppConfig = AppConfig()

    // Click counter for CLICKS trigger mode
    private var clickCounter = 0

    // Interval trigger state
    private var lastAdShownAtMs = 0L
    private var intervalJob: Job? = null

    // Prevents showing multiple ads simultaneously
    private val isShowing = AtomicBoolean(false)

    // Main coroutine scope for SDK operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Tracks whether the app is currently in foreground
    private val isAppOpen = AtomicBoolean(false)

    // ---- Auto tracking state ----

    // Prevents registering lifecycle callbacks more than once
    private var lifecycleRegistered = false

    // Number of currently resumed activities
    private var resumedActivities = 0

    // Stores original Window.Callback per activity (weakly)
    private val windowCallbacks = WeakHashMap<Activity, Window.Callback>()

    // Reference to the currently visible activity (used by INTERVAL scheduler)
    private var currentActivityRef: WeakReference<Activity>? = null

    /**
     * Initializes the SDK.
     * Should be called once at application startup.
     *
     * - Sets up networking
     * - Hooks into all activities automatically
     * - Fetches remote configuration with retries
     */
    fun init(context: Context, baseUrl: String, appId: String) {
        this.appId = appId.trim()

        // Ensure base URL ends with a single slash
        val fixedBaseUrl = baseUrl.trim().trimEnd('/') + "/"

        // Configure HTTP client with safe timeouts
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        // Build Retrofit API client
        api = Retrofit.Builder()
            .baseUrl(fixedBaseUrl)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AdsApi::class.java)

        initialized = true

        // Automatically hook all activities and user taps
        registerAutoTracking(context.applicationContext)

        // Fetch config with limited retry attempts
        scope.launch {
            repeat(3) { attempt ->
                try {
                    refreshConfig()
                    return@launch
                } catch (_: Exception) {
                    if (attempt < 2) delay(1200)
                }
            }
        }
    }

    /**
     * Fetches the latest configuration from the backend.
     * Resets local counters and updates interval scheduler state.
     */
    suspend fun refreshConfig(): AppConfig {
        check(initialized) { "AdsSdk not initialized. Call init() first." }

        val resp = withContext(Dispatchers.IO) { api.getConfig(appId) }
        config = resp.config

        // Reset state when config changes
        clickCounter = 0
        lastAdShownAtMs = 0L

        // Restart interval scheduler if needed
        startOrStopIntervalScheduler()

        return config
    }

    /**
     * Registers a click event.
     * Only active when trigger type is CLICKS and app is in foreground.
     */
    fun registerClick() {
        if (!initialized) return
        if (!isAppOpen.get()) return
        if (!config.trigger.type.equals("CLICKS", ignoreCase = true)) return
        clickCounter++
    }

    /**
     * Determines whether an ad should be shown now.
     * - In CLICKS mode: checks click counter
     * - In INTERVAL mode: handled mainly by scheduler
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
                // Scheduler handles interval-based ads
            }

            else -> Unit
        }
    }

    /**
     * Allows developers to update ad preferences manually.
     * Updates server config and refreshes local state.
     */
    suspend fun setPreferences(
        categories: List<String>,
        triggerType: String,
        clicksCount: Int? = null,
        intervalSeconds: Int? = null,
        xDelaySeconds: Int? = null
    ): AppConfig {
        check(initialized) { "AdsSdk not initialized. Call init() first." }

        // Normalize category list
        val cleanedCats = categories
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()

        val t = triggerType.trim().uppercase()

        // Build trigger configuration
        val newTrigger = if (t == "INTERVAL") {
            val sec = (intervalSeconds ?: 120).coerceAtLeast(10)
            Trigger(type = "INTERVAL", count = null, seconds = sec)
        } else {
            val cnt = (clicksCount ?: 15).coerceAtLeast(1)
            Trigger(type = "CLICKS", count = cnt, seconds = null)
        }

        // Build updated config
        val newConfig = AppConfig(
            categories = if (cleanedCats.isEmpty()) config.categories else cleanedCats,
            trigger = newTrigger,
            x_delay_seconds = (xDelaySeconds ?: config.x_delay_seconds).coerceIn(5, 30)
        )

        // Push update to server
        withContext(Dispatchers.IO) {
            api.updateConfig(appId, UpdateConfigRequest(newConfig))
        }

        val refreshed = refreshConfig()
        startOrStopIntervalScheduler()
        return refreshed
    }

    /**
     * Requests an ad from the backend based on current configuration.
     */
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

    /**
     * Requests an ad and displays it if available.
     * Ensures only one ad is shown at a time.
     */
    private suspend fun requestAndShow(activity: Activity) {
        if (!isShowing.compareAndSet(false, true)) return

        try {
            val ad = requestAd() ?: return
            showAd(activity, ad)
        } catch (_: Exception) {
            // Ignore failures
        } finally {
            isShowing.set(false)
        }
    }

    /**
     * Launches the ad player activity.
     */
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

    /**
     * Registers lifecycle callbacks to automatically hook into all activities.
     */
    private fun registerAutoTracking(appContext: Context) {
        if (lifecycleRegistered) return
        val app = appContext as? Application ?: return

        lifecycleRegistered = true

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityResumed(activity: Activity) {
                resumedActivities++
                isAppOpen.set(true)
                currentActivityRef = WeakReference(activity)

                // Do not intercept touches inside the ad player itself
                if (activity !is AdPlayerActivity) {
                    hookWindowCallback(activity)
                }

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

    /**
     * Wraps the Window.Callback to intercept touch events.
     */
    private fun hookWindowCallback(activity: Activity) {
        val window = activity.window ?: return

        // Retrieve original callback (previously saved or current)
        val original = windowCallbacks[activity] ?: window.callback ?: return

        // Save original callback once
        if (!windowCallbacks.containsKey(activity)) {
            windowCallbacks[activity] = original
        }

        // Replace callback with interceptor if not already installed
        if (window.callback !is TouchInterceptingCallback) {
            window.callback = TouchInterceptingCallback(original, activity)
        }
    }

    /**
     * Restores the original Window.Callback.
     */
    private fun unhookWindowCallback(activity: Activity) {
        val window = activity.window ?: return
        val original = windowCallbacks[activity] ?: return

        if (window.callback is TouchInterceptingCallback) {
            window.callback = original
        }

        windowCallbacks.remove(activity)
    }

    /**
     * Intercepts touch events while delegating all other behavior
     * to the original Window.Callback.
     */
    private class TouchInterceptingCallback(
        private val base: Window.Callback,
        private val activity: Activity
    ) : Window.Callback {

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (activity !is AdPlayerActivity && event.action == MotionEvent.ACTION_UP) {
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

    /**
     * Starts or stops the interval-based ad scheduler
     * based on current app and config state.
     */
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
            while (
                isActive &&
                initialized &&
                isAppOpen.get() &&
                config.trigger.type.equals("INTERVAL", ignoreCase = true)
            ) {

                val act = currentActivityRef?.get()

                // Wait until a valid activity is available
                if (act == null || act is AdPlayerActivity) {
                    delay(500)
                    continue
                }

                val seconds = (config.trigger.seconds ?: 120).coerceAtLeast(10)
                val now = System.currentTimeMillis()

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
