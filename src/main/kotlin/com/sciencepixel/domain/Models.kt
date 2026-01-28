package com.sciencepixel.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.LocalDateTime

@Document(collection = "video_history")
data class VideoHistory(
    @Id
    val id: String? = null,
    val title: String,
    val summary: String,
    val link: String,
    val filePath: String = "",
    val youtubeUrl: String = "",
    val status: String = "PENDING_PROCESSING", // PENDING_PROCESSING, COMPLETED, RETRY_PENDING, REGENERATING, UPLOADED, FILE_NOT_FOUND, REGEN_FAILED, ERROR
    val retryCount: Int = 0,  // Retry count for upload failures
    val regenCount: Int = 0,  // Regeneration attempt count
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

@Document(collection = "quota_usage")
data class QuotaUsage(
    @Id
    val id: String = "youtube_upload", // 단일 레코드로 관리
    val usedUnits: Int = 0,
    val date: String = "", // "yyyy-MM-dd" 형식으로 관리
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

data class NewsItem(
    val title: String,
    val summary: String,
    val link: String = ""
)

data class ProductionResult(
    val filePath: String,
    val keywords: List<String>
)

interface QuotaUsageRepository : MongoRepository<QuotaUsage, String>
