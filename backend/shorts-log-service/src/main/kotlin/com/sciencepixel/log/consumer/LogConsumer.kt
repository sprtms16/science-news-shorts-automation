package com.sciencepixel.log.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.sciencepixel.log.domain.LogEntry
import com.sciencepixel.log.repository.LogRepository
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

@Service
class LogConsumer(
    private val repository: LogRepository,
    private val objectMapper: ObjectMapper
) {

    @KafkaListener(topics = ["system-logs"], groupId = "log-service-group")
    fun consumeLog(message: String) {
        try {
            val logEntry = objectMapper.readValue(message, LogEntry::class.java)
            repository.save(logEntry)
        } catch (e: Exception) {
            println("❌ Error consuming log: ${e.message}")
            // Fallback: save as raw error log
            repository.save(LogEntry(
                serviceName = "log-service",
                level = "ERROR",
                message = "Failed to parse log message: $message"
            ))
        }
    }
}
