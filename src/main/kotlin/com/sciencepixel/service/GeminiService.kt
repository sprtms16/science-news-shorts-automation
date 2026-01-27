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

data class Scene(val sentence: String, val keyword: String)

@Service
class GeminiService(@Value("\${gemini.api-key}") private val apiKey: String) {
    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val CHANNEL_NAME = "사이언스 픽셀"

    // 1. 한국어 대본 작성
    fun writeScript(title: String, summary: String): List<Scene> {
        val prompt = """
            [Role]
            You are '$CHANNEL_NAME', a famous Korean science Shorts YouTuber.
            Your task is to explain the following English news in **KOREAN** (`한국어`).

            [Input News]
            Title: $title
            Summary: $summary

            [Rules]
            1. **Language:** MUST BE KOREAN (한국어). Do not output English sentences in the script.
            2. **Target Duration:** The final video should be close to **60 seconds**.
            3. **Script Length:** Provide a total of **12 to 15 scenes** to ensure sufficient duration.
            4. **Intro:** START with EXACTLY "안녕하세요, $CHANNEL_NAME 입니다!" as the **FIRST SEPARATE SCENE**. (Keyword: waving hello)
            5. **Body:** Explain the news in detail using multiple analogies and step-by-step explanations to fill the 60-second duration.
            6. **Sentence Length:** Keep each sentence around 40-50 characters (including spaces).
            7. **Sentence Integrity:** Each scene MUST be a single, complete, and natural sentence.
            8. **Outro:** END with EXACTLY "유익하셨다면 구독과 좋아요 부탁드려요!" as the **LAST SEPARATE SCENE**. (Keyword: subscribe button)
            9. **Output:** JSON Array ONLY.
            
            [Example Structure (targeting 60s)]
            [
              {"sentence": "안녕하세요, 사이언스 픽셀입니다!", "keyword": "waving hello"},
              {"sentence": "최근 과학계에서 정말 흥미로운 소식이 들려왔는데요.", "keyword": "science news"},
              ... (10 to 13 more body scenes) ...
              {"sentence": "유익하셨다면 구독과 좋아요 부탁드려요!", "keyword": "subscribe button"}
            ]
              
            [Example]
            [
              {"sentence": "안녕하세요, 사이언스 픽셀입니다! 여러분, 화성에 물이 있다는 사실 아시나요?", "keyword": "mars water"},
              {"sentence": "나사가 드디어 결정적인 증거를 찾았습니다.", "keyword": "nasa scientist"}
            ]
        """.trimIndent()
        
        return callGemini(prompt)
    }

    // 2. 비전 검수 (이미지 적합성 판단)
    fun verifyImage(thumbnailUrl: String, context: String): Boolean {
        try {
            val imageBytes = URL(thumbnailUrl).readBytes()
            val base64Image = Base64.getEncoder().encodeToString(imageBytes)
            
            val prompt = """
                News Context: "$context"
                Task: Is this image appropriate as a background for this news?
                If relevant or abstractly suitable, reply "YES".
                If completely irrelevant (e.g., dancing people for space news), reply "NO".
                Reply ONLY "YES" or "NO".
            """.trimIndent()

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
            
            
            val result = callGeminiRaw(jsonBody, "gemini-flash-latest").trim().uppercase()
            return result.contains("YES")
        } catch (e: Exception) {
            println("⚠️ Vision Check Error: ${e.message}")
            return true // 에러 시 관대하게 통과
        }
    }

    // Helper Methods
    private fun callGemini(prompt: String): List<Scene> {
        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        }
        val responseText = callGeminiRaw(jsonBody, "gemini-flash-latest")
        val list = mutableListOf<Scene>()
        try {
            val jsonArray = JSONArray(responseText)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(Scene(obj.getString("sentence"), obj.getString("keyword")))
            }
        } catch (e: Exception) { println("JSON Error: $responseText") }
        return list
    }

    private fun callGeminiRaw(jsonBody: JSONObject, model: String): String {
        println("🔑 Gemini API Key Loaded: ${if (apiKey.isNotBlank()) "YES - Starts with ${apiKey.take(3)}..." else "NO"}")
        
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            .post(RequestBody.create("application/json".toMediaType(), jsonBody.toString()))
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: "{}"
                if (!response.isSuccessful) {
                    println("❌ Gemini API Request Failed ($model): ${response.message} / Code: ${response.code}")
                    println("Body: $bodyString")
                    return ""
                }
                
                val resJson = JSONObject(bodyString)
                return resJson.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                    ?.optJSONObject(0)?.optString("text") ?: ""
            }
        } catch (e: Exception) {
            println("❌ Gemini Network Error: ${e.message}")
            e.printStackTrace()
            return ""
        }
    }
}
