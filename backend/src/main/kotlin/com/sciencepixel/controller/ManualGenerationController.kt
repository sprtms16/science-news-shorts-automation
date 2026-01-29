package com.sciencepixel.controller

import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.ProductionResult
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.event.KafkaEventPublisher
import com.sciencepixel.event.VideoCreatedEvent
import com.sciencepixel.repository.VideoHistoryRepository
import com.sciencepixel.service.AsyncVideoService
import com.sciencepixel.service.GeminiService
import com.sciencepixel.service.ProductionService
import org.springframework.web.bind.annotation.*

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
    private val cleanupService: com.sciencepixel.service.CleanupService
) {

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
        return "✅ Cleanup triggered manually. Check logs."
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
                    title = scienceNews.title,
                    summary = scienceNews.summary,
                    link = "manual_batch_topic"
                )
                val saved = videoHistoryRepository.save(history)
                val videoId = saved.id ?: ""
                
                jobIds.add(videoId)
                
                // NewsItem으로 변환
                val news = NewsItem(
                    title = scienceNews.title,
                    summary = scienceNews.summary,
                    link = "manual_batch_topic"
                )
                
                // 비동기 처리 시작 (인자 순서: news, videoId)
                asyncVideoService.createVideoAsync(news, videoId)
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
    fun createVideoFromTopicAsync(@RequestBody request: TopicRequest): JobStatus {
        println("🚀 [ASYNC] AI-Powered Video Generation Requested")
        println("📌 Topic: ${request.topic}")
        
        val generatedContent = geminiService.generateScienceNews(request.topic, request.style)
        
        println("✨ Generated Title: ${generatedContent.title}")
        println("📝 Generated Summary: ${generatedContent.summary}")
        
        val news = NewsItem(
            title = generatedContent.title,
            summary = generatedContent.summary,
            link = "ai-async-${System.currentTimeMillis()}"
        )
        
        // 초기 상태 저장
        val history = VideoHistory(
            title = news.title,
            link = news.link,
            summary = news.summary,
            status = "PROCESSING"
        )
        val savedHistory = videoHistoryRepository.save(history)
        
        // 비동기로 비디오 생성 시작
        asyncVideoService.createVideoAsync(news, savedHistory.id!!)
        
        return JobStatus(
            id = savedHistory.id!!,
            title = news.title,
            status = "PROCESSING",
            filePath = null,
            youtubeUrl = null,
            message = "✅ 작업이 시작되었습니다. 완료 시 Discord/Telegram으로 알림됩니다. GET /manual/status/${savedHistory.id}로 상태 확인 가능"
        )
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
            "PROCESSING" -> "⏳ 비디오 생성 중..."
            "COMPLETED" -> "✅ 비디오 생성 완료! YouTube 업로드 대기 중..."
            "UPLOADED" -> "🎉 YouTube 업로드 완료!"
            "FAILED" -> "❌ 비디오 생성 실패"
            "ERROR" -> "⚠️ 에러 발생"
            else -> "상태: ${history.status}"
        }

        return JobStatus(
            id = id,
            title = history.title,
            status = history.status,
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
            title = news.title,
            link = news.link,
            summary = news.summary,
            status = "PROCESSING"
        )
        val savedHistory = videoHistoryRepository.save(history)
        
        try {
            val result = productionService.produceVideo(news)
            val filePath = result.filePath
            
            return if (filePath.isNotEmpty()) {
                val completedVideo = videoHistoryRepository.save(savedHistory.copy(
                    status = "COMPLETED",
                    filePath = filePath
                ))
                
                if (completedVideo.id != null) {
                    kafkaEventPublisher.publishVideoCreated(VideoCreatedEvent(
                        videoId = completedVideo.id!!,
                        title = completedVideo.title,
                        summary = completedVideo.summary,
                        link = completedVideo.link,
                        filePath = filePath,
                        keywords = result.keywords
                    ))
                }
                
                "✅ Video created successfully: $filePath (Queued for Upload via Kafka)"
            } else {
                videoHistoryRepository.save(savedHistory.copy(status = "FAILED"))
                "❌ Failed to create video."
            }
        } catch (e: Exception) {
            videoHistoryRepository.save(savedHistory.copy(status = "ERROR"))
            e.printStackTrace()
            return "❌ Error: ${e.message}"
        }
    }
}
