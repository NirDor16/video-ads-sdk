package com.example.videoadssdk.model

data class ServeResponse(
    val ad: Ad?,                                // null when NO_FILL
    val mode: String? = null,                   // RANDOM / MANUAL
    val app_id: String? = null,
    val requested_categories: List<String>? = null
)
