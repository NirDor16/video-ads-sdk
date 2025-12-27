package com.example.videoadssdk.core

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.videoadssdk.api.AdsApi
import com.example.videoadssdk.model.Ad
import com.example.videoadssdk.model.AppConfig
import com.example.videoadssdk.player.AdPlayerActivity
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.atomic.AtomicBoolean
import com.example.videoadssdk.model.Trigger
import com.example.videoadssdk.model.UpdateConfigRequest

object AdsSdk {

    private var initialized = false
    private lateinit var api: AdsApi
    private lateinit var appId: String

    // server config
    private var config: AppConfig = AppConfig()

    // click trigger
    private var clickCounter = 0

    // interval trigger
    private var lastAdShownAtMs = 0L

    // prevent multiple ads at once
    private val isShowing = AtomicBoolean(false)

    // SDK scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // app foreground tracking
    private val isAppOpen = AtomicBoolean(true)

    /**
     * Call once at app start
     */
    fun init(context: Context, baseUrl: String, appId: String) {
        this.appId = appId.trim()

        val fixedBaseUrl = baseUrl.trim().trimEnd('/') + "/"

        api = Retrofit.Builder()
            .baseUrl(fixedBaseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AdsApi::class.java)

        initialized = true
    }

    /**
     * Call this in Activity.onResume()
     */
    fun onAppForeground() {
        isAppOpen.set(true)
    }

    /**
     * Call this in Activity.onPause()
     */
    fun onAppBackground() {
        isAppOpen.set(false)
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

        return config
    }

    /**
     * Call this whenever you want to count a click.
     * (Only matters when trigger=CLICKS)
     */
    fun registerClick() {
        if (!initialized) return
        if (!isAppOpen.get()) return

        if (!config.trigger.type.equals("CLICKS", ignoreCase = true)) return

        val needed = (config.trigger.count ?: 15).coerceAtLeast(1)
        clickCounter++

        if (clickCounter >= needed) {
            clickCounter = 0
            // we don't show immediately without an Activity,
            // so the app should call maybeShowAd(activityProvider) after registerClick()
        }
    }

    /**
     * Main entry-point for showing ads.
     *
     * App should call this after registerClick(), or occasionally (e.g., on screen change),
     * and provide current Activity.
     */
    fun maybeShowAd(activityProvider: () -> Activity?) {
        if (!initialized) return
        if (!isAppOpen.get()) return

        val type = config.trigger.type.uppercase()

        when (type) {
            "CLICKS" -> {
                val needed = (config.trigger.count ?: 15).coerceAtLeast(1)
                if (clickCounter >= needed) {
                    clickCounter = 0
                    val act = activityProvider() ?: return
                    scope.launch { requestAndShow(act) }
                }
            }

            "INTERVAL" -> {
                val seconds = (config.trigger.seconds ?: 120).coerceAtLeast(10)
                val now = System.currentTimeMillis()

                // if never shown -> allow immediate show
                val elapsed = now - lastAdShownAtMs
                if (lastAdShownAtMs == 0L || elapsed >= seconds * 1000L) {
                    val act = activityProvider() ?: return
                    scope.launch {
                        requestAndShow(act)
                        // only update after successful show attempt (best-effort)
                        lastAdShownAtMs = System.currentTimeMillis()
                    }
                }
            }

            else -> {
                // unknown trigger type -> do nothing
            }
        }
    }

    /**
     * Internal: request ad from /v1/serve using categories from config
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
     * Internal: request + show (guarded)
     */
    private suspend fun requestAndShow(activity: Activity) {
        if (!isShowing.compareAndSet(false, true)) return

        try {
            val ad = requestAd() ?: return
            showAd(activity, ad)
        } catch (_: Exception) {
            // optional: log
        } finally {
            isShowing.set(false)
        }
    }

    /**
     * Show fullscreen video ad, X button appears after x_delay_seconds
     */
    fun showAd(activity: Activity, ad: Ad) {
        val xDelay = config.x_delay_seconds.coerceAtLeast(0)
        val intent = AdPlayerActivity.createIntent(activity, ad.video_url, xDelay)
        activity.startActivity(intent)
    }
    /**
     * Developer-controlled preferences:
     * Updates server config for this appId (PUT) and updates local config.
     *
     * categories: list of category ids, e.g. ["SPORT","FOOD"]
     * triggerType: "CLICKS" or "INTERVAL"
     * clicksCount: used when triggerType=CLICKS
     * intervalSeconds: used when triggerType=INTERVAL
     * xDelaySeconds: seconds until X button enabled
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

        val newConfig = config.copy(
            categories = if (cleanedCats.isEmpty()) config.categories else cleanedCats,
            trigger = newTrigger,
            x_delay_seconds = (xDelaySeconds ?: config.x_delay_seconds).coerceAtLeast(0)
        )

        val resp = withContext(Dispatchers.IO) {
            api.updateConfig(appId, UpdateConfigRequest(newConfig))
        }

        // update local config from server response (source of truth)
        config = resp.config

        // reset counters to match new rules
        clickCounter = 0
        lastAdShownAtMs = 0L

        return config
    }

}
