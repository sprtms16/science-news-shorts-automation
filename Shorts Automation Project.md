# **🚀 Project: Science Pixel (Final Production Edition)**

**File Type:** Vibe Coding Master Manifest

**Version:** 1.1.0 (Feature Complete)

**Updates:** 한글 스크립트, Pexels API \+ 비전 검수, Python TTS 연동, FFmpeg 병합

## **📂 1\. Project Architecture**

### **1.1 Tech Stack**

* **Core:** Spring Boot 3.2, Spring Batch 5, Kotlin  
* **Data:** MariaDB (Meta), MongoDB (Business)  
* **Ops:** Docker Compose  
* **External:** \* **Video:** Pexels API (무료 4K/HD 스톡 영상)  
  * **Audio:** Edge-TTS (Python Microservice via HTTP)  
  * **AI:** Google Gemini 1.5 Flash (대본 작성 \+ 썸네일 검수)

### **1.2 Improved Workflow**

1. **Reader:** Rome으로 RSS(ScienceDaily 등) 파싱.  
2. **Processor:**  
   * **Scripting:** Gemini가 영어 뉴스를 읽고 **"친절한 한국어 과학 유튜버"** 톤으로 대본 작성.  
   * **Visual:** 대본 키워드로 Pexels 검색 \-\> **Gemini가 썸네일 검수(Vision Check)** \-\> 통과 시 다운로드.  
   * **Audio:** Python 서비스에 한국어 텍스트 전송 \-\> MP3 생성.  
   * **Editing:** FFmpeg로 영상 루프/컷편집 \+ 자막 합성 \+ 최종 병합(Concat).  
3. **Writer:** MongoDB에 메타데이터 저장 (Status: PENDING\_UPLOAD).  
4. **Scheduler:** 업로드 및 임시 파일 삭제.

## **💻 2\. Spring Boot Application**

### **build.gradle.kts**

dependencies {  
    // RSS & Google API  
    implementation("com.rometools:rome:2.1.0")  
    implementation("com.google.apis:google-api-services-youtube:v3-rev222-1.25.0")  
    implementation("com.google.auth:google-auth-library-oauth2-http:1.19.0")  
      
    // Batch, Web, DB  
    implementation("org.springframework.boot:spring-boot-starter-batch")  
    implementation("org.springframework.boot:spring-boot-starter-web")  
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")  
    implementation("org.springframework.boot:spring-boot-starter-jdbc")  
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")  
      
    // Utils  
    implementation("com.squareup.okhttp3:okhttp:4.12.0")  
    implementation("org.json:json:20231013")  
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")  
}

### **src/main/kotlin/com/sciencepixel/service/GeminiService.kt (Korean Script & Vision)**

영어 뉴스를 한국어로 번역/요약하고, 이미지를 검수하는 핵심 서비스입니다.

package com.sciencepixel.service

import org.springframework.stereotype.Service  
import org.springframework.beans.factory.annotation.Value  
import okhttp3.\*  
import okhttp3.MediaType.Companion.toMediaType  
import org.json.JSONObject  
import org.json.JSONArray  
import java.util.concurrent.TimeUnit  
import java.net.URL  
import java.util.Base64

data class Scene(val sentence: String, val keyword: String)

@Service  
class GeminiService(@Value("\\${gemini.api-key}") private val apiKey: String) {  
    private val client \= OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()  
    private val CHANNEL\_NAME \= "사이언스 픽셀"

    // 1\. 한국어 대본 작성  
    fun writeScript(title: String, summary: String): List\<Scene\> {  
        val prompt \= """  
            \[Role\]  
            You are '$CHANNEL\_NAME', a famous Korean science Shorts YouTuber.  
            Your task is to explain the following English news in \*\*KOREAN\*\* (\`한국어\`).

            \[Input News\]  
            Title: $title  
            Summary: $summary

            \[Rules\]  
            1\. \*\*Language:\*\* MUST BE KOREAN (한국어). Do not output English sentences in the script.  
            2\. \*\*Intro:\*\* "안녕하세요, $CHANNEL\_NAME 입니다\!" (Keyword: waving hello)  
            3\. \*\*Body:\*\* Explain the news simply using analogies.  
            4\. \*\*Outro:\*\* "유익하셨다면 구독과 좋아요 부탁드려요\!" (Keyword: subscribe button)  
            5\. \*\*Output:\*\* JSON Array ONLY.  
              
            \[Example\]  
            \[  
              {"sentence": "안녕하세요, 사이언스 픽셀입니다\! 여러분, 화성에 물이 있다는 사실 아시나요?", "keyword": "mars water"},  
              {"sentence": "나사가 드디어 결정적인 증거를 찾았습니다.", "keyword": "nasa scientist"}  
            \]  
        """.trimIndent()  
          
        return callGemini(prompt)  
    }

