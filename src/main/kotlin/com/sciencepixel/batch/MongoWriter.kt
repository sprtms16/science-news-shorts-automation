package com.sciencepixel.batch

import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.repository.VideoHistoryRepository
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter
import org.springframework.stereotype.Component

@Component
class MongoWriter(
    private val videoHistoryRepository: VideoHistoryRepository
) : ItemWriter<VideoHistory> {

    override fun write(chunk: Chunk<out VideoHistory>) {
        println("📝 Saving ${chunk.size()} items to MongoDB")
        videoHistoryRepository.saveAll(chunk.items)
    }
}
