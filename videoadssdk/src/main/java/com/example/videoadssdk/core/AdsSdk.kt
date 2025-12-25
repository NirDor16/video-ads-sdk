package com.example.videoadssdk.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.example.videoadssdk.api.AdsApi
import com.example.videoadssdk.model.Ad
import com.example.videoadssdk.player.AdPlayerActivity
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


object AdsSdk {

    private var initialized = false
    private lateinit var api: AdsApi
    private lateinit var appId: String

    private var categories: List<String> = listOf("SPORT")
    private var mode: String = "RANDOM" // RANDOM / MANUAL

    fun init(context: Context, baseUrl: String, appId: String) {
        this.appId = appId

        api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AdsApi::class.java)

        initialized = true
    }

    fun setCategories(selected: List<String>) {
        val cleaned = selected.map { it.trim().uppercase() }.filter { it.isNotBlank() }
        categories = if (cleaned.isEmpty()) listOf("SPORT") else cleaned
    }

    fun setMode(newMode: String) {
        val m = newMode.trim().uppercase()
        mode = if (m == "MANUAL" || m == "RANDOM") m else "RANDOM"
    }

    suspend fun requestAd(manualAdId: String? = null): Ad? {
        check(initialized) { "AdsSdk is not initialized. Call AdsSdk.init(...) first." }

        val categoriesParam = categories.joinToString(",")
        val resp = api.serveAd(
            appId = appId,
            categories = categoriesParam,
            mode = mode,
            adId = manualAdId
        )
        return resp.ad
    }
    fun showAd(activity: Activity, ad: Ad) {
        val intent = AdPlayerActivity.createIntent(activity, ad.video_url)
        activity.startActivity(intent)
    }


}
