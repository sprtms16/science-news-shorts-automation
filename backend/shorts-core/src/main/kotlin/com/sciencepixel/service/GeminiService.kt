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



@Service
class GeminiService(
    @Value("\${gemini.api-key}") private val apiKeyString: String,
    @Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String,
    private val promptRepository: com.sciencepixel.repository.SystemPromptRepository,
    private val systemSettingRepository: com.sciencepixel.repository.SystemSettingRepository,
    private val youtubeVideoRepository: com.sciencepixel.repository.YoutubeVideoRepository
) {
    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    
    private fun getChannelName(targetChannelId: String? = null): String {
        return when (targetChannelId ?: channelId) {
            "science" -> "사이언스 픽셀"
            "horror" -> "미스터리 픽셀"
            "stocks" -> "밸류 픽셀"
            "history" -> "히스토리 픽셀"
            else -> "AI 쇼츠 마스터"
        }
    }

    // Parse keys from comma-separated string
    private val apiKeys: List<String> by lazy {
        apiKeyString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    
    // 할당량 제한 정의 (기본값 설정, 개별 모델별 429 응답 시 쿨다운으로 대응)
    companion object {
        private const val MAX_RPM = 15 // Flash 모델 기준 (Pro는 429로 감지)
        private const val MAX_TPM = 1_000_000
        private const val MAX_RPD = 1500
        private const val COOLDOWN_MS = 10 * 60 * 1000L // 쿨다운 10분
        
        // 지원 모델 풀: 사용자가 요청한 모든 무료/프리뷰 모델 포함
        // 성능 및 최신순으로 뒤에 배치하여 우선순위를 높임
        private val SUPPORTED_MODELS = listOf(
            "gemini-1.5-flash-8b",
            "gemini-2.0-flash-lite",
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash-lite-preview-09-2025",
            "gemini-1.5-flash",
            "gemini-2.0-flash",
            "gemini-2.5-flash",
            "gemini-2.5-flash-preview-09-2025",
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-flash-native-audio-preview-12-2025",
            "gemini-2.5-pro",
            "gemini-3-flash-preview",
            "gemini-2.0-pro-exp-0205" // 최신 강력 모델 추가
        )
    }

    // 각 (키 + 모델) 조합별 할당량 추적 클래스
    private class QuotaTracker {
        val requestTimestamps = mutableListOf<Long>()
        val tokenUsages = mutableListOf<Pair<Long, Int>>()
        var dailyRequestCount = 0
        var lastResetDate = java.time.LocalDate.now()
        var failureCount = AtomicInteger(0)
        var lastFailureTime = 0L

        @Synchronized
        fun checkAndResetDaily() {
            val today = java.time.LocalDate.now()
            if (today != lastResetDate) {
                dailyRequestCount = 0
                lastResetDate = today
                println("📅 Daily quota reset for a model combination.")
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
            if (now - lastFailureTime < COOLDOWN_MS) return false
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
        fun recordFailure() {
            failureCount.incrementAndGet()
            lastFailureTime = System.currentTimeMillis()
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
        println("🔑 Gemini API Keys Loaded: ${apiKeys.size}개, Models: ${SUPPORTED_MODELS.size}개")
        println("🚀 Total Daily Capacity: ${apiKeys.size * SUPPORTED_MODELS.size * MAX_RPD} requests")
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
        
        // 1. 일일 사용량이 가장 적은 것 우선
        // 2. 최신 모델(index가 높은 것) 우선 시도
        return availablePairs.sortedWith(
            compareBy<KeyModelSelection> { combinedQuotas["${it.apiKey}:${it.modelName}"]?.dailyRequestCount ?: 0 }
            .thenByDescending { SUPPORTED_MODELS.indexOf(it.modelName) }
        ).firstOrNull()
    }
    
    /**
     * 재시도 로직이 포함된 Gemini API 호출
     * 모든 가용 키/모델 조합을 시도할 때까지 반복
     */
    private fun callGeminiWithRetry(prompt: String, maxRetries: Int = 10): String? {
        var lastError: Exception? = null
        val triedCombinations = mutableSetOf<String>()
        val totalPossibleCombinations = combinedQuotas.size
        
        // 최대 시도 횟수를 전체 조합 수와 maxRetries 중 큰 값으로 설정하여 모든 가능성 탐색
        val effectiveMaxAttempts = maxOf(maxRetries, totalPossibleCombinations)
        
        repeat(effectiveMaxAttempts) { attempt ->
            val selection = getSmartKeyAndModel(triedCombinations)
            
            if (selection == null) {
                if (triedCombinations.size >= totalPossibleCombinations) {
                    println("❌ All $totalPossibleCombinations Gemini Key/Model combinations exhausted.")
                    return@repeat
                }
                println("⏳ No available Key/Model pairs right now. Waiting 5 seconds... (${attempt + 1}/$effectiveMaxAttempts)")
                Thread.sleep(5000)
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
                            println("⚠️ Rate Limit (429) for: $combinedKey. Trying next... (${triedCombinations.size}/$totalPossibleCombinations)")
                            tracker.recordFailure()
                            lastError = Exception("Rate limit exceeded (429)")
                        }
                        503, 504 -> {
                            println("⚠️ Service Unavailable (503/504) for: $combinedKey. Trying next...")
                            tracker.recordFailure()
                            lastError = Exception("Service unavailable ($responseCode)")
                        }
                        else -> {
                            println("⚠️ Gemini Error: $responseCode - $text")
                            tracker.recordFailure()
                            lastError = Exception("Gemini API error: $responseCode")
                        }
                    }
                }
            } catch (e: Exception) {
                println("❌ Gemini Connection Error: ${e.message}")
                tracker.recordFailure()
                lastError = e
            }
        }
        
        println("❌ All possible combinations failed. Last Error: ${lastError?.message}")
        return null
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

        val responseText = callGeminiWithRetry(prompt) ?: return "Failed to generate insights."

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
            
            println("📈 Channel Growth Analysis Complete for $channelId:\n$finalInsights")
            finalInsights
        } catch (e: Exception) {
            println("❌ Growth Analysis Error for $channelId: ${e.message}")
            "Error parsing insights."
        }
    }

    private fun getDefaultScriptPrompt(targetChannelId: String? = null): String {
        val effectiveChannelId = targetChannelId ?: channelId
        val nicheContext = when (effectiveChannelId) {
            "science" -> """
                [Role]
                You are the Lead Communicator for 'Science Pixel' (사이언스 픽셀), a science/tech YouTube channel. 
                Your goal is to break down complex scientific principles and cutting-edge tech into 'pixel-sized' pieces that are incredibly easy and clear for the public to understand.

                [Channel Identity]
                - Tone & Manner: Curious, futuristic, smart, clear, and witty.
                - Target Audience: Early adopters and the general public curious about technological trends and global changes.
                - Language Style: Friendly yet intelligent '해요체' (~합니다, ~거든요). High-pacing rhythmic sentences.

                [Structure]
                1. Hook (0-5s): Start with a visual shock or a question that triggers an "Is this possible?" reaction (e.g., "Invisibility cloaks are now a reality.")
                2. Body (Explanation): 
                    - Replace jargon with everyday analogies (e.g., "Quantum computers are like solving a maze simultaneously").
                    - Visually describe 'How it works'.
                    - Objectively discuss both benefits and current limitations.
                3. Impact (Future Outlook): Add a specific vision of how this tech will change our lives 10 years after commercialization.
                4. Outro: End with the signature phrase "미래의 조각을 모으는 곳, 사이언스 픽셀이었습니다."

                [Output Format Hint]
                - [Visual Example] Should include specific graphics or footage instructions (e.g., [Screen: CG of DNA helix unraveling into data]).
                - [Narration] Should be fast-paced and upbeat.
            """.trimIndent()
            "horror" -> """
                [Role]
                You are the Lead Storyteller for 'Mystery Pixel' (미스터리 픽셀), a YouTube channel specializing in horror and mystery stories. 
                Your goal is to adapt raw material (Reddit horror, Japanese 2ch/5ch ghost stories, true mystery, unsolved incidents) into chilling, 기묘한 (uncanny) Korean scripts that leave viewers breathless with terror.

                [Channel Identity]
                - Tone & Manner: Dark, eerie, suspenseful, and dry yet spine-chilling.
                - Target Audience: Adults who prefer psychological horror and uncanny twists over cheap jump scares.
                - Language Style: Calm, polite formal Korean (~했습니다). High-pacing rhythmic sentences.

                [Structure]
                1. Hook (0-3s): Start with a sentence that constraints the viewer's behavior or triggers intense curiosity (e.g., "Never look back.")
                2. Body (Build-up): 
                    - Use sensory details (shadows, creaking sounds, footsteps) to set the background.
                    - Describe the protagonist's psychological shift (Anxiety -> Terror -> Despair) vividly in 1st person.
                    - For 'Rule-based horror' (규칙 괴담), emphasize the horrifying consequences of breaking rules.
                3. Climax (Twist): Reveal the terrifying truth. Use short, punchy sentences to escalate tension.
                4. Outro (Open Ending): End with a lingering impact, implying the mystery isn't solved or could happen to the viewer.

                [BGM/SFX Instruction]
                - You CAN control the BGM. If you want the BGM to stop suddenly for a chilling effect, include the token [BGM_SILENCE] at the START of the sentence where the music should cut off.
            """.trimIndent()
            "stocks" -> """
                [Role]
                You are the Head Analyst and Lead Writer for 'Value Pixel' (밸류 픽셀), a financial analysis YouTube channel. 
                Your goal is to parse complex financial data and news into logical, insightful, and easy-to-understand Korean scripts.

                [Tone & Manner]
                - Professional, objective, data-driven, and trustworthy.
                - Fast-paced delivery.
                - Target Audience: 2040 investors who value facts, statistics, and balanced logic.
                - Language Style: Use polite formal Korean (~했습니다, ~입니다). Be CLEAR and FIRM.

                [Structure]
                1. Hook (0-5s): Start with a powerful question or a definitive conclusion (e.g., "NVIDIA, is it too late to buy? Let's prove it with numbers.")
                2. Body: Exclude emotional descriptions. Provide evidence using 'numbers', 'statistics', and 'historical precedents'. Balance 'Bull Case' and 'Bear Case'. Explain jargon briefly.
                3. Conclusion: Avoid direct buy/sell advice. Summarize 3 core 'Points to Watch' for the viewer to decide.
                4. Disclaimer: Naturally include the phrase "Investment responsibility lies with you" (투자의 책임은 본인에게 있습니다) at the end.
            """.trimIndent()
            "history" -> """
                [Role]
                You are the Lead Writer for 'Memory Pixel' (메모리 픽셀), a history storytelling YouTube channel. 
                Your goal is to convey historical events as vividly as if the viewer were there, connecting past events to their significance today.

                [Tone & Manner]
                - Grand, emotional, cinematic, and immersive narrative.
                - Target Audience: The public who wants to enjoy history as 'stories of people' rather than dry facts.
                - Language Style: Calm, polite formal Korean (~했습니다). High-pacing rhythmic sentences.

                [Structure]
                1. Intro (Immersion): Start with "X years ago today". Describe the tension or mystery using vivid visual imagery.
                2. Climax: Narrate the decisive moment of the event dramatically (timed for high-tension BGM).
                3. Meaning: Summarize in one sentence how this event shifted history or its lesson for modern times.
                4. Outro: End with the signature phrase "메모리 픽셀이었습니다." to leave a lingering impact.

                [Output Format Hint]
                - Keep sentences short and breathable for narration.
            """.trimIndent()
            else -> "You are a creative content creator."
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
            You are '$effectiveChannelName', a famous Korean YouTuber.
            $nicheContext
            Your task is to explain the following English or Japanese horror/mystery content in **KOREAN** (`한국어`).

            [Input]
            Title: {title}
            Summary: {summary}

            [Rules]
            1. **Language:** MUST BE KOREAN (한국어). Do not output English sentences in the script/title/description (except keywords).
            2. **Format:** Optimized for YouTube Shorts (~60 seconds, 13-14 sentences).
            3. **Tone:** Appropriate for $effectiveChannelName audience. 
            4. **Intro/Outro:** Greeting as $effectiveChannelName, end with CTA "유익하셨다면 구독과 좋아요 부탁드려요!".
            5. **Sources:** List names (e.g., "Nature", "Reddit", "Reuters").
            6. **Keywords:** Scenes' keywords MUST be visual, common English terms for stock footage extraction.
            7. **Hashtags:** In the 'tags' array, generating 3-5 relevant, lowercase English hashtags (e.g., "robot", "space", "ai").

            ${
                run {
                    val todayParam = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("M월 d일"))
                    if (effectiveChannelId == "history") "8. **Date Requirement:** Today is $todayParam. You MUST create a script about a historical event that happened on THIS DATE ($todayParam). Explicitly mention the Date in the intro." 
                    else if (effectiveChannelId == "stocks") "8. **Date Context:** Today is $todayParam. Focus on the LATEST market news for this date." 
                    else ""
                }
            }

            [Output Format - JSON Only]
            Return ONLY a valid JSON object with this exact structure:
            {
                "title": "Korean Title (Catchy, <40 chars)",
                "description": "Korean Description for YouTube",
                "tags": ["lowercase_tag1", "lowercase_tag2", "lowercase_tag3"],
                "sources": ["source1", "source2"],
                "scenes": [
                    {"sentence": "Korean Sentence 1", "keyword": "visual english keyword"},
                    ...
                ],
                "mood": "${getMoodExamples(effectiveChannelId)}"
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
        
        val existing = promptRepository.findByChannelIdAndPromptKey(effectiveChannelId, promptId)
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
        println("✅ Refreshed System Prompt '$promptId' for channel '$effectiveChannelId'")
    }

    // 1. 한국어 대본 작성
    fun writeScript(title: String, summary: String, targetChannelId: String? = null): ScriptResponse {
        val effectiveChannelId = targetChannelId ?: channelId
        val promptId = "script_prompt_v6" 
        var promptTemplate = promptRepository.findByChannelIdAndPromptKey(effectiveChannelId, promptId)?.content
        
        if (promptTemplate == null) {
            println("ℹ️ Prompt '$promptId' for $effectiveChannelId not found in DB. Saving default.")
            refreshSystemPrompts(effectiveChannelId)
            promptTemplate = promptRepository.findByChannelIdAndPromptKey(effectiveChannelId, promptId)?.content
        }
        
        // Inject Growth Insights
        val insights = systemSettingRepository.findByChannelIdAndKey(effectiveChannelId, "CHANNEL_GROWTH_INSIGHTS")?.value ?: ""
        val insightsSection = if (insights.isNotBlank()) {
            "\n\n[Current Channel Success Insights (APPLY THESE)]\n$insights\n"
        } else ""

        val prompt = (promptTemplate ?: getDefaultScriptPrompt(effectiveChannelId))
            .replace("{title}", title)
            .replace("{summary}", summary)
            // Actually, simply appending to the end might be outside the JSON instructions if the prompt ends with JSON example.
            // Better to prepend or replace a placeholder.
            // But since our DEFAULT_SCRIPT_PROMPT puts [Output Format] at the end, appending might confuse it.
            // Let's inject it into [Rules] section if possible, or just add it before [Output Format].
        
        // Let's modify the prompt construction slightly to be safer
        val finalPrompt = if (insights.isNotBlank()) {
            prompt.replace("[Rules]", "[Channel Success Insights]\n$insights\n\n[Rules]")
        } else {
            prompt
        }
        
        val responseText = callGeminiWithRetry(finalPrompt) ?: return ScriptResponse(emptyList(), "tech")
        
        // ... rest of the function ... (I will keep the rest same, just replacing the top part)

        
        return try {
            val jsonResponse = JSONObject(responseText)
            val candidates = jsonResponse.optJSONArray("candidates")
            
            if (candidates == null || candidates.length() == 0) {
                // Check if blocked by safety
                val promptFeedback = jsonResponse.optJSONObject("promptFeedback")
                val blockReason = promptFeedback?.optString("blockReason")
                if (blockReason != null) {
                    println("🛡️ Gemini Blocked by Safety: $blockReason")
                    throw Exception("GEMINI_SAFETY_BLOCKED: $blockReason")
                }
                println("⚠️ No candidates in Gemini response. Possible safety block without detail.")
                throw Exception("GEMINI_NO_CANDIDATES")
            }

            val candidate = candidates.getJSONObject(0)
            val finishReason = candidate.optString("finishReason")
            if (finishReason == "SAFETY") {
                println("🛡️ Gemini Candidate blocked by SAFETY")
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
            
            // Safe Parsing
            val titleRes = parsedContent.optString("title", title)
            val descRes = parsedContent.optString("description", summary)
            
            // Tags
            val tagsList = mutableListOf<String>()
            val tagsArray = parsedContent.optJSONArray("tags")
            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) {
                    tagsList.add(tagsArray.getString(i))
                }
            }
            
            // Sources
            val sourcesList = mutableListOf<String>()
            val sourcesArray = parsedContent.optJSONArray("sources")
            if (sourcesArray != null) {
                for (i in 0 until sourcesArray.length()) {
                    sourcesList.add(sourcesArray.getString(i))
                }
            }

            // Scenes
            val scenesArray = parsedContent.getJSONArray("scenes")
            val scenes = (0 until scenesArray.length()).map { i ->
                val scene = scenesArray.getJSONObject(i)
                Scene(scene.getString("sentence"), scene.getString("keyword"))
            }
            val mood = parsedContent.optString("mood", "tech")
            
            println("✅ Script Generated: ${scenes.size} scenes, Mood: $mood, Title: $titleRes")
            ScriptResponse(scenes, mood, titleRes, descRes, tagsList, sourcesList)
        } catch (e: Exception) {
            println("❌ Script Parse Error: ${e.message}")
            println("Response: ${responseText.take(500)}")
            ScriptResponse(emptyList(), "tech", title = title, description = summary, tags = listOf("Science", "Technology", "Shorts"))
        }
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

        val responseText = callGeminiWithRetry(prompt) ?: return ScriptResponse(emptyList(), "finance")

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
            println("❌ Morning Script Parse Error: ${e.message}")
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
            println("⚠️ Vision Check: No available Key/Model pairs.")
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

            val response = client.newCall(request).execute()
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
                println("  Vision Check: $keyword -> $answer (Relevant: $isRelevant)")
                isRelevant
            } else {
                println("⚠️ Vision API Error: ${response.code} - $text")
                tracker.recordFailure()
                true
            }
        } catch (e: Exception) {
            println("  Vision Error for '$keyword': ${e.message}")
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

        val responseText = callGeminiWithRetry(prompt) ?: return ScriptResponse(emptyList(), "tech")

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
            println("❌ Metadata Regen Error: ${e.message}")
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

        val responseText = callGeminiWithRetry(prompt) ?: return emptyList()
        
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
            println("📈 Extracted Trending Tickers: $list")
            list
        } catch (e: Exception) {
            println("❌ Ticker Extraction Error: ${e.message}")
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

        val responseText = callGeminiWithRetry(prompt) ?: return "science technology"

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
            println("❌ Keyword Extraction Error: ${e.message}")
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
        // Use Flash model for speed/cost effectiveness on audio
        val modelName = "gemini-2.0-flash-exp" 
        
        val tracker = requireNotNull(combinedQuotas["$apiKey:${selection.modelName}"]) { "Tracker not found for $apiKey" }
        tracker.recordAttempt()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${selection.modelName}:generateContent?key=$apiKey"

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

            val response = client.newCall(request).execute()
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
                println("⚠️ Audio Classification Error: ${response.code} - $text")
                tracker.recordFailure()
                "calm"
            }
        } catch (e: Exception) {
            println("❌ Audio Analysis Exception: ${e.message}")
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

        val responseText = callGeminiWithRetry(prompt) ?: return GeneratedNews(
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
            println("❌ Error generating science news: ${e.message}")
            e.printStackTrace()
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

        val responseText = callGeminiWithRetry(prompt) ?: return false
        
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
                println("🤖 Gemini Semantic Check ($channelId): DUPLICATE detected for '$newTitle'")
            }
            isDuplicate
        } catch (e: Exception) {
            println("❌ Similarity Check Error for $channelId: ${e.message}")
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

        val responseText = callGeminiWithRetry(prompt) ?: return true 

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
                println("⛔ Safety Filter ($channelId): UNSAFE topic detected for '$title'")
            }
            !isUnsafe // Return TRUE if SAFE
        } catch (e: Exception) {
            println("❌ Safety Check Error for $channelId: ${e.message}")
            true // Default to SAFE
        }
    }
}