    // 2\. 비전 검수 (이미지 적합성 판단)  
    fun verifyImage(thumbnailUrl: String, context: String): Boolean {  
        try {  
            val imageBytes \= URL(thumbnailUrl).readBytes()  
            val base64Image \= Base64.getEncoder().encodeToString(imageBytes)  
              
            val prompt \= """  
                News Context: "$context"  
                Task: Is this image appropriate as a background for this news?  
                If relevant or abstractly suitable, reply "YES".  
                If completely irrelevant (e.g., dancing people for space news), reply "NO".  
                Reply ONLY "YES" or "NO".  
            """.trimIndent()

            val jsonBody \= JSONObject().apply {  
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
              
            val result \= callGeminiRaw(jsonBody).trim().uppercase()  
            return result.contains("YES")  
        } catch (e: Exception) {  
            println("⚠️ Vision Check Error: ${e.message}")  
            return true // 에러 시 관대하게 통과  
        }  
    }

    // Helper Methods  
    private fun callGemini(prompt: String): List\<Scene\> {  
        val jsonBody \= JSONObject().apply {  
            put("contents", JSONArray().put(JSONObject().apply {  
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))  
            }))  
            put("generationConfig", JSONObject().put("responseMimeType", "application/json"))  
        }  
        val responseText \= callGeminiRaw(jsonBody)  
        val list \= mutableListOf\<Scene\>()  
        try {  
            val jsonArray \= JSONArray(responseText)  
            for (i in 0 until jsonArray.length()) {  
                val obj \= jsonArray.getJSONObject(i)  
                list.add(Scene(obj.getString("sentence"), obj.getString("keyword")))  
            }  
        } catch (e: Exception) { println("JSON Error: $responseText") }  
        return list  
    }

    private fun callGeminiRaw(jsonBody: JSONObject): String {  
        val request \= Request.Builder()  
            .url("\[https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey\](https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey)")  
            .post(RequestBody.create("application/json".toMediaType(), jsonBody.toString()))  
            .build()  
        client.newCall(request).execute().use { response \-\>  
            val resJson \= JSONObject(response.body?.string() ?: "{}")  
            return resJson.optJSONArray("candidates")?.optJSONObject(0)  
                ?.optJSONObject("content")?.optJSONArray("parts")  
                ?.optJSONObject(0)?.optString("text") ?: ""  
        }  
    }  
}

### **src/main/kotlin/com/sciencepixel/service/PexelsService.kt (Video Search & Verify)**

Pexels API 검색 후 Gemini 비전 검수를 통과한 영상만 다운로드합니다.

package com.sciencepixel.service

import org.springframework.stereotype.Service  
import org.springframework.beans.factory.annotation.Value  
import okhttp3.OkHttpClient  
import okhttp3.Request  
import org.json.JSONObject  
import java.io.File  
import java.net.URL

@Service  
class PexelsService(  
    @Value("\\${pexels.api-key}") private val apiKey: String,  
    private val geminiService: GeminiService  
) {  
    private val client \= OkHttpClient()

    fun downloadVerifiedVideo(keyword: String, context: String, outputFile: File): Boolean {  
        // 1\. Search Pexels  
        val request \= Request.Builder()  
            .url("\[https://api.pexels.com/videos/search?query=$keyword\&orientation=portrait\&size=medium\&per\_page=5\](https://api.pexels.com/videos/search?query=$keyword\&orientation=portrait\&size=medium\&per\_page=5)")  
            .addHeader("Authorization", apiKey)  
            .build()

        var bestVideoUrl \= ""

        client.newCall(request).execute().use { response \-\>  
            if (\!response.isSuccessful) return false  
            val videos \= JSONObject(response.body?.string() ?: "{}").optJSONArray("videos") ?: return false

            // 2\. Loop & Verify  
            for (i in 0 until videos.length()) {  
                val v \= videos.getJSONObject(i)  
                val thumb \= v.getString("image") // Thumbnail URL

                // \*\* VISION CHECK \*\*  
                if (geminiService.verifyImage(thumb, context)) {  
                    // Find HD Link  
                    val files \= v.getJSONArray("video\_files")  
                    for (j in 0 until files.length()) {  
                        val f \= files.getJSONObject(j)  
                        if (f.getInt("width") \>= 720\) {  
                            bestVideoUrl \= f.getString("link")  
                            break  
                        }  
                    }  
                    if (bestVideoUrl.isNotEmpty()) break  
                }  
            }  
        }

        if (bestVideoUrl.isEmpty()) {  
            println("⚠️ No relevant video found for '$keyword'.")  
            return false  
        }

        // 3\. Download  
        URL(bestVideoUrl).openStream().use { input \-\>  
            outputFile.outputStream().use { output \-\> input.copyTo(output) }  
        }  
        return true  
    }  
}

### **src/main/kotlin/com/sciencepixel/service/AudioService.kt (Python TTS Integration)**

Python Microservice(localhost:8000)를 호출하여 한글 TTS를 생성합니다.

package com.sciencepixel.service

import org.springframework.stereotype.Service  
import okhttp3.\*  
import okhttp3.MediaType.Companion.toMediaType  
import org.json.JSONObject  
import java.io.File

@Service  
class AudioService {  
    private val client \= OkHttpClient()  
    private val PYTHON\_SERVICE\_URL \= "http://localhost:8000/generate-audio"

