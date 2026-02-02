package com.sciencepixel.service

import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.ProductionResult
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.VideoCreatedEvent
import com.sciencepixel.repository.VideoHistoryRepository
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

/**
 * 비동기 비디오 생성 서비스
 * 수동 요청을 비동기로 처리하고 완료 시 알림 전송
 */
@Service
class AsyncVideoService(
    private val productionService: ProductionService,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val kafkaEventPublisher: KafkaEventPublisher,
    private val notificationService: NotificationService
) {

    /**
     * 비동기 비디오 생성
     * 즉시 작업 ID를 반환하고 백그라운드에서 처리
     */
    @Async
    fun createVideoAsync(news: NewsItem, historyId: String): CompletableFuture<String> {
        println("🚀 [ASYNC] Starting video creation: ${news.title}")
        
        return try {
            val result = productionService.produceVideo(news, historyId)
            val filePath = result.filePath
            val keywords = result.keywords
            val thumbnailPath = result.thumbnailPath
            
            if (filePath.isNotEmpty()) {
                // Update status to COMPLETED
                val history = videoHistoryRepository.findById(historyId).orElse(null)
                if (history != null) {
                    val completedVideo = videoHistoryRepository.save(history.copy(
                        status = VideoStatus.COMPLETED,
                        filePath = filePath,
                        thumbnailPath = thumbnailPath,
                        title = result.title.ifBlank { history.title },
                        description = result.description.ifBlank { history.description },
                        tags = if (result.tags.isNotEmpty()) result.tags else history.tags,
                        sources = if (result.sources.isNotEmpty()) result.sources else history.sources,
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                    
                    // 생성 알림은 디버그용으로만 남김
                    println("📢 Video created successfully: ${completedVideo.title}")
                }
                
                println("✅ [ASYNC] Video created successfully: $filePath")
                CompletableFuture.completedFuture(filePath)
            } else {
                videoHistoryRepository.findById(historyId).ifPresent { history ->
                    videoHistoryRepository.save(history.copy(
                        status = VideoStatus.FAILED,
                        failureStep = "RENDER_ASYNC",
                        errorMessage = "Empty file path produced",
                        updatedAt = java.time.LocalDateTime.now()
                    ))
                }
                notificationService.notifyError(news.title, "비디오 생성 실패")
                println("❌ [ASYNC] Video creation failed")
                CompletableFuture.completedFuture("")
            }
        } catch (e: Exception) {
            videoHistoryRepository.findById(historyId).ifPresent { history ->
                videoHistoryRepository.save(history.copy(
                    status = VideoStatus.FAILED,
                    failureStep = "ASYNC_PROCESS",
                    errorMessage = e.message ?: "Unknown error during async creation",
                    updatedAt = java.time.LocalDateTime.now()
                ))
            }
            notificationService.notifyError(news.title, e.message ?: "알 수 없는 에러")
            println("❌ [ASYNC] Error: ${e.message}")
            e.printStackTrace()
            CompletableFuture.failedFuture(e)
        }
    }
}
