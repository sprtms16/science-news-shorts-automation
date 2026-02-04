package com.sciencepixel.event

import java.time.LocalDateTime

data class SystemLogEvent(
    val serviceName: String,
    val level: String, // INFO, WARN, ERROR
    val message: String,
    val details: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val traceId: String? = null
)
