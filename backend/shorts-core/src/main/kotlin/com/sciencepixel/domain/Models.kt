package com.sciencepixel.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

@Document(collection = "video_history")
@CompoundIndexes(
    CompoundIndex(name = "channel_link_idx", def = "{'channelId': 1, 'link': 1}", unique = true)
)
data class VideoHistory(
    @Id
    val id: String? = null,
    val channelId: String = "science", // 추가: 채널 식별자 (science, horror, stocks, history)
    val title: String,
    val summary: String,
    val link: String,
    val status: VideoStatus = VideoStatus.QUEUED,
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
    val thumbnailPath: String = "", // Local path to thumbnail image
    val rssTitle: String? = null, // Original RSS title for deduplication
    val scenes: List<Scene> = emptyList(), // Persisted script content
    val progress: Int = 0, // 렌더링 진행률 (0-100)
    val currentStep: String = "" // 현재 작업 단계 (e.g. "TTS 생성", "클립 다운로드", "영상 병합")
)

enum class VideoStatus {
    QUEUED,             // 대기 중 (배치 등록 직후)
    SCRIPTING,          // 대본 제작 중 (ScriptConsumer)
    ASSETS_QUEUED,      // 에셋 생성 대기 (Script 완료 -> Kafka)
    ASSETS_GENERATING,  // 에셋 생성 중 (SceneConsumer)
    RENDER_QUEUED,      // 렌더링 대기 (Assets 완료 -> Kafka)
    RENDERING,          // 렌더링 중 (RenderConsumer)
    FAILED,             // 실패
    RETRY_QUEUED,       // 재생성 대기 중 (실패 후 복구 대기)
    BLOCKED,            // 유해성/정책 위반으로 생성 차단 (재시도 불가)
    COMPLETED,          // 제작 완료 (렌더링 완료 -> Upload 대기)
    UPLOADING,          // 업로드 진행 중
    UPLOAD_FAILED,      // 업로드 실패
    UPLOADED            // 업로드 완료
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
    val link: String = "",
    val sourceName: String = "Unknown" // Attribution source
)

enum class SourceType {
    RSS, REDDIT_JSON, WIKIPEDIA_ON_THIS_DAY, STOCK_NEWS
}

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
@CompoundIndexes(
    CompoundIndex(name = "channel_prompt_idx", def = "{'channelId': 1, 'promptKey': 1}", unique = true)
)
data class SystemPrompt(
    @Id
    val id: String? = null,
    val channelId: String = "science", // 추가: 채널 식별자
    val promptKey: String, // e.g. "script_prompt", "vision_prompt"
    val content: String,
    val description: String = "",
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

@Document(collection = "system_setting")
@CompoundIndexes(
    CompoundIndex(name = "channel_key_idx", def = "{'channelId': 1, 'key': 1}", unique = true)
)
data class SystemSetting(
    @Id
    val id: String? = null, // 인스턴스별 설정을 위해 전용 ID 사용
    val channelId: String = "science", // 추가: 채널 식별자
    val key: String, // e.g., "MAX_GENERATION_LIMIT", "UPLOAD_BLOCKED_UNTIL"
    val value: String,
    val description: String = "",
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

data class Scene(val sentence: String, val keyword: String)

data class SilenceRange(val start: Double, val end: Double)

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
@CompoundIndexes(
    CompoundIndex(name = "channel_title_idx", def = "{'channelId': 1, 'title': 1}")
)
data class YoutubeVideoEntity(
    @Id
    val videoId: String,
    val channelId: String = "science", // 추가: 채널 식별자
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

/**
 * Pexels 비디오 캐시
 * 다운로드한 Pexels 영상을 영구 저장하여 재사용
 */
@Document(collection = "pexels_cache")
@CompoundIndexes(
    CompoundIndex(name = "keyword_idx", def = "{'keyword': 1}"),
    CompoundIndex(name = "pexels_id_idx", def = "{'pexelsVideoId': 1}", unique = true),
    CompoundIndex(name = "last_used_idx", def = "{'lastUsedAt': 1}")
)
data class PexelsVideoCache(
    @Id
    val id: String? = null,
    val keyword: String,          // 검색 키워드 (예: "black hole", "DNA")
    val pexelsVideoId: String,    // Pexels 고유 비디오 ID (중복 방지)
    val filePath: String,         // 절대 경로: shared-data/pexels-cache/common/[hash]/[video_id].mp4
    val thumbnailUrl: String,     // Pexels 썸네일 URL
    val width: Int,               // 영상 너비
    val height: Int,              // 영상 높이
    val duration: Double = 0.0,   // 영상 길이 (초)
    val downloadedAt: LocalDateTime = LocalDateTime.now(),
    val lastUsedAt: LocalDateTime = LocalDateTime.now(), // 마지막 사용 시간 (정리용)
    val usageCount: Int = 0,      // 재사용 횟수
    val visionCheckPassed: Boolean = true,
    val visionCheckContext: String = "",
    val channelId: String = "common" // "common" = 전역 캐시, 또는 채널별
)
