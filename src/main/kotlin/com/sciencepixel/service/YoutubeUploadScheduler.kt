package com.sciencepixel.service

import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.repository.VideoHistoryRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File

@Service
class YoutubeUploadScheduler(
    private val repository: VideoHistoryRepository
) {

    // 1시간마다 실행 (테스트를 위해 더 자주 실행하려면 fixedDelay를 줄이세요)
    @Scheduled(fixedDelay = 3600000)
    fun uploadPendingVideos() {
        val pendingVideos = repository.findAll().filter { it.status == "COMPLETED" }

        pendingVideos.forEach { video: VideoHistory ->
            try {
                println("🚀 Uploading to YouTube: ${video.title}")
                
                // --- YouTube API Upload Logic Mock ---
                // In production, instantiate YouTube client and upload file at video.filePath
                // For now, we simulate success.
                
                // val content = File(video.filePath)
                // if (!content.exists()) throw Exception("File not found")
                
                Thread.sleep(2000) // Simulate upload time

                // Update Status
                val updated = video.copy(
                    status = "UPLOADED",
                    youtubeUrl = "https://youtu.be/mock_id_${System.currentTimeMillis()}"
                )
                repository.save(updated)
                println("✅ Upload Success: ${updated.youtubeUrl}")

                // Cleanup Local File after upload
                // File(video.filePath).delete()

            } catch (e: Exception) {
                println("❌ Upload Failed: ${e.message}")
            }
        }
    }
}
