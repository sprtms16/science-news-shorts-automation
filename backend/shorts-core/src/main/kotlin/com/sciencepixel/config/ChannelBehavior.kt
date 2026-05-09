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
     * Horror values v6.9 — user A/B-tested 9 ko-KR voices at -10%/-10Hz/-5%
     * and chose SunHi (female) as the only one that read as a calm 괴담
     * storyteller rather than a synthetic newscaster. The four edge-tts-
     * usable male voices (InJoon/Hyunsu/HyunsuMultilingual + InJoon style=sad
     * not exposed) all share a similar synthetic timbre that fights the calm
     * storyteller goal. The other five Azure-listed voices (BongJin/GookMin/
     * JiMin/SeoHyeon/YuJin) are NOT exposed by the free Edge TTS endpoint —
     * sample tests showed them returning a stuck 4.25s response regardless
     * of prosody.
     *
     * This matches the agent finding that the Korean *female*-narrator niche
     * (디바제시카, 유민지 호신마마, 별 헤는 괴담 ASMR) sounds more "intimate
     * ghost-story reader" than the male-radio voices in TTS form.
     *
     *   - SunHiNeural   — female, "Friendly, Positive" descriptor; reads as
     *                     warm-mid storyteller in the storyteller prosody.
     *   - pitch -10Hz   — moderate downshift. Female baseline ~200Hz so
     *                     -10Hz has more headroom than the male voices had.
     *   - rate -10%     — Korean clear-speech zone (~70-74% of conversational
     *                     pace per PMC6773961).
     *   - volume -5%    — minimal attenuation. Real intimacy comes from the
     *                     post-EQ low-shelf + compressor in
     *                     ttsAudioPostFilterFor, not from quieter TTS.
     */
    companion object {
        fun ttsVoiceFor(channelId: String): String = when (channelId) {
            "horror" -> "ko-KR-SunHiNeural"
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
         * FFmpeg audio filter chain applied AFTER atempo=1.10 to shape the TTS
         * output toward a warm close-mic radio-narrator timbre that matches
         * the perceptual signature of top Korean 괴담 channels (돌비, 왓섭,
         * 쌈무이). Edge TTS's spectral envelope is "neutral studio" — flat,
         * slightly hyper-articulated, missing the chest/proximity warmth and
         * presence-band tame that real narration mics produce. This recipe
         * reshapes that envelope without needing a different TTS engine.
         *
         *   - highpass 80 Hz   : strip subsonic rumble
         *   - +2.5 dB at 180Hz : chest/proximity warmth (the standard vocal
         *                       warmth zone is 200-300 Hz, +1-3 dB)
         *   - -1.5 dB at 2.8kHz: tame the harsh nasal/presence band that TTS
         *                       over-emphasizes (sounds "hyper-clear")
         *   - -2 dB at 6.5kHz  : tame sibilance peak
         *   - 3:1 compressor   : glues to broadcast feel without crushing dynamics
         *   - alimiter         : safety against clipping
         *
         * Returns "" for channels that should not be reshaped — they keep the
         * raw Edge TTS output (only atempo=1.10 applies) so news-pace channels
         * stay punchy.
         */
        fun ttsAudioPostFilterFor(channelId: String): String = when (channelId) {
            "horror" -> "highpass=f=80,equalizer=f=180:t=h:width=120:g=2.5,equalizer=f=2800:t=h:width=1500:g=-1.5,equalizer=f=6500:t=h:width=2000:g=-2,acompressor=threshold=-18dB:ratio=3:attack=15:release=120:makeup=3,alimiter=limit=0.95"
            else -> ""
        }

        /**
         * Effective Korean speech rate (chars/sec, post-atempo) measured from
         * actual rendered output. Used by GeminiService to validate that a
         * generated script's narration will fit the Shorts <60s window.
         * Calibrate by dividing observed final-video duration by total chars.
         *
         *   - horror : SunHiNeural at rate -10%, atempo 1.10
         *              → ~5.5 chars/sec (slower female voice + slow rate;
         *              recalibrate from a v6.9 sanity render before fine-tuning).
         *   - others : SunHiNeural at rate +30%, atempo 1.10 → ~8.0 chars/sec
         *              (fast, news-pace delivery)
         */
        fun ttsCharsPerSecondFor(channelId: String): Double = when (channelId) {
            "horror" -> 5.5
            else -> 8.0
        }

        /**
         * BGM mix volume relative to narration. The renderer mixes narration
         * at volume=1.2 (~+1.5dB) and BGM at this attenuation factor; the
         * difference between the two should land in the 12-16 dB range so
         * the BGM is present without burying the voice.
         *
         *   - horror : 0.45  (~-7dB → narration/BGM diff ~14 dB; the BGM
         *              IS the dread, must be clearly audible. The legacy
         *              0.20 buried it ~21 dB below narration which the user
         *              perceived as "no BGM".)
         *   - others : 0.20  (~-14dB; news/science/history channels treat
         *              BGM as ambient bed under a voice-led explainer)
         */
        fun bgmMixVolumeFor(channelId: String): Double = when (channelId) {
            "horror" -> 0.45
            else -> 0.20
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

    // Korean 괴담 narrator profile (v6.9) — switched to SunHi (female) after
    // the user A/B-tested 9 voices: SunHi at -10%/-10Hz/-5% was the only one
    // that read as a calm storyteller rather than a synthetic neural TTS.
    // This matches the agent finding that the Korean female-narrator niche
    // (디바제시카, 유민지 호신마마, 별 헤는 괴담 ASMR) sounds more "intimate
    // ghost-story reader" than the male-radio voices in TTS form. The four
    // male voices Edge TTS actually exposes (InJoon/Hyunsu/HyunsuMultilingual)
    // all share a similar synthetic newscaster timbre that fights the calm
    // storyteller goal.
    override val ttsVoice = "ko-KR-SunHiNeural"
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
