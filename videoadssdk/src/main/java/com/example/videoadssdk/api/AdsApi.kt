package com.example.videoadssdk.api

import com.example.videoadssdk.model.ConfigResponse
import com.example.videoadssdk.model.ServeResponse
import com.example.videoadssdk.model.UpdateConfigRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface AdsApi {

    @GET("/v1/apps/{appId}/config")
    suspend fun getConfig(
        @Path("appId") appId: String
    ): ConfigResponse

    @PUT("/v1/apps/{appId}/config")
    suspend fun updateConfig(
        @Path("appId") appId: String,
        @Body body: UpdateConfigRequest
    ): ConfigResponse

    @GET("/v1/serve")
    suspend fun serveAd(
        @Query("app_id") appId: String,
        @Query("categories") categories: String?,
        @Query("mode") mode: String,
        @Query("ad_id") adId: String? = null
    ): ServeResponse
}
