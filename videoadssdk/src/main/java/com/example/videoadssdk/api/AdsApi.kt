package com.example.videoadssdk.api

import com.example.videoadssdk.model.ServeResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface AdsApi {
    @GET("/v1/serve")
    suspend fun serveAd(
        @Query("app_id") appId: String,
        @Query("categories") categories: String,
        @Query("mode") mode: String,
        @Query("ad_id") adId: String? = null
    ): ServeResponse
}
