package com.sciencepixel.service

import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.SystemLogEvent
import org.springframework.stereotype.Component

@Component
class LogPublisher(private val kafkaEventPublisher: KafkaEventPublisher) {

    fun info(serviceName: String, message: String, details: String? = null, traceId: String? = null) {
        kafkaEventPublisher.publishSystemLog(SystemLogEvent(
            serviceName = serviceName,
            level = "INFO",
            message = message,
            details = details,
            traceId = traceId
        ))
    }

    fun warn(serviceName: String, message: String, details: String? = null, traceId: String? = null) {
        kafkaEventPublisher.publishSystemLog(SystemLogEvent(
            serviceName = serviceName,
            level = "WARN",
            message = message,
            details = details,
            traceId = traceId
        ))
    }

    fun error(serviceName: String, message: String, details: String? = null, traceId: String? = null) {
        kafkaEventPublisher.publishSystemLog(SystemLogEvent(
            serviceName = serviceName,
            level = "ERROR",
            message = message,
            details = details,
            traceId = traceId
        ))
    }
}
