package com.sciencepixel.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.sciencepixel.config.KafkaConfig
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

/**
 * Kafka 이벤트 발행 서비스
 * 모든 이벤트를 JSON String으로 직렬화하여 발행
 */
@Service
class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {

    fun publishVideoCreated(event: VideoCreatedEvent) {
        println("📤 Publishing VideoCreatedEvent: ${event.videoId}")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_VIDEO_CREATED, event.videoId, json)
    }

    fun publishVideoUploaded(event: VideoUploadedEvent) {
        println("📤 Publishing VideoUploadedEvent: ${event.videoId} -> ${event.youtubeUrl}")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_VIDEO_UPLOADED, event.videoId, json)
    }

    fun publishUploadFailed(event: UploadFailedEvent) {
        println("📤 Publishing UploadFailedEvent: ${event.videoId} (Retry: ${event.retryCount})")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_UPLOAD_FAILED, event.videoId, json)
    }

    fun publishRegenerationRequested(event: RegenerationRequestedEvent) {
        println("📤 Publishing RegenerationRequestedEvent: ${event.videoId}")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_REGENERATION_REQUESTED, event.videoId, json)
    }

    fun publishToDeadLetterQueue(event: Any, reason: String) {
        println("💀 Publishing to DLQ: $reason")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_DLQ, reason, json)
    }
}
