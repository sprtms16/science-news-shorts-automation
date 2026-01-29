package com.sciencepixel.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
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
    val status: VideoStatus = VideoStatus.PENDING_PROCESSING,
    val description: String = "", // YouTube description (Korean)
    val tags: List<String> = emptyList(), // Hashtags
    val sources: List<String> = emptyList(), // Reference URLs/Sources
    val retryCount: Int = 0,  // Retry count for upload failures
    val regenCount: Int = 0,  // Regeneration attempt count
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

enum class VideoStatus {
    PENDING_PROCESSING,
    PROCESSING,
    SCRIPT_READY,
    COMPLETED,
    RETRY_PENDING,
    REGENERATING,
    UPLOADED,
    FILE_NOT_FOUND,
    REGEN_FAILED,
    ERROR,
    PERMANENTLY_FAILED,
    STALE_JOB_ABANDONED,
    QUOTA_EXCEEDED,
    QUEUED,
    ERROR_SCRIPT_EMPTY,
    RENDERING,
    ERROR_RENDERING,
    PROCESSING_ASSETS,
    ERROR_ASSETS
}

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
    val keywords: List<String>,
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val sources: List<String> = emptyList()
)

@Document(collection = "system_prompt")
data class SystemPrompt(
    @Id
    val id: String, // "script_prompt", "vision_prompt" for example
    val content: String,
    val description: String = "",
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

@Document(collection = "system_setting")
data class SystemSetting(
    @Id
    val key: String, // e.g., "MAX_GENERATION_LIMIT", "UPLOAD_BLOCKED_UNTIL"
    val value: String,
    val description: String = "",
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

data class Scene(val sentence: String, val keyword: String)

data class ScriptResponse(
    val scenes: List<Scene>,
    val mood: String,
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val sources: List<String> = emptyList()
)
