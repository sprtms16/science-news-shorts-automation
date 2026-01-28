package com.sciencepixel.service

import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.ProductionResult
import com.sciencepixel.repository.VideoHistoryRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File

@Service
class YoutubeUploadScheduler(
    private val repository: VideoHistoryRepository,
    private val youtubeService: YoutubeService,
    private val productionService: ProductionService
) {
    
    companion object {
        private const val MAX_RETRY_COUNT = 3
        private const val MAX_REGEN_COUNT = 1  // 재생성은 1회만 시도
    }

    // 매 시간 정각에 실행 ("0 0 * * * *")
    @Scheduled(cron = "0 0 * * * *")
    fun uploadPendingVideos() {
        println("⏰ Scheduler Triggered: Checking for pending videos at ${java.time.LocalDateTime.now()}")
        
        // 디버깅용: 전체 상태 카운트 출력
        val allVideos = repository.findAll()
        val statusCounts = allVideos.groupingBy { it.status }.eachCount()
        println("📊 Current Video Statuses: $statusCounts")

        // COMPLETED 또는 RETRY_PENDING 상태의 비디오를 처리
        val pendingVideos = allVideos.filter { 
            it.status == "COMPLETED" || it.status == "RETRY_PENDING" 
        }
        
        println("waiting list: ${pendingVideos.size}")

        // Limit to 1 video per run to ensure "One-by-One" steady stream and avoid spam triggers
        // Also respects daily quota distribution better.
        val targetVideo = pendingVideos.firstOrNull()

        if (targetVideo != null) {
            processVideoUpload(targetVideo)
        } else {
            println("✅ No pending videos to upload.")
        }
    }

    private fun processVideoUpload(video: VideoHistory) {
        try {
            println("🚀 Uploading to YouTube: ${video.title}")
            val file = File(video.filePath)
            
            if (file.exists()) {
                val tags = listOf("Science", "News", "Shorts", "SciencePixel")
                val videoId = youtubeService.uploadVideo(
                    file, 
                    video.title, 
                    "${video.summary}\n\n#Science #News #Shorts", 
                    tags
                )
                
                // Update Status
                val updated = video.copy(
                    status = "UPLOADED",
                    youtubeUrl = videoId,
                    retryCount = 0
                )
                repository.save(updated)
                println("✅ Upload Success: ${updated.youtubeUrl}")
            } else {
                // ... (File not found logic remains same) ...
                handleFileNotFound(video)
            }

        } catch (e: Exception) {
            println("❌ Upload Failed: ${e.message}")
            e.printStackTrace()
            
            // Circuit Breaker: If Quota Exceeded, do NOT mark as error in a way that prevents retry tomorrow
            // But here we are processing one by one, so just logging is fine.
            // If we were processing a list, we would 'break' here.
            
            if (e.message?.contains("quota") == true || e.message?.contains("403") == true) {
                println("⛔ Quota Exceeded. Stopping scheduler for this turn.")
                // Optional: Update status to 'QUOTA_LIMIT' to visualize in DB? 
                // For now, keep as RETRY_PENDING or COMPLETED allows retry next hour.
            } else {
                // Real error
                val errorVideo = video.copy(
                    status = "ERROR",
                    summary = video.summary + "\nError: ${e.message}"
                )
                repository.save(errorVideo)
            }
        }
    }

    private fun handleFileNotFound(video: VideoHistory) {
         val currentRetry = video.retryCount
         if (currentRetry < MAX_RETRY_COUNT) {
             println("⏳ File not found (Retry ${currentRetry + 1}/$MAX_RETRY_COUNT): ${video.filePath}")
             repository.save(video.copy(
                 status = "RETRY_PENDING",
                 retryCount = currentRetry + 1
             ))
         } else {
             println("❌ File not found after $MAX_RETRY_COUNT retries: ${video.filePath}")
             // 재생성 시도
             triggerRegeneration(video)
         }
    }
    

    
    // 비디오 재생성 로직
    private fun triggerRegeneration(video: VideoHistory) {
        val regenCount = video.regenCount
        
        if (regenCount >= MAX_REGEN_COUNT) {
            println("🚫 Max regeneration attempts reached for: ${video.title}")
            repository.save(video.copy(status = "REGEN_FAILED"))
            return
        }
        
        println("🔄 Attempting video regeneration (${regenCount + 1}/$MAX_REGEN_COUNT): ${video.title}")
        
        try {
            // 상태를 REGENERATING으로 변경
            repository.save(video.copy(
                status = "REGENERATING",
                regenCount = regenCount + 1,
                retryCount = 0
            ))
            
            // NewsItem 생성 및 비디오 재생성
            val newsItem = NewsItem(
                title = video.title,
                summary = video.summary,
                link = video.link
            )
            
            val result = productionService.produceVideo(newsItem)
            val newFilePath = result.filePath
            
            if (newFilePath.isNotBlank()) {
                println("✅ Regeneration successful: $newFilePath")
                repository.save(video.copy(
                    status = "COMPLETED",
                    filePath = newFilePath,
                    retryCount = 0,
                    regenCount = regenCount + 1
                ))
            } else {
                println("❌ Regeneration failed: Empty file path")
                repository.save(video.copy(status = "REGEN_FAILED"))
            }
            
        } catch (e: Exception) {
            println("❌ Regeneration error: ${e.message}")
            e.printStackTrace()
            repository.save(video.copy(status = "REGEN_FAILED"))
        }
    }
}
