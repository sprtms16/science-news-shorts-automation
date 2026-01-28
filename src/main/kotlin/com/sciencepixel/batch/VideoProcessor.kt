package com.sciencepixel.batch

import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.ProductionResult
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.service.ProductionService
import org.springframework.batch.item.ItemProcessor
import org.springframework.stereotype.Component

@Component
class VideoProcessor(
    private val productionService: ProductionService,
    private val videoHistoryRepository: VideoHistoryRepository
) : ItemProcessor<NewsItem, VideoHistory> {

    override fun process(item: NewsItem): VideoHistory? {
        // 중복 체크 (Link 기준)
        if (videoHistoryRepository.findByLink(item.link) != null) {
            println("⏭️ Skipping duplicate: ${item.title}")
            return null
        }

        return try {
            val result = productionService.produceVideo(item)
            val videoPath = result.filePath
            
            VideoHistory(
                title = result.title.ifEmpty { item.title },
                summary = item.summary, // Keep original summary for reference
                description = result.description, // New Korean formatted description
                link = item.link,
                filePath = videoPath,
                status = "COMPLETED",
                tags = result.tags,
                sources = result.sources
            )
        } catch (e: Exception) {
            println("⚠️ Failed to process: ${item.title} - ${e.message}")
            // 실패 내역도 저장할지 결정. 여기서는 null 리턴하여 건너뜀
            null
        }
    }
}
