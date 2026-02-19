package com.sciencepixel.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 채널별 동작 인터페이스
 * 하드코딩된 채널별 로직을 Spring DI로 분리
 */
interface ChannelBehavior {
    val channelId: String
    val isLongForm: Boolean
    val dailyLimit: Int
    val useAsyncFlow: Boolean
    
    /**
     * 채널 표시 이름 (인트로에 사용)
     */
    val channelName: String get() = "Science Pixel"
    
    /**
     * Gemini 프롬프트에 추가할 채널 특화 지시사항
     */
    fun getExtraPrompt(today: String): String
    
    /**
     * 스크립트 생성용 시스템 프롬프트 (채널별 오버라이드 가능)
     */
    fun getScriptSystemPrompt(): String = ""
    
    /**
     * BGM 카테고리 (futuristic, suspense, corporate, epic, calm 등)
     */
    fun getBgmCategory(): String = "calm"
    
    /**
     * 생성 스킵 조건 (Renderer 등)
     */
    fun shouldSkipGeneration(): Boolean = false

    /**
     * 엄격한 날짜 체크 필요 여부 (오늘 생성된 영상만 업로드)
     */
    val requiresStrictDateCheck: Boolean get() = false
    
    /**
     * 뉴스 집계(Aggregation) 필요 여부 (Stocks 채널 등)
     */
    val shouldAggregateNews: Boolean get() = false

    /**
     * 업로드 시 사용할 기본 태그 리스트 (YouTube Tags)
     */
    val defaultTags: List<String> get() = listOf("Shorts")

    /**
     * 설명란에 추가할 기본 해시태그 (Description Hashtags)
     */
    val defaultHashtags: String get() = "#shorts"
}

/**
 * 기본 채널 동작 (science, horror 등 일반 채널)
 */
@Component
@ConditionalOnProperty(
    name = ["SHORTS_CHANNEL_ID"],
    havingValue = "science",
    matchIfMissing = true
)
class DefaultChannelBehavior : ChannelBehavior {
    override val channelId = "science"
    override val channelName = "사이언스픽셀"
    override val isLongForm = false
    override val dailyLimit = 10
    override val useAsyncFlow = false
    
    override fun getExtraPrompt(today: String) = ""
    override fun getBgmCategory() = "futuristic"
    
    override val defaultTags = listOf("science", "news", "shorts", "sciencepixel")
    override val defaultHashtags = "#science #news #shorts"
}

/**
 * Horror 채널 동작
 */
@Component
@ConditionalOnProperty(name = ["SHORTS_CHANNEL_ID"], havingValue = "horror")
class HorrorChannelBehavior : ChannelBehavior {
    override val channelId = "horror"
    override val channelName = "미스터리 픽셀"
    override val isLongForm = false
    override val dailyLimit = 10
    override val useAsyncFlow = false
    
    override fun getExtraPrompt(today: String) = ""
    override fun getBgmCategory() = "suspense"
    
    override val defaultTags = listOf("horror", "mystery", "creepy", "shorts")
    override val defaultHashtags = "#공포 #괴담 #미스터리 #호러 #shorts"
}

/**
 * Stocks 채널 동작 - Long Form, 비동기 플로우
 */
@Component
@ConditionalOnProperty(name = ["SHORTS_CHANNEL_ID"], havingValue = "stocks")
class StocksChannelBehavior : ChannelBehavior {
    override val channelId = "stocks"
    override val channelName = "밸류픽셀"
    override val isLongForm = true
    override val dailyLimit = 1
    override val useAsyncFlow = true
    override val requiresStrictDateCheck = true
    override val shouldAggregateNews = true
    
    override fun getExtraPrompt(today: String) = 
        "7. **Date Context:** Today is $today. Focus on the LATEST market news for this date."
    override fun getBgmCategory() = "corporate"
        
    override val defaultTags = listOf("stocks", "economy", "investment", "shorts")
    override val defaultHashtags = "#주식 #경제 #재테크 #뉴스 #shorts"
}

/**
 * History 채널 동작 - Long Form, 날짜 기반 콘텐츠
 */
@Component
@ConditionalOnProperty(name = ["SHORTS_CHANNEL_ID"], havingValue = "history")
class HistoryChannelBehavior : ChannelBehavior {
    override val channelId = "history"
    override val channelName = "메모리픽셀"
    override val isLongForm = true
    override val dailyLimit = 1
    override val useAsyncFlow = false
    override val requiresStrictDateCheck = true
    
    override fun getExtraPrompt(today: String) = 
        "7. **Date Requirement:** Today is $today. You MUST create a script about a historical event that happened on THIS DATE ($today). Explicitly mention the Date in the intro."
    override fun getBgmCategory() = "epic"
        
    override val defaultTags = listOf("history", "mystery", "facts", "shorts")
    override val defaultHashtags = "#역사 #미스터리 #지식 #history #shorts"
}

/**
 * Renderer 노드 동작 - 모든 채널 처리
 */
@Component
@ConditionalOnProperty(name = ["SHORTS_CHANNEL_ID"], havingValue = "renderer")
class RendererChannelBehavior : ChannelBehavior {
    override val channelId = "renderer"
    override val isLongForm = false
    override val dailyLimit = Int.MAX_VALUE
    override val useAsyncFlow = false
    
    override fun getExtraPrompt(today: String) = ""
    
    override fun shouldSkipGeneration() = true // Renderer는 생성 안 함
}
