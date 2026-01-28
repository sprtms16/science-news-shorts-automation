package com.sciencepixel.service

import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Value
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class Scene(val sentence: String, val keyword: String)
data class ScriptResponse(val scenes: List<Scene>, val mood: String)

@Service
class GeminiService(@Value("\${gemini.api-key}") private val apiKeyString: String) {
    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val CHANNEL_NAME = "사이언스 픽셀"
    
    // Parse keys from comma-separated string
    private val apiKeys: List<String> by lazy {
        apiKeyString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    
    // 각 키별 실패 횟수 추적 (HTTP 429 Rate Limit 에러)
    private val keyFailureCount = ConcurrentHashMap<String, AtomicInteger>()
    
    // 마지막 실패 시간 추적 (쿨다운용)
    private val keyLastFailureTime = ConcurrentHashMap<String, Long>()
    
    // 쿨다운 시간 (10분)
    private val COOLDOWN_MS = 10 * 60 * 1000L

    init {
        // 키 초기화
        apiKeys.forEach { key ->
            keyFailureCount[key] = AtomicInteger(0)
            keyLastFailureTime[key] = 0L
        }
        println("🔑 Gemini API Keys Loaded: ${apiKeys.size}개")
    }

    /**
     * 스마트 키 선택: 실패 횟수가 가장 적고 쿨다운이 끝난 키 선택
     */
    private fun getSmartKey(): String {
        if (apiKeys.isEmpty()) return ""
        
        val now = System.currentTimeMillis()
        
        // 쿨다운이 끝난 키들 중에서 실패 횟수가 가장 적은 키 선택
        val availableKeys = apiKeys.filter { key ->
            val lastFailure = keyLastFailureTime[key] ?: 0L
            now - lastFailure > COOLDOWN_MS
        }
        
        // 모든 키가 쿨다운 중이면 가장 오래전에 실패한 키 사용
        val keysToChoose = if (availableKeys.isEmpty()) {
            println("⚠️ 모든 키가 쿨다운 중... 가장 오래된 키 선택")
            apiKeys.sortedBy { keyLastFailureTime[it] ?: 0L }
        } else {
            availableKeys.sortedBy { keyFailureCount[it]?.get() ?: 0 }
        }
        
        val selectedKey = keysToChoose.first()
        val failCount = keyFailureCount[selectedKey]?.get() ?: 0
        println("🔑 Selected Key: ${selectedKey.take(8)}... (Failures: $failCount)")
        
        return selectedKey
    }
    
    /**
     * 키 실패 기록
     */
    private fun recordKeyFailure(key: String) {
        keyFailureCount[key]?.incrementAndGet()
        keyLastFailureTime[key] = System.currentTimeMillis()
        println("❌ Key Failure Recorded: ${key.take(8)}... (Total: ${keyFailureCount[key]?.get()})")
    }
    
    /**
     * 키 성공 시 실패 카운트 리셋
     */
    private fun recordKeySuccess(key: String) {
        keyFailureCount[key]?.set(0)
    }
    
    /**
     * 재시도 로직이 포함된 Gemini API 호출
     */
    private fun callGeminiWithRetry(prompt: String, maxRetries: Int = 3): String? {
        var lastError: Exception? = null
        val triedKeys = mutableSetOf<String>()
        
        repeat(maxRetries) { attempt ->
            val apiKey = getSmartKey()
            
            // 같은 키를 반복 시도하는 경우 스킵
            if (apiKey in triedKeys && triedKeys.size < apiKeys.size) {
                return@repeat
            }
            triedKeys.add(apiKey)
            
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            
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
                    println("⚠️ Rate Limit (429) for key: ${apiKey.take(8)}... Trying another key...")
                    recordKeyFailure(apiKey)
                    lastError = Exception("Rate limit exceeded")
                    response.close()
                    return@repeat
                }
                
                if (responseCode == 200) {
                    recordKeySuccess(apiKey)
                    return text
                }
                
                println("⚠️ Gemini Response Code: $responseCode")
                lastError = Exception("Gemini API error: $responseCode")
                response.close()
                
            } catch (e: Exception) {
                println("❌ Gemini Network Error: ${e.message}")
                lastError = e
            }
        }
        
