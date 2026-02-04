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
    
    private val CHANNEL_NAME = when (channelId) {
        "science" -> "사이언스 픽셀"
        "horror" -> "미스터리 픽셀"
        "stocks" -> "밸류 픽셀"
        "history" -> "히스토리 픽셀"
        else -> "AI 쇼츠 마스터"
    }

    // Parse keys from comma-separated string
    private val apiKeys: List<String> by lazy {
        apiKeyString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    
    // 할당량 제한 정의
    companion object {
        private const val MAX_RPM = 5
        private const val MAX_TPM = 250_000
        private const val MAX_RPD = 20
        private const val COOLDOWN_MS = 10 * 60 * 1000L
        
        // 지원 모델 풀 (각 모델별로 별도 할당량이 존재함)
        private val SUPPORTED_MODELS = listOf("gemini-3-flash-preview", "gemini-2.5-flash")
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
        fun isAvailable(): Boolean {
            checkAndResetDaily()
            val now = System.currentTimeMillis()
            if (now - lastFailureTime < COOLDOWN_MS) return false
            if (dailyRequestCount >= MAX_RPD) return false
            if (getCurrentRPM() >= MAX_RPM) return false
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
     */
    private fun getSmartKeyAndModel(): KeyModelSelection? {
        if (apiKeys.isEmpty()) return null
        
        // 사용 가능한 모든 조합 생성 후 필터링
        val availablePairs = mutableListOf<KeyModelSelection>()
        apiKeys.forEach { key ->
            SUPPORTED_MODELS.forEach { model ->
                if (combinedQuotas["$key:$model"]?.isAvailable() == true) {
                    availablePairs.add(KeyModelSelection(key, model))
                }
            }
        }
        
        if (availablePairs.isEmpty()) {
            println("⚠️ All Gemini Key/Model combinations are at their limit.")
            return null
        }
        
        // 남은 일일 할당량이 가장 많은 것(사용량이 적은 것) 선택, 고성능 모델(gemini-3) 우선
        return availablePairs.sortedWith(compareBy<KeyModelSelection> { 
            combinedQuotas["${it.apiKey}:${it.modelName}"]?.dailyRequestCount ?: 0
        }.thenBy { 
            // gemini-3를 우선시하도록 인덱스로 가중치
            SUPPORTED_MODELS.indexOf(it.modelName)
        }).firstOrNull()
    }
    
    /**
     * 재시도 로직이 포함된 Gemini API 호출
     */
    private fun callGeminiWithRetry(prompt: String, maxRetries: Int = 3): String? {
        var lastError: Exception? = null
        val triedCombinations = mutableSetOf<String>()
        
        repeat(maxRetries) { attempt ->
            val selection = getSmartKeyAndModel()
            
            if (selection == null) {
                println("⏳ No available Key/Model pairs. Waiting 10 seconds... (${attempt + 1}/$maxRetries)")
                Thread.sleep(10000)
                return@repeat
            }

            val apiKey = selection.apiKey
            val modelName = selection.modelName
            val combinedKey = "$apiKey:$modelName"
            
            if (combinedKey in triedCombinations && triedCombinations.size < combinedQuotas.size) {
                 return@repeat
            }
            triedCombinations.add(combinedKey)
            
            val tracker = combinedQuotas[combinedKey]!!
            tracker.recordAttempt()
            
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
            
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            }
            
            val requestBody = RequestBody.create("application/json".toMediaType(), jsonBody.toString())
            val request = Request.Builder().url(url).post(requestBody).build()
            
            try {
                val response = client.newCall(request).execute()
                val responseCode = response.code
                val text = response.body?.string() ?: ""
                
                if (responseCode == 429) {
                    println("⚠️ Rate Limit (429) for combination: $combinedKey... (Daily: ${tracker.dailyRequestCount})")
                    tracker.recordFailure()
                    lastError = Exception("Rate limit exceeded")
                    response.close()
                    return@repeat
                }
                
                if (responseCode == 200) {
                    val jsonResponse = JSONObject(text)
                    val tokens = jsonResponse.optJSONObject("usageMetadata")?.optInt("totalTokenCount", 0) ?: 0
                    tracker.recordSuccess(tokens)
                    return text
                }
                
                println("⚠️ Gemini Response Code: $responseCode - $text")
                tracker.recordFailure()
                lastError = Exception("Gemini API error: $responseCode")
                response.close()
                
            } catch (e: Exception) {
                println("❌ Gemini Network Error: ${e.message}")
                tracker.recordFailure()
                lastError = e
            }
        }
        
        println("❌ All retry attempts failed: ${lastError?.message}")
        return null
    }

    // Default Prompts (Fallbacks)
    private val DEFAULT_SCRIPT_PROMPT = """
            [Role]
            You are '$CHANNEL_NAME', a famous Korean science Shorts YouTuber.
            Your task is to explain the following English news in **KOREAN** (`한국어`).

            [Input News]
            Title: {title}
            Summary: {summary}

            [Rules]
            1. **Language:** MUST BE KOREAN (한국어). Do not output English sentences in the script/title/description (except keywords).
            2. **Target Audience:** High school and university students interested in science. Use appropriate vocabulary - not too childish, not too academic.
            3. **Content Level:** Explain complex topics in an engaging, accessible way. Include interesting facts and "wow" moments.
            4. **Duration:** ~60 seconds (13-14 sentences).
            5. **Intro/Outro:** Start with "$CHANNEL_NAME" greeting, end with CTA "유익하셨다면 구독과 좋아요 부탁드려요!".
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
            You are a YouTube Growth Strategist for '$CHANNEL_NAME'.
            Analyze these High-Performing Videos from our channel to find Success Patterns.

            [Top Performing Videos]
            $videoSummaries

            [Goal]
            Extract 3-5 concise, actionable rules for creating future scripts and titles that will replicate this success.
            Focus on: Title keywords, Topic selection patterns, Tone, or Hook styles.
            **IMPORTANT**: The advice must be specific to our niche: $CHANNEL_NAME.

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
            "science" -> "You explain recent scientific breakthroughs, space exploration, and high-tech news in an engaging but accurate way."
            "horror" -> "You are a master of terror. You tell the most bone-chilling, disturbing, and terrifying ghost stories. Your goal is to evoke deep fear. Use a visceral, dark, and suffocatingly suspenseful tone. Focus on psychological horror and visceral details that make the viewer's skin crawl."
            "stocks" -> "You analyze current stock market trends and popular stocks. Focus on numbers, analysis, and financial insights."
            "history" -> "You tell fascinating historical facts and stories from the past. Use a narrative and educational tone."
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
            Your task is to explain the following English news/topic in **KOREAN** (`한국어`).

            [Input]
            Title: {title}
            Summary: {summary}

            [Rules]
            1. **Language:** MUST BE KOREAN (한국어). Do not output English sentences in the script/title/description (except keywords).
            2. **Format:** Optimized for YouTube Shorts (~60 seconds, 13-14 sentences).
            3. **Tone:** Appropriate for $CHANNEL_NAME audience. 
            4. **Intro/Outro:** Greeting as $CHANNEL_NAME, end with CTA "유익하셨다면 구독과 좋아요 부탁드려요!".
            5. **Sources:** List names (e.g., "Nature", "Reddit", "Reuters").
            6. **Keywords:** Scenes' keywords MUST be visual, common English terms for stock footage extraction.
            ${if (effectiveChannelId == "history") "7. **Date Requirement:** You MUST explicitly mention the Year/Date of the event in the script and description (e.g., '1920년 5월 1일')." else ""}

            [Output Format - JSON Only]
            Return ONLY a valid JSON object with this exact structure:
            {
                "title": "Korean Title (Catchy, <40 chars)",
                "description": "Korean Description for YouTube",
                "tags": ["tag1", "tag2", "tag3"],
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
        val promptId = "script_prompt_v5"
        
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
        val promptId = "script_prompt_v5" 
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
        val tracker = combinedQuotas[combinedKey]!!
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

    // 4. Extract Keywords for Thumbnail (Auto-Regeneration)
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
        
        val tracker = combinedQuotas["$apiKey:${selection.modelName}"]!!
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
            당신은 '$CHANNEL_NAME' 채널의 과학 뉴스 작가입니다.

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
            Check if the "New News Item" is effectively the SAME TOPIC/STORY as any of the "Recent Videos" for the channel '$CHANNEL_NAME'.
            Ignore minor differences in wording, source, or catchy AI titles. 
            If they cover the same core event, story, or research, it IS a duplicate.
            
            [New News Item]
            Title: $newTitle
            Summary: $newSummary
            
            [Recent Videos from $CHANNEL_NAME]
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
        val nicheAvoidance = when (channelId) {
            "science" -> "Politics, Religion, Ideology, or Social Conflicts unrelated to science."
            "horror" -> "Real-life trauma, sensitive criminal cases still in court, or hate speech."
            "stocks" -> "Illegal financial advice, market manipulation, or non-financial political agenda."
            "history" -> "Promotion of hate groups, modern political propaganda, or sensitive religious conflicts."
            else -> "General controversial topics."
        }

        val prompt = """
            [Task]
            Analyze if the following news item is primarily about SENSITIVE or CONTROVERSIAL topics that should be avoided for the channel '$CHANNEL_NAME'.
            
            [Topics to Avoid for $CHANNEL_NAME]
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
