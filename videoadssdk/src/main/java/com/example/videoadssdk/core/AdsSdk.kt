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

    @Volatile
    private var config: AppConfig = AppConfig()

    private var clickCounter = 0

    // INTERVAL
    private var lastAdShownAtMs = 0L
    private var intervalJob: Job? = null

    private val isShowing = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // app foreground tracking
    private val isAppOpen = AtomicBoolean(false)

    // ---- auto tracking ----
    private var lifecycleRegistered = false
    private var resumedActivities = 0
    private val windowCallbacks = WeakHashMap<Activity, Window.Callback>()
    private var currentActivityRef: WeakReference<Activity>? = null

    /**
     * Call once at app start
     * - auto hooks all activities
     * - auto pulls config (with small retry)
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

        // ✅ Pull config automatically (with a few retries)
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
     * Count a click (auto called by SDK touch hook when trigger=CLICKS)
     */
    fun registerClick() {
        if (!initialized) return
        if (!isAppOpen.get()) return
        if (!config.trigger.type.equals("CLICKS", ignoreCase = true)) return
        clickCounter++
    }

    /**
     * Decide whether to show ad now (CLICKS or INTERVAL).
     * In INTERVAL mode, scheduler is the main engine; this remains as a safe fallback.
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
                // fallback: do nothing (scheduler handles it),
                // but we can still allow a manual check if needed.
            }

            else -> Unit
        }
    }

    /**
     * Developer-controlled preferences (optional).
     * Note: dev can call this once; SDK will keep working automatically afterwards.
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

                // when resuming, ensure interval scheduler state is correct
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

    private fun hookWindowCallback(activity: Activity) {
        val window = activity.window ?: return

        // אם שמרנו בעבר original – נשתמש בו
        val original = windowCallbacks[activity] ?: window.callback ?: return

        // נשמור original פעם אחת בלבד
        if (!windowCallbacks.containsKey(activity)) {
            windowCallbacks[activity] = original
        }

        // אם כרגע לא מחובר intercept – נחבר מחדש
        if (window.callback !is TouchInterceptingCallback) {
            window.callback = TouchInterceptingCallback(original, activity)
        }
    }

    private fun unhookWindowCallback(activity: Activity) {
        val window = activity.window ?: return
        val original = windowCallbacks[activity] ?: return

        if (window.callback is TouchInterceptingCallback) {
            window.callback = original
        }

        // ✅ הכי חשוב: למחוק כדי שב־resume הבא יהיה hook מחדש
        windowCallbacks.remove(activity)
    }


    private class TouchInterceptingCallback(
        private val base: Window.Callback,
        private val activity: Activity
    ) : Window.Callback {

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            // never intercept inside ad screen (extra safety)
            if (activity !is AdPlayerActivity && event.action == MotionEvent.ACTION_UP) {
                // only in CLICKS mode will it actually increment
                AdsSdk.registerClick()

                // only in CLICKS mode will it potentially show
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

                // if no activity or we're on ad screen, retry
                if (act == null || act is AdPlayerActivity) {
                    delay(500)
                    continue
                }

                val seconds = (config.trigger.seconds ?: 120).coerceAtLeast(10)
                val now = System.currentTimeMillis()

                // start counting from "now" if first run
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