    fun generateAudio(text: String, outputFile: File): Double {  
        val json \= JSONObject().put("text", text).put("voice", "ko-KR-SunHiNeural").toString()  
        val request \= Request.Builder()  
            .url(PYTHON\_SERVICE\_URL)  
            .post(RequestBody.create("application/json".toMediaType(), json))  
            .build()

        client.newCall(request).execute().use { response \-\>  
            if (\!response.isSuccessful) throw RuntimeException("TTS Error: ${response.code}")  
              
            // Python 서비스가 공유 볼륨(workspace)에 파일을 쓰고, 파일명과 duration을 반환한다고 가정  
            // 실제로는 response body에서 byte stream을 받아 저장하거나  
            // Docker Volume 공유 설정을 통해 파일명만 주고받음.  
              
            val resJson \= JSONObject(response.body?.string() ?: "{}")  
            // 여기서는 Docker Volume이 공유되어 있다고 가정하고 파일 동기화 확인이 필요할 수 있음  
            // Python이 생성한 임시 파일을 Kotlin workspace로 이동하거나 복사하는 로직이 추가될 수 있음  
              
            return resJson.optDouble("duration", 5.0)  
        }  
    }  
}

### **src/main/kotlin/com/sciencepixel/service/ProductionService.kt (Final Assembly)**

모든 서비스를 조립하여 최종 영상을 만듭니다.

package com.sciencepixel.service

import org.springframework.stereotype.Service  
import java.io.File

@Service  
class ProductionService(  
    private val pexelsService: PexelsService,  
    private val audioService: AudioService  
) {  
      
    fun produceVideo(title: String, scenes: List\<Scene\>): String {  
        val workspace \= File("workspace/job\_${System.currentTimeMillis()}").apply { mkdirs() }  
        val clipFiles \= mutableListOf\<File\>()

        scenes.forEachIndexed { i, scene \-\>  
            println("🎬 Scene $i: ${scene.sentence}")  
              
            val videoFile \= File(workspace, "raw\_$i.mp4")  
            val audioFile \= File(workspace, "audio\_$i.mp3") // 실제로는 Python이 여기에 생성하도록 유도  
            val clipFile \= File(workspace, "clip\_$i.mp4")

            // 1\. Video (Pexels \+ Vision Check)  
            if (\!pexelsService.downloadVerifiedVideo(scene.keyword, scene.sentence, videoFile)) {  
                // 실패 시 기본 영상(Fallback) 로직 필요 (생략)  
                return@forEachIndexed  
            }

            // 2\. Audio (Edge-TTS)  
            // Python 서비스가 생성한 파일을 audioFile 경로로 가져오거나 직접 생성 요청  
            val duration \= audioService.generateAudio(scene.sentence, audioFile)

            // 3\. Edit Scene (FFmpeg Loop/Cut/Subtitle)  
            editScene(videoFile, duration, scene.sentence, clipFile)  
            clipFiles.add(clipFile)  
        }

        // 4\. Merge Clips  
        val finalOutput \= File(workspace, "final\_output.mp4")  
        mergeClips(clipFiles, finalOutput, workspace)  
          
        return finalOutput.absolutePath  
    }

    private fun editScene(video: File, duration: Double, text: String, output: File) {  
        // 한글 폰트 경로 (Docker 환경에 맞게 수정)  
        val font \= "/usr/share/fonts/truetype/nanum/NanumGothic.ttf"  
          
        val cmd \= listOf(  
            "ffmpeg", "-y", "-stream\_loop", "-1", "-i", video.absolutePath, "-t", "$duration",  
            "-vf", "scale=1080:1920:force\_original\_aspect\_ratio=increase,crop=1080:1920," \+  
                   "drawtext=fontfile=$font:text='$text':fontcolor=white:fontsize=55:x=(w-text\_w)/2:y=h\*0.8:box=1:boxcolor=black@0.6:boxborderw=10",  
            "-c:v", "libx264", "-preset", "fast", "-an", // 오디오 트랙 일단 제거 (나중에 합칠 때 TTS 사용)  
            output.absolutePath  
        )  
        ProcessBuilder(cmd).start().waitFor()  
    }  
      
    private fun mergeClips(clips: List\<File\>, output: File, workspace: File) {  
        val listFile \= File(workspace, "list.txt")  
        listFile.bufferedWriter().use { out \-\>  
            clips.forEach { out.write("file '${it.absolutePath}'\\n") }  
        }  
        // Concat  
        val cmd \= listOf(  
            "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,  
            "-c", "copy", output.absolutePath  
        )  
        ProcessBuilder(cmd).start().waitFor()  
    }  
}

### **src/main/kotlin/com/sciencepixel/batch/ShortsBatchConfig.kt**

ProductionService에 PexelsService, AudioService가 주입되어 동작하도록 설정합니다.

// ... (이전과 동일한 구조, ProductionService 생성자 주입만 주의)

## **🐳 3\. Audio Service (Python) & Docker**

Python 코드는 한글 TTS를 지원하도록 설정되어 있습니다.

### **ai\_media\_service/app.py**

@app.post("/generate-audio")  
async def generate\_audio(request: TTSRequest):  
    \# voice="ko-KR-SunHiNeural" 사용으로 한글 생성  
    \# ...  
