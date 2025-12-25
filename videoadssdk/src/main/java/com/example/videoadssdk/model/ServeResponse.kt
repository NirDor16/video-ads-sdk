package com.example.videoadssdk.model

data class ServeResponse(
    val ad: Ad?,
    val mode: String?,
    val app_id: String?,
    val requested_categories: List<String>?
)
