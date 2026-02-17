package com.sciencepixel.service

import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Value
import okhttp3.*
import com.sciencepixel.domain.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import org.slf4j.LoggerFactory



@Service
class GeminiService(
    @org.springframework.beans.factory.annotation.Value("\${gemini.api-key}") private val apiKeyString: String,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String,
    private val promptRepository: com.sciencepixel.repository.SystemPromptRepository,
    private val systemSettingRepository: com.sciencepixel.repository.SystemSettingRepository,
    private val youtubeVideoRepository: com.sciencepixel.repository.YoutubeVideoRepository,
    private val channelBehavior: com.sciencepixel.config.ChannelBehavior
) {
    // Custom Exceptions for Kafka Retries
    class GeminiRetryableException(message: String) : RuntimeException(message)
    class GeminiFatalException(message: String) : RuntimeException(message)

    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    
    private fun getChannelName(targetChannelId: String? = null): String {
        return when (targetChannelId ?: channelId) {
            "science" -> "사이언스 픽셀"
            "horror" -> "미스터리 픽셀"
            "stocks" -> "밸류 픽셀"
            "history" -> "메모리 픽셀"
            else -> "AI 쇼츠 마스터"
        }
    }

    // Parse keys from comma-separated string
    private val apiKeys: List<String> by lazy {
        apiKeyString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    
    // 할당량 제한 정의 (기본값 설정, 개별 모델별 429 응답 시 쿨다운으로 대응)
    companion object {
        private val logger = LoggerFactory.getLogger(GeminiService::class.java)
        private const val MAX_RPM = 15 // Flash 모델 기준 (Pro는 429로 감지)
        private const val MAX_TPM = 1_000_000
        private const val MAX_RPD = 1500
        private const val COOLDOWN_MS = 10 * 60 * 1000L // 쿨다운 10분
        
        // 지원 모델 풀: 실제로 대본 생성(Text-to-Text)에 사용 가능한 무료/프리뷰 모델 위주로 필터링
        // 성능 및 최신순으로 뒤에 배치하여 우선순위를 높임
        private val SUPPORTED_MODELS = listOf(
            "gemini-2.0-flash-lite",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-3-flash-preview" // 최신 강력 모델
        )
    }

    // 각 (키 + 모델) 조합별 할당량 추적 클래스
    private class QuotaTracker {
        val requestTimestamps = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val tokenUsages = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Int>>()
        var dailyRequestCount = 0
        var lastResetDate = java.time.LocalDate.now()
        var failureCount = AtomicInteger(0)
        var lastFailureTime = 0L
        var cooldownDuration = COOLDOWN_MS

        @Synchronized
        fun checkAndResetDaily() {
            val today = java.time.LocalDate.now()
            if (today != lastResetDate) {
                dailyRequestCount = 0
                lastResetDate = today
                logger.info("Daily quota reset for a model combination.")
            }
        }

        @Synchronized
        fun getCurrentRPM(): Int {
            val now = System.currentTimeMillis()
            requestTimestamps.removeIf { now - it > 60_000 }
            return requestTimestamps.size
        }

        @Synchronized
        fun getCurrentTPM(): Int {
            val now = System.currentTimeMillis()
            tokenUsages.removeIf { now - it.first > 60_000 }
            return tokenUsages.sumOf { it.second }
        }

        @Synchronized
        fun isAvailable(): Boolean {
            checkAndResetDaily()
            val now = System.currentTimeMillis()
            if (now - lastFailureTime < cooldownDuration) return false
            if (dailyRequestCount >= MAX_RPD) return false
            if (getCurrentRPM() >= MAX_RPM) return false
            if (getCurrentTPM() >= MAX_TPM) return false
            return true
        }

        @Synchronized
        fun recordAttempt() {
            requestTimestamps.add(System.currentTimeMillis())
            dailyRequestCount++
        }

        @Synchronized
        fun recordSuccess(tokens: Int) {
            failureCount.set(0)
            tokenUsages.add(System.currentTimeMillis() to tokens)
        }

        @Synchronized
        fun recordFailure(duration: Long = COOLDOWN_MS) {
            failureCount.incrementAndGet()
            lastFailureTime = System.currentTimeMillis()
            cooldownDuration = duration
        }
    }

    // Key format: "API_KEY:MODEL_NAME"
    private val combinedQuotas = ConcurrentHashMap<String, QuotaTracker>()

    init {
        apiKeys.forEach { key ->
            SUPPORTED_MODELS.forEach { model ->
                combinedQuotas["$key:$model"] = QuotaTracker()
            }
        }
        logger.info("Gemini API Keys Loaded: {} keys, Models: {}", apiKeys.size, SUPPORTED_MODELS.size)
        logger.info("Total Daily Capacity: {} requests", apiKeys.size * SUPPORTED_MODELS.size * MAX_RPD)
    }

    data class KeyModelSelection(val apiKey: String, val modelName: String)

    /**
     * 스마트 키/모델 선택: 할당량이 남은 최적의 조합 선택
     * @param excludeCombinations 이미 시도했거나 제외할 조합 세트
     */
    private fun getSmartKeyAndModel(excludeCombinations: Set<String> = emptySet()): KeyModelSelection? {
        if (apiKeys.isEmpty()) return null
        
        val availablePairs = mutableListOf<KeyModelSelection>()
        apiKeys.forEach { key ->
            SUPPORTED_MODELS.forEach { model ->
                val combinedKey = "$key:$model"
                if (combinedKey !in excludeCombinations && combinedQuotas[combinedKey]?.isAvailable() == true) {
                    availablePairs.add(KeyModelSelection(key, model))
                }
            }
        }
        
        if (availablePairs.isEmpty()) return null
        
        // 1. 지터링 적용 (동일 사용량 시 인스턴스 간 충돌 방지)
        // 2. 일일 사용량이 가장 적은 것 우선
        // 3. 최신 모델(index가 높은 것) 우선 시도
        return availablePairs.shuffled().sortedWith(
            compareBy<KeyModelSelection> { combinedQuotas["${it.apiKey}:${it.modelName}"]?.dailyRequestCount ?: 0 }
            .thenByDescending { SUPPORTED_MODELS.indexOf(it.modelName) }
        ).firstOrNull()
    }
    
    /**
     * 재시도 로직이 포함된 Gemini API 호출
     * 모든 가용 키/모델 조합을 시도할 때까지 반복
     */
    private fun callGeminiWithRetry(prompt: String, channelId: String, maxRetries: Int = 10): String? {
        var lastError: Exception? = null
        val triedCombinations = mutableSetOf<String>()
        val totalPossibleCombinations = combinedQuotas.size
        
        // horror 채널의 경우 리소스 낭비 방지를 위해 최대 시도 횟수를 3회로 제한
        val channelMaxRetries = if (channelId == "horror") 3 else maxRetries
        val effectiveMaxAttempts = maxOf(channelMaxRetries, if (channelId == "horror") 3 else totalPossibleCombinations)
        
        repeat(effectiveMaxAttempts) { attempt ->
            val selection = getSmartKeyAndModel(triedCombinations)
            
            if (selection == null) {
                if (triedCombinations.size >= totalPossibleCombinations) {
                    logger.error("All {} Gemini Key/Model combinations exhausted.", totalPossibleCombinations)
                    return@repeat
                }
                val jitter = (Random.nextLong(1, 10)) * 1000L
            logger.warn("No available Key/Model pairs right now. Waiting {} seconds... ({}/{})", 5 + jitter / 1000, attempt + 1, effectiveMaxAttempts)
            Thread.sleep(5000 + jitter)
            return@repeat
            }

            val apiKey = selection.apiKey
            val modelName = selection.modelName
            val combinedKey = "$apiKey:$modelName"
            triedCombinations.add(combinedKey)
            
            val tracker = combinedQuotas[combinedKey] ?: return@repeat
            tracker.recordAttempt()
            
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            }
            
            val requestBody = RequestBody.create("application/json".toMediaType(), jsonBody.toString())
            val request = Request.Builder().url(url).post(requestBody).build()
            
            try {
                client.newCall(request).execute().use { response ->
                    val responseCode = response.code
                    val text = response.body?.string() ?: ""
                    
                    when (responseCode) {
                        200 -> {
                            val jsonResponse = JSONObject(text)
                            val tokens = jsonResponse.optJSONObject("usageMetadata")?.optInt("totalTokenCount", 0) ?: 0
                            tracker.recordSuccess(tokens)
                            return text
                        }
                        429 -> {
                            val jitter = Random.nextLong(1, 120) * 1000L // Increased jitter range
                            logger.warn("⚠️ Rate Limit (429) for: {}. Applying significant cooldown (10m + jitter). ({}/{})", combinedKey, jitter/1000, triedCombinations.size, totalPossibleCombinations)
                            tracker.recordFailure(COOLDOWN_MS + jitter) // Full 10 min cooldown + jitter
                            lastError = Exception("Rate limit exceeded (429)")
                        }
                        400, 404 -> {
                            logger.warn("🚫 Invalid Model or Request ({}) for: {}. Long cooldown 24h. Skip this combination.", responseCode, combinedKey)
                            tracker.recordFailure(24 * 3600_000L) // 24 hours for invalid endpoints/models
                            lastError = Exception("Permanent API error ($responseCode)")
                        }
                        else -> {
                            logger.warn("⚠️ Gemini Error: {} - {}. Default cooldown 15m.", responseCode, text)
                            tracker.recordFailure(15 * 60_000L) // 15 minutes for general errors
                            lastError = Exception("Gemini API error: $responseCode")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("❌ Gemini Connection Error: {}", e.message)
                tracker.recordFailure(60_000L) // 1 min for connection errors
                lastError = e
            }
        }
        
        logger.error("All possible combinations failed. Last Error: {}", lastError?.message)
        
        // Kafka 기반 재시도를 위해 구체적인 예외 발생
        val errorMsg = lastError?.message ?: "Unknown Gemini Error"
        if (errorMsg.contains("Rate limit") || errorMsg.contains("500") || errorMsg.contains("Connection")) {
            throw GeminiRetryableException("Gemini transient failure: $errorMsg")
        } else {
            throw GeminiFatalException("Gemini permanent failure: $errorMsg")
        }
    }

    // Default Prompts (Fallbacks)
    private val DEFAULT_SCRIPT_PROMPT = """
            [Role]
            You are '${getChannelName()}', a famous Korean science Shorts YouTuber.
            Your task is to explain the following English news in **KOREAN** (`한국어`).

            [Input News]
            Title: {title}
            Summary: {summary}

            [Rules]
            1. **Language:** MUST BE KOREAN (한국어). Do not output English sentences in the script/title/description (except keywords).
            2. **Target Audience:** High school and university students interested in science. Use appropriate vocabulary - not too childish, not too academic.
            3. **Content Level:** Explain complex topics in an engaging, accessible way. Include interesting facts and "wow" moments.
            4. **Duration:** ~60 seconds (13-14 sentences).
            5. **Intro/Outro:** Start with "${getChannelName()}" greeting, end with CTA "유익하셨다면 구독과 좋아요 부탁드려요!".
            6. **Evidence & Sources:** You MUST provide a brief "Verification Note" checking accuracy and list sources (e.g., "Nature", "NASA") in the JSON output.
            7. **Description:** Write a compelling YouTube description including the summary and sources.
            8. **Keywords:** Scenes' keywords MUST be visual, common English terms (e.g., 'nebula', 'laboratory', 'robot', 'brain') rather than abstract or overly specific scientific names that might not have stock footage.

            [Output Format - JSON Only]
            Return ONLY a valid JSON object with this exact structure:
            {
                "title": "Korean Title (Catchy, <40 chars)",
                "description": "Korean Description for YouTube (Include summary and sources clearly)",
                "tags": ["tag1", "tag2", "tag3"],
                "sources": ["source1", "source2"],
                "verification": "Fact check note (e.g., 'Verified from Nature journal')",
                "scenes": [
                    {"sentence": "Korean Sentence 1", "keyword": "visual english keyword (1-3 words)"},
                    ...
                ],
                "mood": "calm|exciting|tech|epic"
            }
    """.trimIndent()


    // Additional Analysis and Regeneration Tools


    /**
     * 6. Growth Analysis (Insights)
     * Analyze top performing videos and generate advice for future scripts.
     */
    fun analyzeChannelGrowth(): String {
        // 1. Fetch Top 10% Videos by View Count
        val allVideos = youtubeVideoRepository.findByChannelId(channelId)
        if (allVideos.isEmpty()) return "No videos found for channel $channelId to analyze."
        
        val sortedVideos = allVideos.sortedByDescending { it.viewCount }
        val topCount = (sortedVideos.size * 0.1).toInt().coerceAtLeast(3).coerceAtMost(20)
        val topVideos = sortedVideos.take(topCount)
        
        val videoSummaries = topVideos.joinToString("\n") { 
            "- [${it.viewCount} views] ${it.title}" 
        }

        val prompt = """
            [Task]
            You are a YouTube Growth Strategist for '${getChannelName(channelId)}'.
            Analyze these High-Performing Videos from our channel to find Success Patterns.

            [Top Performing Videos]
            $videoSummaries

            [Goal]
            Extract 3-5 concise, actionable rules for creating future scripts and titles that will replicate this success.
            Focus on: Title keywords, Topic selection patterns, Tone, or Hook styles.
            **IMPORTANT**: The advice must be specific to our niche: ${getChannelName(channelId)}.

            [Output]
            Return ONLY a JSON list of strings (The insights).
            Example: ["Use questions in titles", "Focus on space discoveries", "Start with a shocking fact"]
        """.trimIndent()

        val responseText = callGeminiWithRetry(prompt, channelId) ?: return "Failed to generate insights."

        return try {
            val jsonResponse = JSONObject(responseText)
            val content = jsonResponse.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .removePrefix("```json")
                .removeSuffix("```")
                .trim()
            
            // Validate it's a list
            val insightsArray = JSONArray(content)
            val insightsList = mutableListOf<String>()
            for (i in 0 until insightsArray.length()) {
                insightsList.add(insightsArray.getString(i))
            }
            
            val finalInsights = insightsList.joinToString("\n") { "- $it" }
            
            // Save to System Settings per channel
            val settingKey = "CHANNEL_GROWTH_INSIGHTS"
            val existing = systemSettingRepository.findByChannelIdAndKey(channelId, settingKey)
            
            systemSettingRepository.save(com.sciencepixel.domain.SystemSetting(
                id = existing?.id,
                channelId = channelId,
                key = settingKey,
                value = finalInsights,
                description = "AI-generated success patterns from high-performing videos ($channelId)"
            ))
            
            logger.info("Channel Growth Analysis Complete for {}:\n{}", channelId, finalInsights)
            finalInsights
        } catch (e: Exception) {
            logger.error("Growth Analysis Error for {}: {}", channelId, e.message)
            "Error parsing insights."
        }
    }

    private fun getDefaultScriptPrompt(targetChannelId: String? = null): String {
        val effectiveChannelId = targetChannelId ?: channelId
        val nicheContext = when (effectiveChannelId) {
            "science" -> """
                [Role]
                You are the Lead Communicator for 'Science Pixel' (사이언스 픽셀). 
                Your goal is to break down complex scientific principles into 'pixel-sized' pieces.
                
                [Channel Identity & Rules]
                - **NO GREETINGS**: Never start with "안녕하세요" or "사이언스 픽셀입니다". Start IMMEDIATELY with the core news.
                - **The Hook (0-3s)**: Start IMMEDIATELY with a shocking fact, a visual provocation, or a question that stops the scroll.
                - Tone: Futuristic, smart, and high-pacing rhythmic sentences.
                - Product Intro: Mention EXACT product names clearly.
                - Vision: How this tech changes lives 10 years later.
                - Signature Outro: "미래의 조각을 모으는 곳, 사이언스 픽셀이었습니다." (Shortest possible).
            """.trimIndent()
            "horror" -> """
                [Role]
                You are a Korean Storyteller for 'Mystery Pixel' (미스터리 픽셀).
                
                [Core Rules - CRITICAL]
                - **NO GREETINGS**: Never say "안녕하세요" or use introductory pleasantries.
                - **Preserve Facts**: Keep original names/locations (e.g., 'Kyoto', 'Smith') but write in Korean.
                - **The Hook (0-3s)**: Start with the most bone-chilling fact or the location's eerie atmosphere immediately.
                - Tone: **Cold & Eerie**. Priority on the *shiver factor*.
                - Signature Outro: "미스터리 픽셀이었습니다."
            """.trimIndent()
            "stocks" -> """
                [Role]
                You are the Head Analyst for 'Value Pixel' (밸류 픽셀).
                
                [Core Rules]
                - **NO GREETINGS**: Start directly with the core market insight or a definitive conclusion.
                - Tone: Professional, data-driven, and firm.
                - Delivery: Fast-paced, high information density.
                - Signature Outro: "투자의 책임은 본인에게 있습니다. 밸류 픽셀이었습니다."
            """.trimIndent()
            "history" -> """
                [Role]
                You are 'Memory Pixel' (메모리 픽셀), a humanist historian.

                [Core Rules]
                - **NO GREETINGS**: Start with the tragic or epic moment of the date ({today}) immediately.
                - Perspective: Human-centric, objective ethics. No glorification of violence.
                - Tone: Solemn & Reflective.
                - Signature Outro: "메모리 픽셀이었습니다."
            """.trimIndent()
            else -> "You are a creative content creator. NO GREETINGS allowed."
        }
        
        val effectiveChannelName = when (effectiveChannelId) {
            "science" -> "사이언스 픽셀"
            "horror" -> "미스터리 픽셀"
            "stocks" -> "밸류 픽셀"
            "history" -> "메모리 픽셀"
            else -> "AI 쇼츠 마스터"
        }

        return """
            [Role]
            You are '$effectiveChannelName', a professional YouTuber.
            $nicheContext
            
            [General Hard Rules]
            1. **NO GREETINGS**: Never use "안녕하세요", "반가워요", or any introductory remarks. Start directly with the HOOK.
            2. **Scene Count**: YOU MUST split the story into **exactly 14 scenes**. No more, no less.
            3. **Sentence Length**: Each scene's sentence MUST be **33-43 Korean characters (글자)** long.
               This is CRITICAL for timing. Aim for 38-42 characters. SHORT and PUNCHY sentences only.
               BAD (too short, 12자): "이건 놀라운 발견입니다."
               BAD (too long, 48자): "이 발견은 기존의 물리학 법칙을 완전히 뒤집을 수 있는 혁명적인 연구 결과입니다."
               GOOD (40자): "이 발견은 기존 물리학 법칙을 뒤집을 수 있는 혁명적 연구입니다."
            4. **Duration**: The total script MUST be **47-55 seconds** when read aloud at normal Korean speech speed.
               At 1.10x playback speed this produces a 43-50 second video. Target approximately 470-550 total Korean characters.
            5. **Language**: MUST BE KOREAN (한국어).
            6. **Tone & Speech Style**: Use formal/polite Korean (존댓말) throughout ALL scenes.
               End sentences with formal endings: -습니다, -입니다, -됩니다, -있습니다.
               NEVER use informal speech (반말) like -해, -야, -다, -네, -지.
               Example: "이것은 놀라운 발견입니다" (GOOD) vs "이것은 놀라운 발견이다" (BAD).
            7. **Natural Delivery**: Write sentences that are dense and rhythmic, optimized for 1.10x narration speed. Do NOT use filler words, but do NOT make sentences too short either.
            8. **Information Density**: Don't explain loosely. Pack as much interesting value as possible into every second.
            
            [Input]
            Title: {title}
            Summary: {summary}
            
            [Verification]
            If information is too vague or product info is missing (for Science), add "[BLOCKED: MISSING_PRODUCT_INFO]" at the start of the 'verification' field.

            [Output Format - JSON Only]
            Return ONLY a valid JSON object:
            {
                "title": "Catchy Korean Title",
                "description": "YouTube Description (include sources)",
                "tags": ["tag1", "tag2", "tag3"],
                "sources": ["source1", "source2"],
                "scenes": [
                    {"sentence": "Punchy Korean Sentence 1", "keyword": "visual english keyword"},
                    ...
                ],
                "mood": "${getMoodExamples(effectiveChannelId)}",
                "verification": "Fact check note"
            }
        """.trimIndent()
    }

    private fun getMoodExamples(channelId: String): String {
        return when (channelId) {
            "science" -> "Tech, Futuristic, Exciting, Curious, Synth, Modern, Bright, Inspirational"
            "horror" -> "Terrifying, Bone-chilling, Visceral Horror, Deep Suspense, Nightmare, Dark Ambient, Disturbing, Psychological Thriller, Gruesome, Eerie"
            "stocks" -> "Modern, Corporate, Fast-paced, Intense, Business, Funky, Hip Hop"
            "history" -> "Epic, Orchestral, Historical, Cinematic, Grand, War, Dramatic"
            else -> "Calm, Exciting, Jazz, Lo-fi"
        }
    }

    fun refreshSystemPrompts(targetChannelId: String? = null) {
        val effectiveChannelId = targetChannelId ?: channelId
        val promptId = "script_prompt_v6"
        
        val content = getDefaultScriptPrompt(effectiveChannelId)
        
        val existing = promptRepository.findFirstByChannelIdAndPromptKey(effectiveChannelId, promptId)
        val promptToSave = existing?.copy(
            content = content,
            description = "Refreshed Niche-aware Script Prompt for $effectiveChannelId",
            updatedAt = java.time.LocalDateTime.now()
        ) ?: com.sciencepixel.domain.SystemPrompt(
            channelId = effectiveChannelId,
            promptKey = promptId,
            content = content,
            description = "Niche-aware Script Prompt for $effectiveChannelId"
        )
        
        promptRepository.save(promptToSave)
        logger.info("Refreshed System Prompt '{}' for channel '{}'", promptId, effectiveChannelId)
    }

    // 1. 한국어 대본 작성
    fun writeScript(title: String, summary: String, targetChannelId: String? = null): ScriptResponse {
        val effectiveChannelId = targetChannelId ?: channelId
        val promptId = "script_prompt_v6"
        var promptTemplate = promptRepository.findFirstByChannelIdAndPromptKey(effectiveChannelId, promptId)?.content

        if (promptTemplate == null) {
            logger.info("Prompt '{}' for {} not found in DB. Saving default.", promptId, effectiveChannelId)
            refreshSystemPrompts(effectiveChannelId)
            promptTemplate = promptRepository.findFirstByChannelIdAndPromptKey(effectiveChannelId, promptId)?.content
        }

        // Inject Growth Insights
        val insights = systemSettingRepository.findByChannelIdAndKey(effectiveChannelId, "CHANNEL_GROWTH_INSIGHTS")?.value ?: ""

        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy년 M월 d일"))

        val prompt = (promptTemplate ?: getDefaultScriptPrompt(effectiveChannelId))
            .replace("{title}", title)
            .replace("{summary}", summary)
            .replace("{today}", todayStr)

        val finalPrompt = if (insights.isNotBlank()) {
            prompt.replace("[Rules]", "[Channel Success Insights]\n$insights\n\n[Rules]")
        } else {
            prompt
        }

        // === 검증 & 재시도 로직 ===
        var attempt = 0
        val maxAttempts = 3

        while (attempt < maxAttempts) {
            attempt++

            val responseText = callGeminiWithRetry(finalPrompt, effectiveChannelId)
            if (responseText == null) {
                logger.warn("Gemini API returned null. Retry $attempt/$maxAttempts")
                if (attempt < maxAttempts) continue else return ScriptResponse(emptyList(), "tech")
            }

            val scriptResponse = try {
                val jsonResponse = JSONObject(responseText)
                val candidates = jsonResponse.optJSONArray("candidates")

                if (candidates == null || candidates.length() == 0) {
                    val promptFeedback = jsonResponse.optJSONObject("promptFeedback")
                    val blockReason = promptFeedback?.optString("blockReason")
                    if (blockReason != null) {
                        logger.warn("Gemini Blocked by Safety: {}", blockReason)
                        throw Exception("GEMINI_SAFETY_BLOCKED: $blockReason")
                    }
                    logger.warn("No candidates in Gemini response. Possible safety block without detail.")
                    throw Exception("GEMINI_NO_CANDIDATES")
                }

                val candidate = candidates.getJSONObject(0)
                val finishReason = candidate.optString("finishReason")
                if (finishReason == "SAFETY") {
                    logger.warn("Gemini Candidate blocked by SAFETY")
                    throw Exception("GEMINI_SAFETY_BLOCKED")
                }

                val content = candidate
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
                    .removePrefix("```json")
                    .removeSuffix("```")
                    .trim()

                val parsedContent = JSONObject(content)

                val titleRes = parsedContent.optString("title", title)
                val descRes = parsedContent.optString("description", summary)

                val tagsList = mutableListOf<String>()
                val tagsArray = parsedContent.optJSONArray("tags")
                if (tagsArray != null) {
                    for (i in 0 until tagsArray.length()) {
                        tagsList.add(tagsArray.getString(i))
                    }
                }

                val sourcesList = mutableListOf<String>()
                val sourcesArray = parsedContent.optJSONArray("sources")
                if (sourcesArray != null) {
                    for (i in 0 until sourcesArray.length()) {
                        sourcesList.add(sourcesArray.getString(i))
                    }
                }

                val scenesArray = parsedContent.getJSONArray("scenes")
                val scenes = (0 until scenesArray.length()).map { i ->
                    val scene = scenesArray.getJSONObject(i)
                    Scene(scene.getString("sentence"), scene.getString("keyword"))
                }
                val mood = parsedContent.optString("mood", "tech")

                ScriptResponse(scenes, mood, titleRes, descRes, tagsList, sourcesList)
            } catch (e: Exception) {
                logger.error("Script Parse Error (attempt $attempt/$maxAttempts): {}", e.message)
                logger.error("Response: {}", responseText.take(500))
                if (attempt < maxAttempts) continue else return ScriptResponse(emptyList(), "tech", title = title, description = summary, tags = listOf("Science", "Technology", "Shorts"))
            }

            // === 검증 1: 씬 개수 = 14 ===
            if (scriptResponse.scenes.size != 14) {
                logger.warn("⚠️ Scene count validation failed: {} scenes (expected 14). Retry $attempt/$maxAttempts", scriptResponse.scenes.size)
                if (attempt < maxAttempts) continue else break
            }

            // === 검증 2: 총 duration 체크 (씬별 글자수 soft 로그만) ===
            val totalChars = scriptResponse.scenes.sumOf { it.sentence.length }
            val estimatedRawDuration = totalChars / 10.0  // ~10 한국어 글자/초
            val adjustedDuration = estimatedRawDuration / 1.10

            val tooLongScenes = scriptResponse.scenes.mapIndexedNotNull { i, scene ->
                val len = scene.sentence.length
                if (len > 50) i to len else null
            }
            if (tooLongScenes.isNotEmpty()) {
                logger.warn("⚠️ Scene length soft-check: {} scenes over 50 chars (will affect duration): {}", tooLongScenes.size, tooLongScenes.take(3))
            }

            logger.info("✓ Script scenes parsed: 14 scenes, $totalChars chars, ~${String.format("%.1f", adjustedDuration)}s @ 1.10x")

            if (adjustedDuration < 43 || adjustedDuration > 60) {
                logger.warn("⚠️ Duration out of range: ${String.format("%.1f", adjustedDuration)}s (target 43-60s @ 1.10x). Retry $attempt/$maxAttempts")
                if (attempt < maxAttempts) continue else break
            }

            // === 검증 성공! ===
            logger.info("Script Generated: {} scenes, Mood: {}, Title: {}", scriptResponse.scenes.size, scriptResponse.mood, scriptResponse.title)
            return scriptResponse
        }

        // === 모든 재시도 실패 ===
        logger.error("❌ Script generation failed validation after $maxAttempts attempts")
        throw RuntimeException("Script validation failed: Scene count/duration out of acceptable range after $maxAttempts retries")
    }

    /**
     * 모닝 브리핑 전용 대본 작성
     * @param marketDataJson 수집된 시장 데이터 (JSON 형식)
     */
    fun writeMorningBriefingScript(marketDataJson: String): ScriptResponse {
        val todayParam = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("M월 d일"))
        
        val prompt = """
            [Role]
            당신은 '밸류 픽셀'의 수석 애널리스트입니다. 
            간밤의 미국 시장 데이터를 보고 한국의 2040 투자자들이 출근길에 가볍게 들을 수 있는 핵심 브리핑 스크립트를 작성하세요.

            [Input Data (JSON)]
            $marketDataJson

            [Rules]
            1. **Tone:** 신뢰감 있고 침착하며 단호한 어조 (~했습니다, ~입니다).
            2. **Structure:**
               - 오프닝: "안녕하세요, 밸류 픽셀입니다. $todayParam 간밤의 미 증시 마감 요약해 드립니다."
               - 본문 1 (미 증시): 지수 등락과 핵심 원인 (예: 국채 금리, 고용 지표 등) 언급.
               - 본문 2 (빅테크): 엔비디아, 테슬라 등 주요 종목 등락과 이유.
               - 본문 3 (국내 시장 포인트): 미국 장 결과를 봤을 때 오늘 삼성전자나 하이닉스 등 한국 반도체/2차전지 주가 어떻게 될지 예측.
               - 아웃트로: "내용이 도움 되셨다면 구독과 좋아요 부탁드립니다. 투자의 책임은 본인에게 있습니다."
            3. **Time:** 약 60초 분량 (총 12~14문장).
            4. **Visual Keywords:** 씬별 키워드는 픽셀이나 영상 소스 추출을 위해 'Stock Market', 'Business Chart', 'New York City', 'Semiconductor' 등 영어로 작성하세요.

            [Output Format - JSON Only]
            Return ONLY a valid JSON object with this exact structure:
            {
                "title": "Korean Title (Catchy, <40 chars)",
                "description": "Korean Description for YouTube",
                "tags": ["미국상황", "나스닥", "삼성전자", "주식쇼츠", "모닝리포트"],
                "sources": ["Investing.com", "yfinance"],
                "scenes": [
                    {"sentence": "KOREAN_SENTENCE", "keyword": "ENGLISH_VISUAL_KEYWORD"},
                    ...
                ],
                "mood": "finance"
            }
        """.trimIndent()

        val responseText = callGeminiWithRetry(prompt, "stocks") ?: return ScriptResponse(emptyList(), "finance")

        return try {
            val content = JSONObject(responseText)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .removePrefix("```json")
                .removeSuffix("```")
                .trim()

            val parsedContent = JSONObject(content)
            val scenesArray = parsedContent.getJSONArray("scenes")
            val scenes = (0 until scenesArray.length()).map { i ->
                val scene = scenesArray.getJSONObject(i)
                Scene(scene.getString("sentence"), scene.getString("keyword"))
            }

            ScriptResponse(
                scenes = scenes,
                mood = parsedContent.optString("mood", "finance"),
                title = parsedContent.optString("title", "모닝 브리핑"),
                description = parsedContent.optString("description", ""),
                tags = parsedContent.optJSONArray("tags")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                sources = parsedContent.optJSONArray("sources")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            )
        } catch (e: Exception) {
            logger.error("Morning Script Parse Error: {}", e.message)
            ScriptResponse(emptyList(), "finance")
        }
    }

    // 2. Vision API - 영상 관련성 체크
    fun checkVideoRelevance(thumbnailUrl: String, keyword: String): Boolean {
        // AI 품질 검수 로직 활성화
        return checkVideoRelevanceReal(thumbnailUrl, keyword)
    }

    // 3. 영상 관련성 체크 (실제 구현)
    fun checkVideoRelevanceReal(thumbnailUrl: String, keyword: String): Boolean {
        val prompt = """
            Analyze if this video thumbnail is relevant for a shorts video about "$keyword".
            
            Answer ONLY "YES" or "NO".
            - YES: The image clearly shows content related to "$keyword"
            - NO: The image is unrelated, shows watermarks, text overlays, or people's faces
        """.trimIndent()

        val selection = getSmartKeyAndModel()
        if (selection == null) {
            logger.warn("Vision Check: No available Key/Model pairs.")
            return true
        }
        
        val apiKey = selection.apiKey
        val modelName = selection.modelName
        val combinedKey = "$apiKey:$modelName"
        
        val tracker = requireNotNull(combinedQuotas[combinedKey]) { "Tracker not found for $combinedKey" }
        tracker.recordAttempt()
        
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        return try {
            // Fetch and encode image
            val imageBytes = URL(thumbnailUrl).openStream().use { it.readBytes() }
            val base64Image = Base64.getEncoder().encodeToString(imageBytes)

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                }))
            }

            val requestBody = RequestBody.create("application/json".toMediaType(), jsonBody.toString())
            val request = Request.Builder().url(url).post(requestBody).build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""

                if (response.code == 200) {
                    val jsonResponse = JSONObject(text)
                    val tokens = jsonResponse.optJSONObject("usageMetadata")?.optInt("totalTokenCount", 0) ?: 0
                    tracker.recordSuccess(tokens)

                    val answer = jsonResponse
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()
                        .uppercase()

                    val isRelevant = answer.contains("YES")
                    logger.info("Vision Check: {} -> {} (Relevant: {})", keyword, answer, isRelevant)
                    isRelevant
                } else {
                    logger.warn("Vision API Error: {} - {}", response.code, text)
                    tracker.recordFailure()
                    true
                }
            }
        } catch (e: Exception) {
            logger.warn("Vision Error for '{}': {}", keyword, e.message)
            tracker.recordFailure()
            true // Default to true on error
        }
    }

    // 3. Metadata Renewal (Metadata Only)
    fun regenerateMetadataOnly(currentTitle: String, currentSummary: String, targetChannelId: String? = null): ScriptResponse {
        val effectiveChannelId = targetChannelId ?: channelId
        val effectiveChannelName = when (effectiveChannelId) {
            "science" -> "사이언스 픽셀"
            "horror" -> "미스터리 픽셀"
            "stocks" -> "밸류 픽셀"
            "history" -> "메모리 픽셀"
            else -> "AI 쇼츠 마스터"
        }
        
        val prompt = """
            [Task]
            You are '$effectiveChannelName'. Update the metadata for this existing science news video into **KOREAN**.
            The video is already made, so just generate the Title, Description, Tags, and Sources.

            [Input Info]
            Original Title: $currentTitle
            Original Summary: $currentSummary

            [Rules]
            1. **Language:** MUST BE KOREAN (한국어).
            2. **Title:** Catchy YouTube Shorts title (<40 chars). KOREAN ONLY.
            3. **Description:** Informative YouTube description. PLAIN TEXT ONLY. NO HTML tags or links. Include brief summary.
            4. **Tags:** 5-8 relevant hashtags (Korean/English mix). Do NOT include '#' prefix.
            5. **Sources:** ONLY source names (e.g., "Nature", "NASA", "ScienceDaily"). NO URLs or HTML.

            [Output Format - JSON Only]
            Return ONLY a valid JSON object:
            {
                "title": "한글 제목",
                "description": "한글 설명 (HTML 없이 순수 텍스트)",
                "tags": ["과학", "science", "news"],
                "sources": ["Nature", "NASA"],
                "verification": "검증 노트"
            }
        """.trimIndent()

        val responseText = callGeminiWithRetry(prompt, effectiveChannelId) ?: return ScriptResponse(emptyList(), "tech")

        return try {
            val jsonResponse = JSONObject(responseText)
            val content = jsonResponse.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .removePrefix("```json")
                .removeSuffix("```")
                .trim()

            val parsedContent = JSONObject(content)
            
            // Safe Parsing
            val titleRes = parsedContent.optString("title", currentTitle)
            // Clean description - remove any HTML tags
            val rawDesc = parsedContent.optString("description", currentSummary)
            val descRes = rawDesc.replace(Regex("<[^>]*>"), "").trim()
            
            // Tags with defaults
            val tagsList = mutableListOf("SciencePixel", "Shorts", "과학")
            val tagsArray = parsedContent.optJSONArray("tags")
            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) {
                    val tag = tagsArray.getString(i).removePrefix("#").trim()
                    if (tag.isNotEmpty() && tag !in tagsList) tagsList.add(tag)
                }
            }

            // Sources - clean any URLs
            val sourcesList = mutableListOf<String>()
            val sourcesArray = parsedContent.optJSONArray("sources")
            if (sourcesArray != null) {
                for (i in 0 until sourcesArray.length()) {
                    val source = sourcesArray.getString(i)
                        .replace(Regex("<[^>]*>"), "") // Remove HTML
                        .replace(Regex("https?://\\S+"), "") // Remove URLs
                        .trim()
                    if (source.isNotEmpty()) sourcesList.add(source)
                }
            }

            // Do NOT generate scenes. Return empty scenes.
            ScriptResponse(emptyList(), "tech", titleRes, descRes, tagsList, sourcesList)
        } catch (e: Exception) {
            logger.error("Metadata Regen Error: {}", e.message)
            ScriptResponse(emptyList(), "tech", currentTitle, currentSummary, listOf("SciencePixel", "Shorts"))
        }
    }

    // 5. Extract Trending Tickers from Headlines (Dynamic Stock Discovery)
    fun extractTrendingTickers(headlines: String): List<String> {
        val prompt = """
            [Task]
            Analyze the following recent business news headlines and identify the top 3-5 most significant stock tickers or company names that are currently 'trending' or experiencing major moves (e.g., earnings suprise, crash, surge).
            
            [Headlines]
            $headlines
            
            [Rules]
            1. Return ONLY a JSON list of strings.
            2. Each string should be a company name or ticker symbol (e.g., "Nvidia", "Tesla", "Samsung", "Bitcoin").
            3. Select only the most critical ones impacting the market.
            4. If nothing is significant, return an empty list [].
            
            [Output Example]
            ["Nvidia", "AMD", "Google"]
        """.trimIndent()

        val responseText = callGeminiWithRetry(prompt, "stocks") ?: return emptyList()
        
        return try {
            val jsonResponse = JSONObject(responseText)
            val content = jsonResponse.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .removePrefix("```json")
                .removeSuffix("```")
                .trim()
            
            val jsonArray = JSONArray(content)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
            logger.info("Extracted Trending Tickers: {}", list)
            list
        } catch (e: Exception) {
            logger.error("Ticker Extraction Error: {}", e.message)
            emptyList()
        }
    }
    fun extractThumbnailKeyword(title: String, description: String): String {
        val prompt = """
            [Task]
            You are an expert Stock Photo Searcher.
            Convert the following YouTube Video Title and Description (KOREAN) into the **BEST SINGLE ENGLISH SEARCH KEYWORD** for finding a relevant, high-quality stock photo (Pexels).

            [Input]
            Title: $title
            Description: $description

            [Rules]
            1. Output MUST be in **ENGLISH**.
            2. Output MUST be 1-3 words max.
            3. Focus on the main visual subject (e.g., "Black Hole", "DNA", "Robot", "Mars").
            4. Do NOT output a sentence. Just the keywords.

            [Output Example]
            Input: "블랙홀이 새로운 우주를 만들 수 있다?!"
            Output: Black Hole space
        """.trimIndent()

        val responseText = callGeminiWithRetry(prompt, "science") ?: return "science technology"

        return try {
            val jsonResponse = JSONObject(responseText)
            val text = jsonResponse.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .removePrefix("```json")
                .removeSuffix("```")
                .trim()
            
            // Basic cleanup
            text.filter { it.isLetterOrDigit() || it.isWhitespace() }.take(50)
        } catch (e: Exception) {
            logger.error("Keyword Extraction Error: {}", e.message)
            "science technology"
        }
    }

    // 5. Audio Classification (BGM)
    fun classifyAudio(audioFile: java.io.File, originalFileName: String): String {
        val prompt = """
            [Task]
            Listen to this background music track ("$originalFileName") and classify it into ONE of the following MOOD categories.
            
            [Mood Categories]
            - futuristic: Tech, Sci-Fi, Synth, Modern, Bright, Electronic (for Science/Tech news)
            - suspense: Dark, Eerie, Tension, Mystery, Horror, Thriller (for Mystery/Crime news)
            - corporate: Fast-paced, Intense, Business, Upbeat, Funky (for Stock/Finance news)
            - epic: Orchestral, Cinematic, Grand, War, Dramatic, Heroic (for History news)
            - calm: Jazz, Lo-fi, Acoustic, Relaxing, Ambient (General purpose)

            [Output]
            Return ONLY the category name in lowercase (e.g., "futuristic", "suspense").
            If unsure, choose the closest match.
        """.trimIndent()

        val selection = getSmartKeyAndModel() ?: return "calm"
        val apiKey = selection.apiKey
        val modelName = selection.modelName

        val tracker = requireNotNull(combinedQuotas["$apiKey:$modelName"]) { "Tracker not found for $apiKey:$modelName" }
        tracker.recordAttempt()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        return try {
            val fileBytes = audioFile.readBytes()
            val base64Audio = Base64.getEncoder().encodeToString(fileBytes)

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "audio/mp3") 
                                put("data", base64Audio)
                            })
                        })
                    })
                }))
            }

            val requestBody = RequestBody.create("application/json".toMediaType(), jsonBody.toString())
            val request = Request.Builder().url(url).post(requestBody).build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""

                if (response.code == 200) {
                    val jsonResponse = JSONObject(text)
                    val tokens = jsonResponse.optJSONObject("usageMetadata")?.optInt("totalTokenCount", 0) ?: 0
                    tracker.recordSuccess(tokens)

                    val answer = jsonResponse.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()
                        .lowercase()
                        .replace("\n", "")
                        .replace("```", "")

                    val validCategories = setOf("futuristic", "suspense", "corporate", "epic", "calm")
                    validCategories.find { answer.contains(it) } ?: "calm"
                } else {
                    logger.warn("Audio Classification Error: {} - {}", response.code, text)
                    tracker.recordFailure()
                    "calm"
                }
            }
        } catch (e: Exception) {
            logger.error("Audio Analysis Exception: {}", e.message)
            tracker.recordFailure()
            "calm"
        }
    }

    /**
     * 주제 기반 과학 뉴스 생성
     * 주제만 입력하면 Gemini가 자동으로 뉴스 제목과 요약을 생성
     */
    data class GeneratedNews(val title: String, val summary: String)

    fun generateScienceNews(topic: String, style: String = "news"): GeneratedNews {
        val styleGuide = when (style) {
            "tutorial" -> "교육적이고 단계별 설명 형식으로"
            "facts" -> "흥미로운 사실들을 나열하는 형식으로"
            else -> "최신 과학 뉴스 기사 형식으로"
        }

        val prompt = """
            [Role]
            당신은 '${getChannelName()}' 채널의 과학 뉴스 작가입니다.

            [Task]
            다음 주제에 대해 흥미로운 과학 뉴스를 생성하세요:
            주제: $topic

            [Style]
            $styleGuide

            [Output Format]
            Return ONLY a valid JSON object with this exact structure (no markdown, no explanation):
            {
                "title": "한글 제목 (YouTube Shorts에 적합한 캐치한 제목, 40자 이내)",
                "summary": "한글 요약 (2-3문장, 흥미롭고 정보성 있는 내용)"
            }

            [Example Output]
            {"title": "블랙홀이 새로운 우주를 만들 수 있다?!", "summary": "최근 연구에 따르면 블랙홀이 완전히 새로운 우주로 가는 포털일 수 있다고 합니다. CERN의 과학자들이 사건의 지평선 근처에서 이 이론을 뒷받침하는 특이한 입자 행동을 발견했습니다."}

            [Important]
            - 제목은 반드시 한글로, YouTube Shorts에 적합하게 캐치하게 작성
            - 요약은 반드시 한글로, 사실적이면서도 흥미롭게 작성
            - 과학적으로 정확한 내용
            - 일반 대중이 이해할 수 있도록 쉽게 작성
        """.trimIndent()

        val responseText = callGeminiWithRetry(prompt, "science") ?: return GeneratedNews(
            title = "${topic}에 대한 놀라운 발견!",
            summary = "$topic 에 대한 새로운 연구 결과가 발표되었습니다. 이 발견은 우리의 자연에 대한 이해를 바꿀 수 있습니다."
        )

        return try {
            val jsonResponse = JSONObject(responseText)
            val content = jsonResponse.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .removePrefix("```json")
                .removeSuffix("```")
                .trim()

            val parsedContent = JSONObject(content)
            GeneratedNews(
                title = parsedContent.getString("title"),
                summary = parsedContent.getString("summary")
            )
        } catch (e: Exception) {
            logger.error("Error generating science news: {}", e.message, e)
            GeneratedNews(
                title = "${topic}에 대한 놀라운 발견!",
                summary = "$topic 에 대한 새로운 연구 결과가 발표되었습니다. 이 발견은 우리의 자연에 대한 이해를 바꿀 수 있습니다."
            )
        }
    }

    /**
     * 4. Semantic Similarity Check
     * Check if the new topic is substantively the same as any of the previous videos.
     */
    fun checkSimilarity(newTitle: String, newSummary: String, history: List<com.sciencepixel.domain.VideoHistory>): Boolean {
        if (history.isEmpty()) return false

        val historyText = history.joinToString("\n") { 
            "- [${it.id}] ${it.title} (${it.summary.take(50)}...)" 
        }

        val prompt = """
            [Task]
            Check if the "New News Item" is effectively the SAME TOPIC/STORY as any of the "Recent Videos" for the channel '${getChannelName()}'.
            Ignore minor differences in wording, source, or catchy AI titles. 
            If they cover the same core event, story, or research, it IS a duplicate.
            
            [New News Item]
            Title: $newTitle
            Summary: $newSummary
            
            [Recent Videos from ${getChannelName()}]
            $historyText
            
            [Output]
            Answer ONLY "YES" or "NO".
            - YES: It is a duplicate.
            - NO: It is a new topic.
        """.trimIndent()

        val responseText = callGeminiWithRetry(prompt, channelId) ?: return false
        
        return try {
            val candidateText = JSONObject(responseText)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .uppercase()
            
            val isDuplicate = candidateText.contains("YES")
            if (isDuplicate) {
                logger.info("Gemini Semantic Check ({}): DUPLICATE detected for '{}'", channelId, newTitle)
            }
            isDuplicate
        } catch (e: Exception) {
            logger.error("Similarity Check Error for {}: {}", channelId, e.message)
            false 
        }
    }

    /**
     * 5. Safety & Sensitivity Check
     * Detects Politics, Religion, Ideology, or Social Conflicts.
     */
    fun checkSensitivity(title: String, summary: String, channelId: String): Boolean {
        // [Policy] Stocks channel deals with politics/economy naturally. Disable filter.
        if (channelId == "stocks") return true

        val nicheAvoidance = when (channelId) {
            "science" -> "Politics, Religion, Ideology, or Social Conflicts unrelated to science."
            "horror" -> "Real-life trauma, sensitive criminal cases still in court, or hate speech."
            "stocks" -> "Illegal financial advice, market manipulation, or non-financial political agenda."
            "history" -> "Promotion of hate groups, modern political propaganda, or sensitive religious conflicts."
            else -> "General controversial topics."
        }

        val prompt = """
            [Task]
            Analyze if the following news item is primarily about SENSITIVE or CONTROVERSIAL topics that should be avoided for the channel '${getChannelName()}'.
            
            [Topics to Avoid for ${getChannelName()}]
            $nicheAvoidance
            
            [General Rule]
            - Stay within the core niche.
            
            [Input News]
            Title: $title
            Summary: $summary
            
            [Output]
            Answer ONLY "SAFE" or "UNSAFE".
        """.trimIndent()

        val responseText = callGeminiWithRetry(prompt, channelId) ?: return true 

        return try {
            val candidateText = JSONObject(responseText)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .uppercase()
            
            val isUnsafe = candidateText.contains("UNSAFE")
            if (isUnsafe) {
                logger.warn("Safety Filter ({}): UNSAFE topic detected for '{}'", channelId, title)
            }
            !isUnsafe // Return TRUE if SAFE
        } catch (e: Exception) {
            logger.error("Safety Check Error for {}: {}", channelId, e.message)
            true // Default to SAFE
        }
    }
}
