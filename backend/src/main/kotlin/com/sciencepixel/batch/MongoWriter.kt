package com.sciencepixel.batch

import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.VideoCreatedEvent
import com.sciencepixel.repository.VideoHistoryRepository
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import org.springframework.stereotype.Component

@Component
class MongoWriter(
    private val videoHistoryRepository: VideoHistoryRepository,
    private val kafkaEventPublisher: KafkaEventPublisher
) : ItemWriter<VideoHistory> {

    override fun write(chunk: Chunk<out VideoHistory>) {
        println("📝 Saving ${chunk.size()} items to MongoDB")
        val savedItems = videoHistoryRepository.saveAll(chunk.items)
        
        // Kafka 이벤트 발행 - COMPLETED 상태인 비디오만
        savedItems.forEach { video ->
            if (video.status == "COMPLETED" && video.filePath.isNotBlank() && video.id != null) {
                kafkaEventPublisher.publishVideoCreated(VideoCreatedEvent(
                    videoId = video.id!!,
                    title = video.title,
                    summary = video.summary,
                    link = video.link,
                    filePath = video.filePath,
                    keywords = emptyList() // Batch 처리 시에는 현재 키워드 추출 로직이 없음
                ))
            }
        }
    }
}

