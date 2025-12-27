package com.example.videoadssdk.model

data class ConfigResponse(
    val app_id: String,
    val config: AppConfig
)

data class AppConfig(
    val categories: List<String> = listOf("SPORT", "FOOD", "TECH"),
    val trigger: Trigger = Trigger(type = "CLICKS", count = 15),
    val x_delay_seconds: Int = 5
)

/**
 * trigger supports:
 * - CLICKS: use `count` (number of clicks)
 * - INTERVAL: use `seconds` (number of seconds)
 *
 * We keep both fields optional to support backward/forward compatibility.
 */
data class Trigger(
    val type: String = "CLICKS",
    val count: Int? = 15,      // used when type=CLICKS
    val seconds: Int? = null   // used when type=INTERVAL
)
