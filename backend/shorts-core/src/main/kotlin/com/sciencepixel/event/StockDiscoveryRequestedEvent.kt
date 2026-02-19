package com.sciencepixel.event

data class StockDiscoveryRequestedEvent(
    val channelId: String,
    val timestamp: Long = System.currentTimeMillis()
)
