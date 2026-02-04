package com.sciencepixel.event

import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class VideoProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    @Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {
    private val REQUEST_TOPIC = "video.creation.request"

    fun sendCreationRequest(
        videoId: String,
        title: String,
        summary: String,
        link: String,
        regenCount: Int = 0
    ) {
        val request = VideoCreationRequest(
            videoId = videoId,
            channelId = channelId, // Send current channel ID so renderer knows where to save
            title = title,
            summary = summary,
            link = link,
            regenCount = regenCount
        )
        val json = objectMapper.writeValueAsString(request)
        println("📤 [Kafka] Sending Creation Request for: $title (ID: $videoId)")
        kafkaTemplate.send(REQUEST_TOPIC, videoId, json)
    }
}
