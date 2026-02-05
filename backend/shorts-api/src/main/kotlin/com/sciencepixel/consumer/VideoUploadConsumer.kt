package com.sciencepixel.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.service.VideoUploadService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

/**
 * 비디오 업로드 Consumer
 * VideoCreatedEvent를 구독하여 YouTube 업로드 수행
 */
@Service
class VideoUploadConsumer(
    private val videoUploadService: VideoUploadService,
    private val objectMapper: ObjectMapper,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    @KafkaListener(
        topics = [KafkaConfig.TOPIC_UPLOAD_REQUESTED, KafkaConfig.TOPIC_VIDEO_CREATED],
        groupId = "\${spring.kafka.consumer.group-id:\${SHORTS_CHANNEL_ID:science}-upload-group}"
    )
    fun handleEvent(message: String) {
        try {
            if (message.contains("videoId") && message.contains("youtubeUrl")) {
                return 
            }
            
            val videoId = if (message.contains("\"videoId\":\"")) {
                message.substringAfter("\"videoId\":\"").substringBefore("\"")
            } else {
                null
            }
            
            if (videoId == null) return

            videoUploadService.uploadVideo(videoId)

        } catch (e: Exception) {
            println("❌ Error parsing upload triggering event: ${e.message}")
        }
    }
}
