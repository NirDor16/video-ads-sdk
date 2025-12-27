package com.example.videoadssdk.core

import android.app.Activity
import android.content.Context
import com.example.videoadssdk.api.AdsApi
import com.example.videoadssdk.model.Ad
import com.example.videoadssdk.model.AppConfig
import com.example.videoadssdk.model.Trigger
import com.example.videoadssdk.model.UpdateConfigRequest
import com.example.videoadssdk.player.AdPlayerActivity
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
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
    }

    fun onAppForeground() { isAppOpen.set(true) }
    fun onAppBackground() { isAppOpen.set(false) }

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
     * Count a click. Only relevant when trigger=CLICKS.
     * NOTE: This does NOT show ad by itself - call maybeShowAd(activityProvider) too.
     */
    fun registerClick() {
        if (!initialized) return
        if (!isAppOpen.get()) return
        if (!config.trigger.type.equals("CLICKS", ignoreCase = true)) return

        clickCounter++
    }

    /**
     * Main entry point: decide whether to show ad now (CLICKS or INTERVAL).
     * Call this after registerClick(), and also optionally on screen changes.
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
            x_delay_seconds = (xDelaySeconds ?: config.x_delay_seconds).coerceAtLeast(0)
        )

        // update server
        withContext(Dispatchers.IO) {
            api.updateConfig(appId, UpdateConfigRequest(newConfig))
        }

        // refresh local from server (source of truth)
        return refreshConfig()
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
            // optional log
        } finally {
            isShowing.set(false)
        }
    }

    fun showAd(activity: Activity, ad: Ad) {
        val xDelay = config.x_delay_seconds.coerceAtLeast(5)
        val intent = AdPlayerActivity.createIntent(activity, ad.video_url, xDelay)
        activity.startActivity(intent)
    }
}
