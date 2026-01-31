package com.sciencepixel.log.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

@Document(collection = "system_logs")
data class LogEntry(
    @Id val id: String? = null,
    val serviceName: String,
    val level: String, // INFO, WARN, ERROR
    val message: String,
    val details: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val traceId: String? = null
)
