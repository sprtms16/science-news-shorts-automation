package com.sciencepixel.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.sciencepixel.config.KafkaConfig
import com.sciencepixel.event.VideoCreatedEvent
import com.sciencepixel.event.StockDiscoveryRequestedEvent
import com.sciencepixel.repository.RssSourceRepository
import com.sciencepixel.service.ContentProviderService
import com.sciencepixel.service.AsyncVideoService
import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import com.sciencepixel.repository.VideoHistoryRepository
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import com.sciencepixel.domain.NewsItem

@Component
class StockDiscoveryConsumer(
    private val contentProviderService: ContentProviderService,
    private val asyncVideoService: AsyncVideoService,
    private val rssSourceRepository: RssSourceRepository,
    private val videoHistoryRepository: VideoHistoryRepository,
    private val geminiService: com.sciencepixel.service.GeminiService,
    private val eventPublisher: com.sciencepixel.event.KafkaEventPublisher,
    private val objectMapper: ObjectMapper
) {

    @KafkaListener(topics = [KafkaConfig.TOPIC_STOCK_DISCOVERY_REQUESTED], groupId = KafkaConfig.GROUP_MAIN)
    fun handleStockDiscoveryRequest(message: String) {
        try {
            val event = objectMapper.readValue(message, StockDiscoveryRequestedEvent::class.java)
            println("📉 [StockConsumer] Received request for channel: ${event.channelId}")

            if (event.channelId != "stocks") return

            // 1. Morning Briefing Check (Between 06:00 and 08:30 AM KST)
            val now = java.time.LocalTime.now()
            val isMorningTime = now.isAfter(java.time.LocalTime.of(6, 0)) && now.isBefore(java.time.LocalTime.of(8, 30))
            
            if (isMorningTime) {
                println("☀️ [StockConsumer] Morning Briefing Time! Executing Market Collector...")
                if (executeMorningBriefing(event.channelId)) return
            }

            // 2. Fallback to Deep-Dive Discovery (Existing Logic)
            executeDeepDiveDiscovery(event)

        } catch (e: Exception) {
            println("❌ [StockConsumer] Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun executeMorningBriefing(channelId: String): Boolean {
        try {
            // 1. Run Python Collector
            val processBuilder = ProcessBuilder(
                "c:/Users/sprtm/AppData/Local/Programs/Python/Python311/python.exe",
                "backend/market-collector/collector.py"
            )
            processBuilder.directory(java.io.File("."))
            val process = processBuilder.start()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                println("❌ [StockConsumer] Market Collector failed with exit code $exitCode")
                return false
            }

            // 2. Read Latest Report JSON
            val reportFile = java.io.File("shared-data/market-reports/latest_report.json")
            if (!reportFile.exists()) return false
            
            val marketDataJson = reportFile.readText()
            
            // 3. Generate Script via Gemini
            println("🤖 [StockConsumer] Generating Morning Briefing Script...")
            val scriptResponse = geminiService.writeMorningBriefingScript(marketDataJson)
            
            if (scriptResponse.scenes.isEmpty()) {
                println("⚠️ [StockConsumer] Failed to generate morning script.")
                return false
            }

            // 4. Create History
            val today = java.time.LocalDate.now().toString()
            val history = VideoHistory(
                channelId = channelId,
                title = scriptResponse.title.ifBlank { "오늘의 모닝 브리핑" },
                summary = "간밤의 미 증시 요약 및 오늘 국장 관전 포인트",
                link = "https://finance.yahoo.com/morning-briefing-$today", // 필수 파라미터 추가 및 일별 고유값 부여
                status = VideoStatus.QUEUED,
                description = scriptResponse.description,
                tags = scriptResponse.tags,
                sources = scriptResponse.sources,
                updatedAt = java.time.LocalDateTime.now()
            )
            val saved = videoHistoryRepository.save(history)

            // 5. Trigger Pipeline (Directly to ScriptCreated stage)
            eventPublisher.publishScriptCreated(com.sciencepixel.event.ScriptCreatedEvent(
                channelId = channelId,
                videoId = saved.id!!,
                title = saved.title,
                script = objectMapper.writeValueAsString(scriptResponse.scenes),
                summary = saved.summary,
                sourceLink = "https://finance.yahoo.com",
                mood = scriptResponse.mood,
                keywords = scriptResponse.scenes.map { it.keyword },
                reportImagePath = reportFile.absolutePath // 경로 전달 추가
            ))

            println("✅ [StockConsumer] Morning Briefing Triggered: ${saved.id}")
            return true
        } catch (e: Exception) {
            println("❌ [StockConsumer] Morning Briefing Error: ${e.message}")
            return false
        }
    }

    private fun executeDeepDiveDiscovery(event: StockDiscoveryRequestedEvent) {
        val sources = rssSourceRepository.findByChannelIdAndIsActive(event.channelId, true)
        if (sources.isEmpty()) return
        
        val items = contentProviderService.fetchStockNews(sources.first())
        items.take(1).forEach { newsItem ->
            val history = VideoHistory(
                channelId = event.channelId,
                title = newsItem.title,
                link = newsItem.link,
                summary = newsItem.summary,
                status = VideoStatus.QUEUED,
                updatedAt = java.time.LocalDateTime.now()
            )
            val saved = videoHistoryRepository.save(history)
            asyncVideoService.createVideoAsync(newsItem, saved.id!!)
        }
    }
}
