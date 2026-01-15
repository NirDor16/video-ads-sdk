package com.example.videoadssdk.model

data class ConfigResponse(
    val app_id: String,
    val config: AppConfig
)

data class AppConfig(
    val categories: List<String> = listOf("TV", "CAR", "GAME"),
    val trigger: Trigger = Trigger(type = "CLICKS", count = 15),
    val x_delay_seconds: Int = 5
)

/**
 * trigger supports:
 * - CLICKS: use `count`
 * - INTERVAL: use `seconds`
 */
data class Trigger(
    val type: String = "CLICKS",
    val count: Int? = 15,
    val seconds: Int? = null
)
