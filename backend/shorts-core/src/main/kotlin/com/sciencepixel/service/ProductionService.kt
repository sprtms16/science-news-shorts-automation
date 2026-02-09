package com.sciencepixel.service

import com.sciencepixel.config.ChannelBehavior
import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.ProductionResult
import com.sciencepixel.domain.Scene
import kotlinx.coroutines.*
import org.springframework.stereotype.Service
import java.io.File

@Service

class ProductionService(
    private val pexelsService: PexelsService,
    private val audioService: AudioService,
    private val geminiService: GeminiService,
    private val logPublisher: LogPublisher,
    private val channelBehavior: ChannelBehavior,
    @org.springframework.beans.factory.annotation.Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {
    private val isLongForm = channelBehavior.isLongForm
    private val videoWidth = if (isLongForm) 1920 else 1080
    private val videoHeight = if (isLongForm) 1080 else 1920
    private val vfScaleFilter = "scale=$videoWidth:$videoHeight:force_original_aspect_ratio=increase,crop=$videoWidth:$videoHeight"
    data class AssetsResult(
        val mood: String,
        val clipPaths: List<String>,
        val durations: List<Double>,
        val subtitles: List<String>,
        val silenceRanges: List<com.sciencepixel.domain.SilenceRange> = emptyList()
    )

    fun produceAssetsOnly(
        title: String, 
        scenes: List<Scene>, 
        videoId: String, 
        mood: String,
        reportImagePath: String? = null,
        targetChannelId: String? = null, // 추가: 대상 채널 ID
        onProgress: ((progress: Int, step: String) -> Unit)? = null
    ): AssetsResult {
        logPublisher.info("shorts-controller", "Rendering Started: $title", "Scenes: ${scenes.size}ea", traceId = videoId)
        
        val effectiveChannelId = targetChannelId ?: channelId
        // Organize workspace by channel
        val workspace = File("shared-data/workspace/$effectiveChannelId/$videoId").apply { mkdirs() }
        val clipFiles = mutableListOf<File>()
        val durations = mutableListOf<Double>()
        val subtitles = mutableListOf<String>()
        val silenceRanges = mutableListOf<com.sciencepixel.domain.SilenceRange>()

        val totalScenes = scenes.size
        println("📹 [SAGA] Phase 1: Processing scenes for $videoId (${totalScenes}개 씬) - 병렬 처리")
        
        // 병렬 처리를 위한 데이터 클래스
        data class SceneResult(
            val index: Int,
            val clipFile: File,
            val duration: Double,
            val subtitle: String,
            val hasSilence: Boolean
        )

        // 씬 처리를 코루틴으로 병렬화
        val sceneResults = runBlocking {
            scenes.mapIndexed { i, scene ->
                async(Dispatchers.IO) {
                    val sceneProgress = 10 + ((i.toDouble() / totalScenes) * 50).toInt()
                    val step = "씬 ${i + 1}/${totalScenes} 생성 중 (${scene.keyword})"
                    println("📊 [$title] 진행률: $sceneProgress% - $step")
                    onProgress?.invoke(sceneProgress, step)
                    
                    val videoFile = File(workspace, "raw_$i.mp4")
                    val audioFile = File(workspace, "audio_$i.mp3")
                    val clipFile = File(workspace, "clip_$i.mp4")

                    val cleanSentence = scene.sentence.replace("[BGM_SILENCE]", "").trim()
                    val useReportImage = reportImagePath != null && i < 5
                    
                    // 비디오 다운로드
                    if (useReportImage) {
                        println("🖼️ Using report image for scene $i")
                        java.nio.file.Files.copy(
                            java.io.File(reportImagePath!!).toPath(),
                            videoFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    } else {
                        if (!pexelsService.downloadVerifiedVideo(scene.keyword, "$title context: $cleanSentence", videoFile)) {
                            println("⚠️ No video found for '${scene.keyword}'. Trying fallback...")
                            pexelsService.downloadVerifiedVideo("science technology", "fallback context", videoFile)
                        }
                    }
                    
                    // 오디오 생성
                    val duration = try {
                        audioService.generateAudio(cleanSentence, audioFile)
                    } catch (e: Exception) {
                        5.0
                    }

                    editSceneWithoutSubtitle(videoFile, audioFile, duration, clipFile)
                    
                    SceneResult(
                        index = i,
                        clipFile = clipFile,
                        duration = duration,
                        subtitle = cleanSentence,
                        hasSilence = scene.sentence.contains("[BGM_SILENCE]")
                    )
                }
            }.awaitAll()
        }

        // 결과를 인덱스 순으로 정렬하여 조합
        val sortedResults = sceneResults.sortedBy { it.index }
        var totalDuration = 0.0
        
        sortedResults.forEach { result ->
            clipFiles.add(result.clipFile)
            durations.add(result.duration)
            subtitles.add(result.subtitle)
            
            if (result.hasSilence) {
                val silenceStart = totalDuration
                val silenceEnd = totalDuration + result.duration
                silenceRanges.add(com.sciencepixel.domain.SilenceRange(silenceStart, silenceEnd))
                println("🔇 BGM Silence from $silenceStart to $silenceEnd seconds (Scene ${result.index})")
            }
            totalDuration += result.duration
        }
        
        // Return absolute paths
        return AssetsResult(
            mood = mood, 
            clipPaths = clipFiles.map { it.absolutePath },
            durations = durations,
            subtitles = subtitles,
            silenceRanges = silenceRanges
        )
    }

    fun finalizeVideo(
        videoId: String, 
        title: String, 
        clipPaths: List<String>, 
        durations: List<Double>, 
        subtitles: List<String>, 
        mood: String, 
        silenceRanges: List<com.sciencepixel.domain.SilenceRange> = emptyList(), 
        reportImagePath: String? = null,
        targetChannelId: String? = null // 추가
    ): String {
        val effectiveChannelId = targetChannelId ?: channelId
        val workspace = File("shared-data/workspace/$effectiveChannelId/$videoId")
        if (!workspace.exists()) workspace.mkdirs()
        
        val clipFiles = clipPaths.map { File(it) }
        
        // Phase 2: SRT
        val srtFile = File(workspace, "subtitles.srt")
        generateSrtFile(subtitles, durations, srtFile)
        
        // Phase 3: Merge & Burn
        val mergedFile = File(workspace, "merged_no_subs.mp4")
        mergeClipsWithoutSubtitles(clipFiles, mergedFile, workspace)
        
        val sanitizedTitle = title.take(20).replace(Regex("[^a-zA-Z0-9가-힣]"), "_").lowercase()
        val outcomeDir = File("shared-data/videos/$effectiveChannelId").apply { mkdirs() }
        // Use videoId for deterministic filename to avoid duplicates
        val finalOutput = File(outcomeDir, "shorts_${sanitizedTitle}_$videoId.mp4")
        
        burnSubtitlesAndMixBGM(mergedFile, srtFile, finalOutput, mood, workspace, silenceRanges)
        
        logPublisher.info("shorts-controller", "Production Completed: $title", "Path: ${finalOutput.name}", traceId = videoId)
        return finalOutput.absolutePath
    }





    // Entry point for Batch Job (Legacy - Deprecated)
    fun produceVideo(news: NewsItem, videoId: String): ProductionResult {
        println("🎬 Producing video for: ${news.title} (ID: $videoId)")
        
        // 1. Script Generation (Gemini)
        val response = geminiService.writeScript(news.title, news.summary)
        if (response.scenes.isEmpty()) {
            println("⚠️ No script generated for ${news.title}")
            return ProductionResult("", emptyList())
        }
        
        val keywords = response.scenes.map { it.keyword }.distinct()
        val filePath = produceVideoFromScenes(news.title, response.scenes, response.mood, videoId)
        
        // Phase 4: Thumbnail Selection
        val thumbnailPath = try {
            val thumbKeyword = keywords.firstOrNull() ?: news.title
            val thumbUrl = pexelsService.searchPhoto(thumbKeyword)
            if (thumbUrl != null) {
                val thumbFile = File("shared-data/workspace/$channelId/$videoId/thumbnail.jpeg")
                // Download
                java.net.URL(thumbUrl).openStream().use { input ->
                    thumbFile.outputStream().use { output -> input.copyTo(output) }
                }
                thumbFile.absolutePath
            } else ""
        } catch (e: Exception) {
            println("❌ Thumbnail download failed: ${e.message}")
            ""
        }
        
        return ProductionResult(
            filePath = filePath,
            keywords = keywords,
            title = response.title,
            description = response.description,
            tags = response.tags,
            sources = response.sources,
            thumbnailPath = thumbnailPath
        )
    }

    // Core Logic - 3 Phase Pipeline
    private fun produceVideoFromScenes(title: String, scenes: List<Scene>, mood: String, videoId: String): String {
        val workspace = File("shared-data/workspace/$channelId/$videoId").apply { mkdirs() }
        val clipFiles = mutableListOf<File>()
        val durations = mutableListOf<Double>()
        val subtitles = mutableListOf<String>()

        // ========== PHASE 1: Video + Audio (without subtitles) ==========
        println("📹 Phase 1: Processing scenes (video + audio)")
        scenes.forEachIndexed { i, scene ->
            println("🎬 Scene $i: ${scene.sentence}")
            
            val videoFile = File(workspace, "raw_$i.mp4")
            val audioFile = File(workspace, "audio_$i.mp3")
            val clipFile = File(workspace, "clip_$i.mp4")

            // 1. Video (Pexels - License: Free to use, no attribution required)
            if (!pexelsService.downloadVerifiedVideo(scene.keyword, "$title context: ${scene.sentence}", videoFile)) {
                println("⚠️ No video found for '${scene.keyword}'. Trying fallback...")
                if (!pexelsService.downloadVerifiedVideo("science technology", "fallback context", videoFile)) {
                    println("⚠️ Skipping scene $i even after fallback")
                    return@forEachIndexed
                }
            }

            // 2. Audio (Edge-TTS)
            val duration = try {
                 audioService.generateAudio(scene.sentence, audioFile)
            } catch (e: Exception) {
                println("⚠️ Audio generation failed: ${e.message}")
                5.0
            }

            // 3. Edit Scene (NO SUBTITLES - just video + audio)
            editSceneWithoutSubtitle(videoFile, audioFile, duration, clipFile)
            
            clipFiles.add(clipFile)
            durations.add(duration)
            subtitles.add(scene.sentence)
        }

        if (clipFiles.isEmpty()) return ""

        // ========== PHASE 2: Generate SRT Subtitle File ==========
        println("📝 Phase 2: Generating SRT subtitle file")
        val srtFile = File(workspace, "subtitles.srt")
        generateSrtFile(subtitles, durations, srtFile)
        println("✅ SRT file created: ${srtFile.absolutePath}")

        // ========== PHASE 3: Merge + Burn Subtitles ==========
        println("🔥 Phase 3: Merging clips and burning subtitles")
        val mergedFile = File(workspace, "merged_no_subs.mp4")
        mergeClipsWithoutSubtitles(clipFiles, mergedFile, workspace)
        
        val sanitizedTitle = title.take(20)
            .replace(Regex("[^a-zA-Z0-9가-힣]"), "_")
            .lowercase()
        
        // Ensure outcome directory exists
        val outcomeDir = File("shared-data/videos/$channelId").apply { mkdirs() }
        // Use videoId for deterministic filename
        val finalOutput = File(outcomeDir, "shorts_${sanitizedTitle}_$videoId.mp4")
        
        burnSubtitlesAndMixBGM(mergedFile, srtFile, finalOutput, mood, workspace)
        
        // Clean up workspace after successful production
        if (finalOutput.exists() && finalOutput.length() > 0) {
            println("🧹 Cleaning up workspace for: $title")
            workspace.deleteRecursively()
        }
        
        logPublisher.info("shorts-controller", "Batch Production Completed: $title", "Path: ${finalOutput.name}", traceId = videoId)
        return finalOutput.absolutePath
    }

    // Phase 1: Edit scene WITHOUT subtitles
    private fun editSceneWithoutSubtitle(video: File, audio: File, duration: Double, output: File) {
        val isImage = video.extension.lowercase() in listOf("jpg", "jpeg", "png")
        
        val cmd = if (isImage) {
            // Special handling for image background
            mutableListOf(
                "ffmpeg", "-y",
                "-loop", "1", "-i", video.absolutePath,
                "-i", audio.absolutePath,
                "-vf", "scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,setsar=1",
                "-c:v", "h264_nvenc", "-t", duration.toString(),
                "-pix_fmt", "yuv420p", output.absolutePath
            )
        } else {
            mutableListOf(
                "ffmpeg", "-y",
                "-stream_loop", "-1", "-i", video.absolutePath
            )
        }
        
        if (audio.exists()) {
            cmd.add("-i")
            cmd.add(audio.absolutePath)
        } else {
            // Generate silence if audio is missing to maintain stream consistency
            cmd.add("-f")
            cmd.add("lavfi")
            cmd.add("-i")
            cmd.add("anullsrc=channel_layout=stereo:sample_rate=44100")
        }
        
        cmd.addAll(listOf("-t", "$duration", "-vf", vfScaleFilter, "-r", "60"))
        
        // Map video (0:v) and audio (1:a) - audio is either file or silence
        cmd.addAll(listOf("-map", "0:v", "-map", "1:a", "-c:v", "h264_nvenc", "-c:a", "aac"))
        
        if (!audio.exists()) {
             // Shortest ensures it matches the duration constraint (though -t handles it too)
             cmd.add("-shortest")
        }
        
        cmd.addAll(listOf("-preset", "fast", output.absolutePath))
        
        println("Executing FFmpeg Scene Edit (no subs): ${cmd.joinToString(" ")}")
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val processOutput = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            println("FFmpeg Error: $processOutput")
        }
    }

    // Helper function to wrap text into chunks of max 2 lines
    private fun wrapTextToChunks(text: String, maxCharsPerLine: Int = if (isLongForm) 40 else 22): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        
        for (word in words) {
            if (currentLine.isEmpty()) {
                currentLine.append(word)
            } else if (currentLine.length + 1 + word.length <= maxCharsPerLine) {
                currentLine.append(" ").append(word)
            } else {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        
        // Group lines into triplets (max 3 lines per subtitle display)
        return lines.chunked(3).map { it.joinToString("\n") }
    }

    // Phase 2: Generate SRT file with cumulative timestamps
    private fun generateSrtFile(subtitles: List<String>, durations: List<Double>, outputFile: File) {
        val sb = StringBuilder()
        var cumulativeTime = 0.0
        var srtIndex = 1
        
        subtitles.forEachIndexed { index, fullText ->
            val totalDuration = durations[index]
            val chunks = wrapTextToChunks(fullText, 22)
            
            if (chunks.isEmpty()) {
                cumulativeTime += totalDuration
                return@forEachIndexed
            }

            // Calculate proportional duration for each chunk based on character length
            val totalChars = chunks.sumOf { it.length }.toDouble()
            var sceneCurrentTime = cumulativeTime
            
            chunks.forEach { chunk ->
                val chunkDuration = (chunk.length / totalChars) * totalDuration
                val startTime = sceneCurrentTime
                val endTime = sceneCurrentTime + chunkDuration
                
                sb.appendLine("${srtIndex++}")
                sb.appendLine("${formatSrtTime(startTime)} --> ${formatSrtTime(endTime)}")
                sb.appendLine(chunk)
                sb.appendLine()
                
                sceneCurrentTime = endTime
            }
            
            cumulativeTime += totalDuration
        }
        
        outputFile.writeText(sb.toString())
    }
    
    private fun formatSrtTime(seconds: Double): String {
        val hours = (seconds / 3600).toInt()
        val minutes = ((seconds % 3600) / 60).toInt()
        val secs = (seconds % 60).toInt()
        val millis = ((seconds - seconds.toInt()) * 1000).toInt()
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, secs, millis)
    }

    // Phase 3a: Merge clips (without subtitles)
    private fun mergeClipsWithoutSubtitles(clips: List<File>, output: File, workspace: File) {
        val listFile = File(workspace, "list.txt")
        listFile.bufferedWriter().use { out ->
            clips.forEach { out.write("file '${it.absolutePath}'\n") }
        }
        
        val cmd = listOf(
            "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,
            "-c", "copy",
            output.absolutePath
        )
        
        println("Executing FFmpeg Merge: ${cmd.joinToString(" ")}")
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val processOutput = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            println("FFmpeg Merge Error (exit $exitCode): $processOutput")
        } else {
            println("✅ FFmpeg Merge Complete: ${output.absolutePath}")
        }
    }

    // Phase 3b: Burn subtitles and Mix BGM into final video
    private fun burnSubtitlesAndMixBGM(inputVideo: File, srtFile: File, output: File, mood: String, workspace: File, silenceRanges: List<com.sciencepixel.domain.SilenceRange> = emptyList()) {
        // Regex-based escaping for FFmpeg filter path:
        // 1. \ -> / (Windows separator Fix)
        // 2. : -> \: (FFmpeg key:val separator escape)
        // 3. ' -> '\'' (FFmpeg single quote escape inside single quotes)
        val srtPath = srtFile.absolutePath.replace(Regex("[\\\\:']")) { match ->
            when (match.value) {
                "\\" -> "/"
                ":" -> "\\:"
                "'" -> "'\\\\''"
                else -> match.value
            }
        }
        val subtitleFilter = "subtitles='$srtPath':force_style='FontName=NanumGothic,FontSize=10,PrimaryColour=&HFFFFFF,OutlineColour=&H000000,Outline=0.8,Shadow=0.5,Alignment=2,MarginV=50'"
        
        // Find BGM file (Random selection from matching mood files)
        val bgmDir = File("shared-data/bgm").apply { mkdirs() }
        val bgmFiles = bgmDir.listFiles { _, name -> name.startsWith(mood) && name.endsWith(".mp3") }
        
        var bgmFile = if (!bgmFiles.isNullOrEmpty()) {
             bgmFiles.random()
        } else {
             File(bgmDir, "$mood.mp3") // Fallback
        }
        
        // 1. Check Local File
        if (!bgmFile.exists()) {
            println("⚠️ Local BGM not found for '$mood'. Trying AI Generation...")
            val aiBgmFile = File(workspace, "ai_bgm_${java.lang.System.currentTimeMillis()}.wav")
            
            // Generate for 15 seconds (will loop)
            val prompt = "$mood style cinematic background music, high quality"
            if (audioService.generateBgm(prompt, 15, aiBgmFile)) {
                bgmFile = aiBgmFile
            }
        }
        
        val cmd = mutableListOf("ffmpeg", "-y", "-i", inputVideo.absolutePath)
        
        if (bgmFile.exists()) {
            println("🎵 Mixing BGM: ${bgmFile.name} (Mood: $mood)")
            cmd.addAll(listOf("-stream_loop", "-1", "-i", bgmFile.absolutePath))
            
            // Filter complex: mix voice and bgm
            // volume filter on bgm: if silenceRanges are provided, set volume to 0 during those ranges, 0.20 otherwise
            val bgmVolumeFilter = if (silenceRanges.isNotEmpty()) {
                val conditions = silenceRanges.joinToString("+") { range ->
                    "between(t,${range.start},${range.end})"
                }
                "volume='if($conditions, 0, 0.20)':eval=frame"
            } else {
                "volume=0.20"
            }

            cmd.addAll(listOf(
                "-filter_complex", "[0:a]volume=1.2[v];[1:a]$bgmVolumeFilter[bgm];[v][bgm]amix=inputs=2:duration=first[aout];[0:v]$subtitleFilter[vout]",
                "-map", "[vout]", "-map", "[aout]"
            ))
        } else {
            println("⚠️ BGM file not found and Generation failed. Skipping BGM.")
            cmd.addAll(listOf("-vf", subtitleFilter, "-c:a", "copy"))
        }
        
        cmd.addAll(listOf("-c:v", "h264_nvenc", "-preset", "p4", "-cq", "23", "-c:a", "aac", "-movflags", "+faststart", output.absolutePath))
        
        println("Executing FFmpeg Production (Phase 3): ${cmd.joinToString(" ")}")
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val processOutput = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            println("FFmpeg Production Error (exit $exitCode): $processOutput")
        } else {
            println("✅ Final Video Created Successfully: ${output.absolutePath}")
        }
    }
}
