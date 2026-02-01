package com.sciencepixel.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

@Document(collection = "video_history")
data class VideoHistory(
    @Id
    val id: String? = null,
    val title: String,
    val summary: String,
    @Indexed(unique = true)
    val link: String,
    val status: VideoStatus = VideoStatus.CREATING,
    val failureStep: String = "", // e.g. "SCRIPT", "ASSETS", "RENDER", "UPLOAD"
    val errorMessage: String = "",
    val description: String = "", // YouTube description (Korean)
    val tags: List<String> = emptyList(), // Hashtags
    val sources: List<String> = emptyList(), // Reference URLs/Sources
    val validationErrors: List<String> = emptyList(), // Validation failure reasons
    val retryCount: Int = 0,  // Retry count for upload failures
    val regenCount: Int = 0,  // Regeneration attempt count
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val filePath: String = "",
    val youtubeUrl: String = "",
    val thumbnailPath: String = "" // Local path to thumbnail image
)

enum class VideoStatus {
    QUEUED,     // 대기 중 (배치 등록 직후)
    CREATING,   // 생성 중 (작업 시작됨)
    FAILED,     // 생성 실패
    COMPLETED,  // 생성 완료
    UPLOADED    // 업로드 완료
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

/**
 * Data class to hold MongoDB aggregation results for duplicate link detection.
 */
data class DuplicateLinkGroup(val _id: String, val docs: List<VideoHistory>)

data class ProductionResult(
    val filePath: String,
    val keywords: List<String>,
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val thumbnailPath: String = ""
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

data class YoutubeVideoStat(
    val videoId: String,
    val title: String,
    val description: String = "",
    val viewCount: Long,
    val likeCount: Long,
    val publishedAt: String,
    val thumbnailUrl: String
)

@Document(collection = "youtube_videos")
data class YoutubeVideoEntity(
    @Id
    val videoId: String,
    @Indexed
    val title: String,
    val description: String = "",
    val viewCount: Long,
    val likeCount: Long,
    val publishedAt: String,
    val thumbnailUrl: String,
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

data class YoutubeVideoResponse(
    val videos: List<YoutubeVideoStat>,
    val nextPageToken: String?
)
