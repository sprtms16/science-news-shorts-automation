package com.sciencepixel.service

import com.sciencepixel.domain.YoutubeVideoEntity
import com.sciencepixel.repository.YoutubeVideoRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Service
class YoutubeSyncService(
    private val youtubeService: YoutubeService,
    private val youtubeVideoRepository: YoutubeVideoRepository
) {

    /**
     * Sync YouTube videos to local DB every 1 hour.
     * Initially performs a deep sync, then incremental.
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    fun syncVideos() {
        println("📡 Starting YouTube video sync...")
        try {
            var pageToken: String? = null
            var count = 0
            
            // Limit to last 100 for periodic sync, or more if needed
            // For the first time, one might want to fetch more.
            // Let's fetch up to 50 (1 page) every hour, which is usually enough for a shorts channel.
            // But to be safe, let's fetch until we see duplicates we already have.
            
            val allFetchedIds = mutableSetOf<String>()
            var oldestPublishedAt: String? = null

            do {
                val response = youtubeService.getMyVideosStats(limit = 50, pageToken = pageToken)
                val videos = response.videos
                
                if (videos.isEmpty()) break
                
                allFetchedIds.addAll(videos.map { it.videoId })
                if (videos.isNotEmpty()) {
                    val currentOldest = videos.last().publishedAt
                    if (oldestPublishedAt == null || currentOldest < oldestPublishedAt!!) {
                        oldestPublishedAt = currentOldest
                    }
                }

                val entities = videos.map { stat ->
                    YoutubeVideoEntity(
                        videoId = stat.videoId,
                        title = stat.title,
                        description = stat.description,
                        viewCount = stat.viewCount,
                        likeCount = stat.likeCount,
                        publishedAt = stat.publishedAt,
                        thumbnailUrl = stat.thumbnailUrl,
                        updatedAt = LocalDateTime.now()
                    )
                }
                
                youtubeVideoRepository.saveAll(entities)
                count += entities.size
                
                pageToken = response.nextPageToken
                
            } while (pageToken != null && count < 500)

            // Pruning logic: Delete local records that are NOT on YouTube anymore (within the checked range)
            if (allFetchedIds.isNotEmpty()) {
                val localVideos = youtubeVideoRepository.findAll()
                val toDelete = localVideos.filter { local ->
                    // Not in current YT list AND is within the time range we just refreshed
                    !allFetchedIds.contains(local.videoId) && (oldestPublishedAt == null || local.publishedAt >= oldestPublishedAt!!)
                }
                if (toDelete.isNotEmpty()) {
                    youtubeVideoRepository.deleteAll(toDelete)
                    println("🧹 Pruned ${toDelete.size} deleted videos from local DB.")
                }
            }
            
            println("✅ YouTube sync completed. Synced $count videos.")
        } catch (e: Exception) {
            println("❌ Error during YouTube sync: ${e.message}")
        }
    }
}
