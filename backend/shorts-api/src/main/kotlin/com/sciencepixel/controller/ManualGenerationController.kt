package com.sciencepixel.controller

import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.ProductionResult
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.VideoCreatedEvent
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.service.AsyncVideoService
import com.sciencepixel.service.GeminiService
import com.sciencepixel.service.ProductionService
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity

// 기존 방식: 제목/요약 직접 입력
data class ManualRequest(
    val title: String,
    val summary: String
)

// 새 방식: 주제만 입력
data class TopicRequest(
    val topic: String,            // 예: "블랙홀", "인공지능", "양자컴퓨터"
    val style: String = "news"    // news, tutorial, facts (기본값: news)
)

// 작업 상태 조회용 응답
data class JobStatus(
    val id: String,
    val title: String,
    val status: String,
    val filePath: String?,
    val youtubeUrl: String?,
    val message: String
)

// Batch topic request
data class BatchTopicRequest(
    val topics: List<String>,
    val style: String = "news"
)

@RestController
@RequestMapping("/manual")
class ManualGenerationController(
    private val productionService: ProductionService,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val geminiService: GeminiService,
    private val kafkaEventPublisher: KafkaEventPublisher,
    private val asyncVideoService: AsyncVideoService,
    private val youtubeUploadScheduler: com.sciencepixel.service.YoutubeUploadScheduler,
    private val cleanupService: com.sciencepixel.service.CleanupService,
    private val rssSourceRepository: com.sciencepixel.repository.RssSourceRepository,
    private val contentProviderService: com.sciencepixel.service.ContentProviderService,
    private val batchScheduler: com.sciencepixel.service.BatchScheduler,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    @GetMapping("/sources")
    fun getAvailableSources(): Map<String, Any> {
        val sources = rssSourceRepository.findByChannelId(channelId)
        val allItems = mutableListOf<NewsItem>()
        
        sources.forEach { source ->
            if (source.isActive) {
                allItems.addAll(contentProviderService.fetchContent(source))
            }
        }
        
        return mapOf(
            "channelId" to channelId,
            "sourceCount" to sources.size,
            "items" to allItems.sortedByDescending { it.title } // Simple sort
        )
    }

    @PostMapping("/scheduler/trigger")
    fun triggerSchedulerManually(): String {
        println("🔧 Manually triggering upload scheduler...")
        youtubeUploadScheduler.uploadPendingVideos()
        return "✅ Scheduler triggered manually. Check logs."
    }

    @PostMapping("/cleanup/trigger")
    fun triggerCleanupManually(): String {
        println("🧹 Manually triggering resource cleanup...")
        cleanupService.cleanupUploadedVideos()
        cleanupService.cleanupOldWorkspaces()
        cleanupService.cleanupOrphanedVideos()
        cleanupService.cleanupAiBgm()
        return "✅ Cleanup triggered manually. Check logs."
    }

    @PostMapping("/batch/trigger")
    fun triggerBatchJob(): ResponseEntity<String> {
        // Force trigger (Bypass daily limit)
        batchScheduler.triggerBatchJob(force = true)
        return ResponseEntity.ok("✅ Batch Job triggered manually. Check logs.")
    }

    /**
     * 배치 주제 기반 비동기 비디오 생성
     */
    @PostMapping("/batch/topic")
    fun createBatchVideosFromTopic(@RequestBody request: BatchTopicRequest): Map<String, Any> {
        println("📦 Received Batch Topic Request: ${request.topics.size} topics")
        
        val jobIds = mutableListOf<String>()
        
        request.topics.forEach { topic ->
            try {
                // AI 콘텐츠 생성
                val scienceNews = geminiService.generateScienceNews(topic, request.style)
                
                // 히스토리 저장
                val history = VideoHistory(
                    channelId = channelId,
                    title = scienceNews.title,
                    summary = scienceNews.summary,
                    link = "manual-batch-${topic.hashCode()}-${System.currentTimeMillis()}",
                    updatedAt = java.time.LocalDateTime.now()
                )
                val saved = videoHistoryRepository.save(history)
                val videoId = saved.id ?: ""
                
                jobIds.add(videoId)
                
                // NewsItem으로 변환
                val news = NewsItem(
                    title = scienceNews.title,
                    summary = scienceNews.summary,
                    link = history.link
                )
                
                // 비동기 처리 시작 (인자 순서: news, videoId, channelId)
                asyncVideoService.createVideoAsync(news, videoId, channelId)
            } catch (e: Exception) {
                println("⚠️ Failed to start job for topic '$topic': ${e.message}")
            }
        }
        
        return mapOf(
            "status" to "BATCH_STARTED",
            "total_topics" to request.topics.size,
            "started_jobs" to jobIds.size,
            "job_ids" to jobIds,
            "message" to "Batch processing started. Check logs or Discord for updates."
        )
    }

    /**
     * 기존 방식 (동기): 제목과 요약을 직접 입력하여 비디오 생성
     * 요청 완료까지 대기
     */
    @PostMapping("/create")
    fun createVideo(@RequestBody request: ManualRequest): String {
        println("🛠️ Manual Video Generation Requested: ${request.title}")
        
        val news = NewsItem(
            title = request.title,
            summary = request.summary,
            link = "manual-trigger-${System.currentTimeMillis()}"
        )
        
        return processVideoCreationSync(news)
    }

    /**
     * 새 방식 (동기): 주제만 입력하면 Gemini가 과학 뉴스를 자동 생성
     */
    @PostMapping("/topic")
    fun createVideoFromTopic(@RequestBody request: TopicRequest): String {
        println("🧠 AI-Powered Video Generation Requested")
        println("📌 Topic: ${request.topic}")
        println("🎨 Style: ${request.style}")
        
        val generatedContent = geminiService.generateScienceNews(request.topic, request.style)
        
        println("✨ Generated Title: ${generatedContent.title}")
        println("📝 Generated Summary: ${generatedContent.summary}")
        
        val news = NewsItem(
            title = generatedContent.title,
            summary = generatedContent.summary,
            link = "ai-generated-${System.currentTimeMillis()}"
        )
        
        return processVideoCreationSync(news)
    }

    /**
     * 비동기 방식: 즉시 작업 ID 반환, 백그라운드에서 처리
     * 완료 시 Discord/Telegram으로 알림
     * 
     * 예시 요청:
     * POST /manual/async/topic
     * {"topic": "블랙홀의 비밀", "style": "news"}
     */
    @PostMapping("/async/topic")
    fun createVideoFromTopicAsync(): ResponseEntity<Map<String, String>> {
        println("🚀 Manual Batch Trigger Requested (via Async Topic endpoint)")
        
        // 기존 배치를 호출하여 RSS 수집부터 시작
        batchScheduler.triggerBatchJob(force = true)
        
        return ResponseEntity.ok(mapOf(
            "status" to "BATCH_STARTED",
            "message" to "✅ 기존 배치 프로세스(RSS 수집 및 비디오 생성)가 시작되었습니다. 로그 및 알림(Discord/Telegram)을 확인해주세요."
        ))
    }

    /**
     * 작업 상태 조회
     * GET /manual/status/{id}
     */
    @GetMapping("/status/{id}")
    fun getJobStatus(@PathVariable id: String): JobStatus {
        val history = videoHistoryRepository.findById(id).orElse(null)
            ?: return JobStatus(
                id = id,
                title = "",
                status = "NOT_FOUND",
                filePath = null,
                youtubeUrl = null,
                message = "❌ 작업을 찾을 수 없습니다."
            )

        val statusMessage = when (history.status) {
            VideoStatus.QUEUED -> "⏸️ 비디오 생성 대기 중..."
            VideoStatus.SCRIPTING -> "📝 대본 작성 중..."
            VideoStatus.RENDERING -> "🎬 영상 렌더링 중..."
            VideoStatus.RETRY_QUEUED -> "⏳ 재시도 대기 중..."
            VideoStatus.COMPLETED -> "✅ 비디오 생성 완료! YouTube 업로드 대기 중..."
            VideoStatus.UPLOADED -> "🎉 YouTube 업로드 완료!"
            VideoStatus.FAILED -> "❌ 비디오 생성 실패: ${history.errorMessage}"
            else -> "상태: ${history.status}"
        }

        return JobStatus(
            id = id,
            title = history.title,
            status = history.status.name,
            filePath = history.filePath.takeIf { it.isNotBlank() },
            youtubeUrl = history.youtubeUrl.takeIf { it.isNotBlank() },
            message = statusMessage
        )
    }

    /**
     * 동기 비디오 생성 로직
     */
    private fun processVideoCreationSync(news: NewsItem): String {
        val history = VideoHistory(
            channelId = channelId,
            title = news.title,
            link = news.link,
            summary = news.summary,
            status = VideoStatus.QUEUED,
            updatedAt = java.time.LocalDateTime.now()
        )
        val savedHistory = videoHistoryRepository.save(history)
        val historyId = requireNotNull(savedHistory.id) { "Video ID must not be null for sync production" }
        
        try {
            val result = productionService.produceVideo(news, historyId)
            val filePath = result.filePath
            
            return if (filePath.isNotEmpty()) {
                val completedVideo = videoHistoryRepository.save(savedHistory.copy(
                    status = VideoStatus.COMPLETED,
                    filePath = filePath,
                    thumbnailPath = result.thumbnailPath,
                    title = result.title.ifBlank { savedHistory.title },
                    description = result.description.ifBlank { savedHistory.description },
                    tags = if (result.tags.isNotEmpty()) result.tags else savedHistory.tags,
                    sources = if (result.sources.isNotEmpty()) result.sources else savedHistory.sources,
                    updatedAt = java.time.LocalDateTime.now()
                ))
                
                val completedVideoId = requireNotNull(completedVideo.id) { "Completed video ID must not be null" }
                kafkaEventPublisher.publishVideoCreated(com.sciencepixel.event.VideoCreatedEvent(
                    channelId = channelId,
                    videoId = completedVideoId,
                    title = completedVideo.title,
                    summary = completedVideo.summary,
                    description = completedVideo.description,
                    link = completedVideo.link,
                    filePath = filePath,
                    keywords = result.keywords,
                    thumbnailPath = result.thumbnailPath
                ))
                
                "✅ Video created successfully: $filePath (Queued for Upload via Kafka)"
            } else {
                videoHistoryRepository.save(savedHistory.copy(
                    status = VideoStatus.FAILED,
                    failureStep = "RENDER",
                    errorMessage = "Empty file path produced",
                    updatedAt = java.time.LocalDateTime.now()
                ))
                "❌ Failed to create video."
            }
        } catch (e: Exception) {
            videoHistoryRepository.save(savedHistory.copy(
                status = VideoStatus.FAILED,
                failureStep = "SYNC_PROCESS",
                errorMessage = e.message ?: "Unknown error during sync creation",
                updatedAt = java.time.LocalDateTime.now()
            ))
            e.printStackTrace()
            return "❌ Error: ${e.message}"
        }
    }
}
