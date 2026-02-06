package com.sciencepixel.consumer

import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.event.UploadRequestedEvent
import com.sciencepixel.service.VideoUploadService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = ["app.feature.consumer-manual-upload"],
    havingValue = "true",
    matchIfMissing = true
)
class ManualUploadConsumer(
    private val videoUploadService: VideoUploadService,
    private val objectMapper: ObjectMapper,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    // Use a distinct group ID for manual uploads to ensure reliable immediate execution
    // and to potentially allow separating 'latest' behavior if needed.
    // However, adhering to the channel-group pattern is usually safer.
    // To "delete" old events (ignore backlog), we can rely on the fact that 
    // we are likely adding this consumer to a deployment that might reset offsets or 
    // we can explicitly check if the video is still in valid state in the service.
    @KafkaListener(
        topics = [KafkaConfig.TOPIC_UPLOAD_REQUESTED],
        groupId = "\${spring.kafka.consumer.group-id:\${SHORTS_CHANNEL_ID:science}-manual-upload-group}",
        properties = ["auto.offset.reset=latest"] // Skip past events as requested by user
    )
    fun consumeUploadRequest(message: String) {
        try {
            val event = objectMapper.readValue(message, UploadRequestedEvent::class.java)

            // Channel Filter
            if (event.channelId != channelId) {
                return
            }

            println("🚀 [$channelId] Received Manual Upload Request for: ${event.title}")
            
            // Execute Upload immediately (Async via Consumer thread)
            videoUploadService.uploadVideo(event.videoId)

        } catch (e: Exception) {
            println("❌ [$channelId] Error processing manual upload request: ${e.message}")
        }
    }
}
