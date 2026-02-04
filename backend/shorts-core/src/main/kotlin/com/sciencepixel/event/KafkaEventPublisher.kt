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

    fun publishUploadRequested(event: UploadRequestedEvent) {
        println("📤 Publishing UploadRequestedEvent: ${event.videoId}")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_UPLOAD_REQUESTED, event.videoId, json)
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

    // === SAGA Pipeline Events ===

    fun publishRssNewItem(event: RssNewItemEvent) {
        println("📤 Publishing RssNewItemEvent: ${event.url}")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_RSS_NEW_ITEM, event.url, json) // URL as Key to prevent duplicate processing if needed
    }

    fun publishStockDiscoveryRequested(event: StockDiscoveryRequestedEvent) {
        println("📤 Publishing StockDiscoveryRequestedEvent: ${event.channelId}")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_STOCK_DISCOVERY_REQUESTED, event.channelId, json)
    }

    fun publishBgmUpload(event: BgmUploadEvent) {
        println("📤 Publishing BgmUploadEvent: ${event.filename}")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_BGM_UPLOAD, event.bgmId, json)
    }

    fun publishScriptCreated(event: ScriptCreatedEvent) {
        println("📤 Publishing ScriptCreatedEvent: ${event.videoId}")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_SCRIPT_CREATED, event.videoId, json)
    }

    fun publishAudioCreated(event: AudioCreatedEvent) {
        println("📤 Publishing AudioCreatedEvent: ${event.videoId}")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_AUDIO_CREATED, event.videoId, json)
    }

    fun publishVideoAssetsReady(event: VideoAssetsReadyEvent) {
        println("📤 Publishing VideoAssetsReadyEvent: ${event.videoId}")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_ASSETS_READY, event.videoId, json)
    }

    fun publishToDeadLetterQueue(event: Any, reason: String) {
        println("💀 Publishing to DLQ: $reason")
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_DLQ, reason, json)
    }

    fun publishSystemLog(event: SystemLogEvent) {
        val json = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(KafkaConfig.TOPIC_SYSTEM_LOGS, event.serviceName, json)
    }
}
