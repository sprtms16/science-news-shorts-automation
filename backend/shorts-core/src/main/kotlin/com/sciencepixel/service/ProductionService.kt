package com.sciencepixel.service

import com.sciencepixel.config.ChannelBehavior
import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.ProductionResult
import com.sciencepixel.domain.Scene
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
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
    companion object {
        private val logger = LoggerFactory.getLogger(ProductionService::class.java)
    }

    private val isLongForm = channelBehavior.isLongForm
    private val videoWidth = if (isLongForm) 1920 else 1080
    private val videoHeight = if (isLongForm) 1080 else 1920
    // Blur background + centered original: preserves content instead of cropping 75%
    private val vfScaleFilter = "split[bg][fg];[bg]scale=$videoWidth:$videoHeight:force_original_aspect_ratio=increase,crop=$videoWidth:$videoHeight,boxblur=20[bgblur];[fg]scale=$videoWidth:$videoHeight:force_original_aspect_ratio=decrease[fgscaled];[bgblur][fgscaled]overlay=(W-w)/2:(H-h)/2"
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
        logger.info("[SAGA] Phase 1: Processing scenes for {} ({} scenes) - parallel", videoId, totalScenes)
        
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
                    logger.info("[{}] Progress: {}% - {}", title, sceneProgress, step)
                    onProgress?.invoke(sceneProgress, step)
                    
                    val videoFile = File(workspace, "raw_$i.mp4")
                    val audioFile = File(workspace, "audio_$i.mp3")
                    val clipFile = File(workspace, "clip_$i.mp4")

                    val cleanSentence = scene.sentence.replace("[BGM_SILENCE]", "").trim()
                    val useReportImage = reportImagePath != null && i < 5
                    
                    // 비디오 다운로드
                    if (useReportImage) {
                        logger.info("Using report image for scene {}", i)
                        java.nio.file.Files.copy(
                            java.io.File(reportImagePath!!).toPath(),
                            videoFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    } else {
                        if (!pexelsService.downloadVerifiedVideo(scene.keyword, "$title context: $cleanSentence", videoFile)) {
                            logger.warn("No video found for '{}'. Trying fallback...", scene.keyword)
                            pexelsService.downloadVerifiedVideo("science technology", "fallback context", videoFile)
                        }
                    }
                    
                    // 오디오 생성 (FFmpeg atempo=1.15가 속도 처리를 담당)
                    val rawDuration = try {
                        audioService.generateAudio(cleanSentence, audioFile)
                    } catch (e: Exception) {
                        5.0
                    }
                    val effectiveDuration = rawDuration  // FFmpeg atempo가 속도 처리

                    editSceneWithoutSubtitle(videoFile, audioFile, effectiveDuration, clipFile)
                    
                    SceneResult(
                        index = i,
                        clipFile = clipFile,
                        duration = effectiveDuration,
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
                logger.info("BGM Silence from {} to {} seconds (Scene {})", silenceStart, silenceEnd, result.index)
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
        
        if (!finalOutput.exists() || finalOutput.length() == 0L) {
            logger.error("Finalization failed: Output file missing or 0 bytes")
            return ""
        }

        logPublisher.info("shorts-controller", "Production Completed: $title", "Path: ${finalOutput.name}", traceId = videoId)
        return finalOutput.absolutePath
    }





    // Entry point for Batch Job (Legacy - Deprecated)
    fun produceVideo(news: NewsItem, videoId: String): ProductionResult {
        logger.info("Producing video for: {} (ID: {})", news.title, videoId)
        
        // 1. Script Generation (Gemini)
        val response = geminiService.writeScript(news.title, news.summary)
        if (response.scenes.isEmpty()) {
            logger.warn("No script generated for {}", news.title)
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
            logger.error("Thumbnail download failed: {}", e.message)
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
        logger.info("Phase 1: Processing scenes (video + audio)")
        scenes.forEachIndexed { i, scene ->
            logger.info("Scene {}: {}", i, scene.sentence)
            
            val videoFile = File(workspace, "raw_$i.mp4")
            val audioFile = File(workspace, "audio_$i.mp3")
            val clipFile = File(workspace, "clip_$i.mp4")

            // 1. Video (Pexels - License: Free to use, no attribution required)
            if (!pexelsService.downloadVerifiedVideo(scene.keyword, "$title context: ${scene.sentence}", videoFile)) {
                logger.warn("No video found for '{}'. Trying fallback...", scene.keyword)
                if (!pexelsService.downloadVerifiedVideo("science technology", "fallback context", videoFile)) {
                    logger.warn("Skipping scene {} even after fallback", i)
                    return@forEachIndexed
                }
            }

            // 2. Audio (Edge-TTS)
            val rawDuration = try {
                 audioService.generateAudio(scene.sentence, audioFile)
            } catch (e: Exception) {
                logger.warn("Audio generation failed: {}", e.message)
                5.0
            }
            val effectiveDuration = rawDuration  // FFmpeg atempo가 속도 처리

            // 3. Edit Scene (1.15x speed-up applied inside)
            editSceneWithoutSubtitle(videoFile, audioFile, effectiveDuration, clipFile)
            
            clipFiles.add(clipFile)
            durations.add(effectiveDuration)
            subtitles.add(scene.sentence)
        }

        if (clipFiles.isEmpty()) return ""

        // ========== PHASE 2: Generate SRT Subtitle File ==========
        logger.info("Phase 2: Generating SRT subtitle file")
        val srtFile = File(workspace, "subtitles.srt")
        generateSrtFile(subtitles, durations, srtFile)
        logger.info("SRT file created: {}", srtFile.absolutePath)

        // ========== PHASE 3: Merge + Burn Subtitles ==========
        logger.info("Phase 3: Merging clips and burning subtitles")
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
            logger.info("Cleaning up workspace for: {}", title)
            workspace.deleteRecursively()
        }
        
        logPublisher.info("shorts-controller", "Batch Production Completed: $title", "Path: ${finalOutput.name}", traceId = videoId)
        return finalOutput.absolutePath
    }

    // Phase 1: Edit scene WITHOUT subtitles
    private fun editSceneWithoutSubtitle(video: File, audio: File, duration: Double, output: File) {
        val isImage = video.extension.lowercase() in listOf("jpg", "jpeg", "png")
        
        val cmd = mutableListOf("ffmpeg", "-y")
        
        if (isImage) {
            cmd.addAll(listOf("-loop", "1", "-i", video.absolutePath))
        } else {
            cmd.addAll(listOf("-stream_loop", "-1", "-i", video.absolutePath))
        }
        
        if (audio.exists()) {
            cmd.addAll(listOf("-i", audio.absolutePath))
        } else {
            // Generate silence if audio is missing
            cmd.addAll(listOf("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100"))
        }
        
        // Use nvenc for video, aac for audio with fixed sample rate and channels to prevent concat audio loss
        cmd.addAll(listOf(
            "-t", "$duration",
            "-filter_complex", "[0:v]${vfScaleFilter}[vout];[1:a]atempo=1.15[aout]",
            "-map", "[vout]", "-map", "[aout]",
            "-r", "60",
            "-pix_fmt", "yuv420p",
            "-c:v", "h264_nvenc",
            "-c:a", "aac",
            "-ar", "44100",
            "-ac", "2",
            "-shortest",
            "-preset", "p4",
            output.absolutePath
        ))
        
        logger.debug("FFmpeg Scene Edit: {}", cmd.joinToString(" "))
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val processOutput = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error("FFmpeg Error (exit {}): {}", exitCode, processOutput)
        } else {
            if (!output.exists() || output.length() == 0L) {
                logger.error("FFmpeg Scene Edit failed: Output file missing or 0 bytes ({})", output.absolutePath)
            }
        }
    }

    // Calculate visual width: CJK characters count as 2, others as 1
    private fun visualWidth(text: String): Int = text.sumOf { ch ->
        if (ch.code in 0xAC00..0xD7AF || ch.code in 0x3130..0x318F ||
            ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F) 2 else 1
    }

    // Helper function to wrap text into chunks of max 3 lines
    private fun wrapTextToChunks(text: String, maxCharsPerLine: Int = if (isLongForm) 40 else 22): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            if (currentLine.isEmpty()) {
                currentLine.append(word)
            } else if (visualWidth(currentLine.toString()) + 1 + visualWidth(word) <= maxCharsPerLine) {
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
        
        logger.debug("FFmpeg Merge: {}", cmd.joinToString(" "))
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val processOutput = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error("FFmpeg Merge Error (exit {}): {}", exitCode, processOutput)
        } else {
            if (output.exists() && output.length() > 0) {
                logger.info("FFmpeg Merge Complete: {} ({} bytes)", output.absolutePath, output.length())
            } else {
                logger.error("FFmpeg Merge failed: Output file missing or 0 bytes ({})", output.absolutePath)
            }
        }
    }

    // Phase 3b: Burn subtitles and Mix BGM into final video
    private fun burnSubtitlesAndMixBGM(inputVideo: File, srtFile: File, output: File, mood: String, workspace: File, silenceRanges: List<com.sciencepixel.domain.SilenceRange> = emptyList()) {
        // FFmpeg subtitle filter path escaping:
        // 1. Convert backslashes to forward slashes (Windows path fix)
        // 2. Escape ALL colons with \: (FFmpeg filter syntax requirement)
        // 3. Escape single quotes for FFmpeg filter syntax
        val srtPath = srtFile.absolutePath
            .replace("\\", "/")
            .replace(":", "\\:")
            .replace("'", "'\\''")
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
            logger.warn("Local BGM not found for '{}'. Trying AI Generation...", mood)
            val aiBgmFile = File(workspace, "ai_bgm_${java.lang.System.currentTimeMillis()}.wav")
            
            // Generate for 15 seconds (will loop)
            val prompt = "$mood style cinematic background music, high quality"
            if (audioService.generateBgm(prompt, 15, aiBgmFile)) {
                bgmFile = aiBgmFile
            }
        }
        
        val cmd = mutableListOf("ffmpeg", "-y", "-i", inputVideo.absolutePath)
        
        if (bgmFile.exists()) {
            logger.info("Mixing BGM: {} (Mood: {})", bgmFile.name, mood)
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
            logger.warn("BGM file not found and Generation failed. Skipping BGM.")
            cmd.addAll(listOf("-vf", subtitleFilter, "-c:a", "copy"))
        }
        
        cmd.addAll(listOf("-c:v", "h264_nvenc", "-preset", "p4", "-cq", "23", "-c:a", "aac", "-ar", "44100", "-ac", "2", "-movflags", "+faststart", output.absolutePath))
        
        logger.debug("FFmpeg Production (Phase 3): {}", cmd.joinToString(" "))
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val processOutput = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error("FFmpeg Production Error (exit {}): {}", exitCode, processOutput)
        } else {
            if (output.exists() && output.length() > 0) {
                logger.info("Final Video Created Successfully: {} ({} bytes)", output.absolutePath, output.length())
            } else {
                logger.error("FFmpeg reported success but output file is missing or 0 bytes: {}", output.absolutePath)
            }
        }
    }
}