        println("❌ All retry attempts failed: ${lastError?.message}")
        return null
    }

    // 1. 한국어 대본 작성
    fun writeScript(title: String, summary: String): ScriptResponse {
        val prompt = """
            [Role]
            You are '$CHANNEL_NAME', a famous Korean science Shorts YouTuber.
            Your task is to explain the following English news in **KOREAN** (`한국어`).

            [Input News]
            Title: $title
            Summary: $summary

            [Rules]
            1. **Language:** MUST BE KOREAN (한국어). Do not output English sentences in the script.
            2. **Target Audience:** High school and university students interested in science. Use appropriate vocabulary - not too childish, not too academic.
            3. **Content Level:** Explain complex topics in an engaging, accessible way. Include interesting facts and "wow" moments.
            4. **Target Duration:** The final video should be close to **60 seconds**.
            5. **Number of sentences:** EXACTLY 13 to 14 sentences. No more, no less.
            6. **Sentence length:** Each sentence should be short, natural, and spoken-friendly.
            7. **Channel intro:** FIRST sentence MUST be a greeting introducing the channel: "안녕하세요, $CHANNEL_NAME 입니다!" or similar.
            8. **Opening hook:** SECOND sentence should be a catchy hook about the topic (e.g., "오늘은 정말 놀라운 소식을 전해드릴게요!").
            9. **Channel name:** Naturally mention '$CHANNEL_NAME' at least once more in the script.
            10. **CTA (Call-to-Action):** End with a friendly closing like "유익하셨다면 구독과 좋아요 부탁드려요!"
            11. **Each sentence must pair with a video clip.** The `keyword` should describe what the video should show.
            12. **Keywords must be general English terms** suitable for Pexels video search. Avoid overly specific or abstract words.

            [BGM Mood]
            Choose one mood for the video's background music based on the news content:
            - "calm" for relaxing, peaceful topics
            - "exciting" for breakthrough discoveries or amazing facts
            - "tech" for technology, AI, robotics topics
            - "epic" for space, universe, grand scale topics

            [Output Format - JSON Only]
            Return ONLY a valid JSON object with this exact structure (no markdown, no explanation):
            {
                "scenes": [
                    {"sentence": "한국어 문장1", "keyword": "english search keyword"},
                    {"sentence": "한국어 문장2", "keyword": "english search keyword"},
                    ...
                ],
                "mood": "calm|exciting|tech|epic"
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
            val scenesArray = parsedContent.getJSONArray("scenes")
            val scenes = (0 until scenesArray.length()).map { i ->
                val scene = scenesArray.getJSONObject(i)
                Scene(scene.getString("sentence"), scene.getString("keyword"))
            }
            val mood = parsedContent.optString("mood", "tech")
            
            println("✅ Script Generated: ${scenes.size} scenes, Mood: $mood")
            ScriptResponse(scenes, mood)
        } catch (e: Exception) {
            println("❌ Script Parse Error: ${e.message}")
            println("Response: ${responseText.take(500)}")
            ScriptResponse(emptyList(), "tech")
        }
    }

    // 2. Vision API - 영상 관련성 체크
    fun checkVideoRelevance(thumbnailUrl: String, keyword: String): Boolean {
        println("  🔍 Vision Check (Bypassed): $keyword")
        return true  // Vision check bypassed for speed
    }

    // 3. 영상 관련성 체크 (실제 구현)
    fun checkVideoRelevanceReal(thumbnailUrl: String, keyword: String): Boolean {
        val prompt = """
            Analyze if this video thumbnail is relevant for a shorts video about "$keyword".
            
            Answer ONLY "YES" or "NO".
            - YES: The image clearly shows content related to "$keyword"
            - NO: The image is unrelated, shows watermarks, text overlays, or people's faces
        """.trimIndent()

        val apiKey = getSmartKey()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

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

            val answer = JSONObject(text)
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
        } catch (e: Exception) {
            println("  Vision Error for '$keyword': ${e.message}")
            true // Default to true on error
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
}
