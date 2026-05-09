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

    /**
     * TTS voice ID. ko-KR 기본 음성: SunHiNeural(여, friendly) / InJoonNeural(남)
     */
    val ttsVoice: String get() = "ko-KR-SunHiNeural"

    /**
     * TTS speed. edge-tts rate 형식 (예: "+30%", "-10%")
     */
    val ttsRate: String get() = "+30%"

    /**
     * TTS pitch. edge-tts pitch 형식 (예: "+0Hz", "-30Hz", "+50Hz")
     * 음수일수록 낮고 음산하게, 양수일수록 높고 밝게 들립니다.
     */
    val ttsPitch: String get() = "+0Hz"

    /**
     * TTS volume. edge-tts volume 형식 (예: "+0%", "-15%", "+10%").
     * 음수면 더 조용 → 청자 집중도 ↑ (호러 톤에 효과적).
     */
    val ttsVolume: String get() = "+0%"

    /**
     * Channel-aware TTS lookup. Required because the renderer container runs
     * with SHORTS_CHANNEL_ID=renderer (RendererChannelBehavior) but processes
     * jobs for every channel, so the per-instance ttsVoice/Rate/Pitch above
     * would always resolve to the renderer's defaults. Use these static
     * lookups whenever the effective channelId is known at call time.
     *
     * Horror values v6.7 — calibrated from a survey of top Korean 괴담 radio
     * channels (돌비공포라디오, 왓섭, 쌈무이, 조선별곡, 유민지 호신마마):
     *   - HyunsuMultilingualNeural (male) — most natural / expressive ko-KR
     *     male voice in the free Edge TTS tier; reads as a calm narrator
     *     rather than a movie-trailer voiceover.
     *   - pitch -10Hz   — barely below natural baseline. The earlier -50Hz
     *                     manufactured "menace" but pulled the voice into
     *                     synthetic trailer-territory.
     *   - rate -10%     — Korean clear-speech zone (~70-74% of conversational
     *                     pace per PMC6773961). Slow enough to feel deliberate
     *                     without dragging.
     *   - volume -5%    — minimal attenuation. Real intimacy comes from
     *                     close-mic + post-EQ, not from quieter TTS output.
     * Replaces the v6.5/6.6 "threat tone" recipe (-50Hz / -5% / -15%) which
     * produced an unsettling but storytelling-incompatible voice.
     */
    companion object {
        fun ttsVoiceFor(channelId: String): String = when (channelId) {
            "horror" -> "ko-KR-HyunsuMultilingualNeural"
            else -> "ko-KR-SunHiNeural"
        }

        fun ttsRateFor(channelId: String): String = when (channelId) {
            "horror" -> "-10%"
            else -> "+30%"
        }

        fun ttsPitchFor(channelId: String): String = when (channelId) {
            "horror" -> "-10Hz"
            else -> "+0Hz"
        }

        fun ttsVolumeFor(channelId: String): String = when (channelId) {
            "horror" -> "-5%"
            else -> "+0%"
        }

        /**
         * Effective Korean speech rate (chars/sec, post-atempo) measured from
         * actual rendered output. Used by GeminiService to validate that a
         * generated script's narration will fit the Shorts <60s window.
         * Calibrate by dividing observed final-video duration by total chars.
         *
         *   - horror : HyunsuMultilingualNeural at rate -10%, atempo 1.10
         *              → ~6.0 chars/sec (calm storyteller pace, bumped up from
         *              v6.6's 5.6 because the new voice runs slightly faster
         *              than InJoon at the same rate setting). Recalibrate by
         *              dividing observed video duration by total chars after
         *              the first batch of v6.7 renders.
         *   - others : SunHiNeural at rate +30%, atempo 1.10 → ~8.0 chars/sec
         *              (fast, news-pace delivery)
         */
        fun ttsCharsPerSecondFor(channelId: String): Double = when (channelId) {
            "horror" -> 6.0
            else -> 8.0
        }
    }
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

    // Korean 괴담 narrator profile (v6.7): the consensus voice across top
    // 괴담-radio channels (돌비공포라디오, 왓섭, 쌈무이, 조선별곡) is a calm,
    // mid-pitched male reading voice — the BGM does the scaring, NOT the voice.
    // HyunsuMultilingualNeural is the most natural-sounding ko-KR male in the
    // free Edge TTS tier; pitch stays close to natural; rate sits in the
    // measured "clear-speech" zone (~70-74% of conversational pace).
    // Values mirror ChannelBehavior.companion lookups below.
    override val ttsVoice = "ko-KR-HyunsuMultilingualNeural"
    override val ttsRate = "-10%"
    override val ttsPitch = "-10Hz"
    override val ttsVolume = "-5%"
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
