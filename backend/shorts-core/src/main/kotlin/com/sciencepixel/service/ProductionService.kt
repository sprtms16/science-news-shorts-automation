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
    private var useGpuCodec = true // GPU 코덱 사용 여부 (첫 실패 시 false로 전환)
    data class AssetsResult(
        val mood: String,
        val clipPaths: List<String>,
        val durations: List<Double>,
        val subtitles: List<String>,
        val silenceRanges: List<com.sciencepixel.domain.SilenceRange> = emptyList()
    )

    /**
     * FFmpeg 프로세스 실행 헬퍼 함수
     * - 타임아웃 설정 (기본 10분)
     * - 에러 로깅 개선
     * - 파일 검증
     */
    private fun executeFFmpeg(cmd: List<String>, outputFile: File, operationName: String, timeoutMinutes: Long = 10): Boolean {
        println("🎬 [FFmpeg] $operationName")
        println("   Command: ${cmd.joinToString(" ")}")

        try {
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()

            // 타임아웃과 함께 프로세스 대기
            val completed = process.waitFor(timeoutMinutes, java.util.concurrent.TimeUnit.MINUTES)

            if (!completed) {
                println("❌ [FFmpeg] $operationName TIMEOUT after $timeoutMinutes minutes")
                process.destroyForcibly()
                return false
            }

            val exitCode = process.exitValue()
            val processOutput = process.inputStream.bufferedReader().readText()

            if (exitCode != 0) {
                println("❌ [FFmpeg] $operationName FAILED (exit code: $exitCode)")
                println("   Output (last 50 lines):")
                processOutput.lines().takeLast(50).forEach { println("   $it") }
                return false
            }

            // 출력 파일 검증
            if (!outputFile.exists()) {
                println("❌ [FFmpeg] $operationName: Output file does not exist: ${outputFile.absolutePath}")
                return false
            }

            if (outputFile.length() == 0L) {
                println("❌ [FFmpeg] $operationName: Output file is 0 bytes: ${outputFile.absolutePath}")
                return false
            }

            println("✅ [FFmpeg] $operationName SUCCESS: ${outputFile.name} (${outputFile.length() / 1024} KB)")
            return true

        } catch (e: Exception) {
            println("❌ [FFmpeg] $operationName EXCEPTION: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

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
                    try {
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
                            println("🖼️ [Scene $i] Using report image")
                            val reportFile = java.io.File(reportImagePath!!)
                            if (!reportFile.exists()) {
                                println("❌ [Scene $i] Report image not found: $reportImagePath")
                                throw IllegalArgumentException("Report image file not found")
                            }
                            java.nio.file.Files.copy(
                                reportFile.toPath(),
                                videoFile.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING
                            )
                        } else {
                            println("🎥 [Scene $i] Downloading video for keyword: ${scene.keyword}")
                            if (!pexelsService.downloadVerifiedVideo(scene.keyword, "$title context: $cleanSentence", videoFile)) {
                                println("⚠️ [Scene $i] No video found for '${scene.keyword}'. Trying fallback...")

                                // 채널별 Fallback 키워드 설정
                                val fallbackKeyword = when (effectiveChannelId) {
                                    "science" -> "science technology"      // 사이언스 픽셀: 과학/기술
                                    "stocks" -> "business finance"          // 밸류 픽셀: 금융/비즈니스
                                    "horror" -> "dark mystery"              // 미스터리 픽셀: 공포/미스터리
                                    "history" -> "ancient civilization"     // 메모리 픽셀: 역사/문명
                                    else -> "technology innovation"
                                }

                                if (!pexelsService.downloadVerifiedVideo(fallbackKeyword, "fallback context for $effectiveChannelId", videoFile)) {
                                    println("❌ [Scene $i] Fallback video download also failed for channel: $effectiveChannelId")
                                    throw RuntimeException("Failed to download video for scene $i")
                                }
                            }
                        }

                        if (!videoFile.exists() || videoFile.length() == 0L) {
                            println("❌ [Scene $i] Video file is missing or empty after download")
                            throw RuntimeException("Video file invalid for scene $i")
                        }

                        // 오디오 생성 (1.15배속 적용을 위해 duration 조정)
                        println("🎙️ [Scene $i] Generating audio: $cleanSentence")
                        val rawDuration = try {
                            audioService.generateAudio(cleanSentence, audioFile)
                        } catch (e: Exception) {
                            println("⚠️ [Scene $i] Audio generation failed: ${e.message}. Using default duration 5.0s")
                            5.0
                        }
                        val effectiveDuration = rawDuration / 1.15

                        println("✂️ [Scene $i] Editing scene (duration: ${String.format("%.2f", effectiveDuration)}s)")
                        editSceneWithoutSubtitle(videoFile, audioFile, effectiveDuration, clipFile)

                        SceneResult(
                            index = i,
                            clipFile = clipFile,
                            duration = effectiveDuration,
                            subtitle = cleanSentence,
                            hasSilence = scene.sentence.contains("[BGM_SILENCE]")
                        )
                    } catch (e: Exception) {
                        println("❌ [Scene $i] Failed to process scene: ${e.message}")
                        e.printStackTrace()
                        throw e // 재throw하여 병렬 처리 실패 감지
                    }
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
        println("🎬 [FinalizeVideo] Starting finalization for: $title (videoId: $videoId)")

        val effectiveChannelId = targetChannelId ?: channelId
        val workspace = File("shared-data/workspace/$effectiveChannelId/$videoId")
        if (!workspace.exists()) workspace.mkdirs()

        if (clipPaths.isEmpty()) {
            println("❌ [FinalizeVideo] No clips provided for finalization")
            return ""
        }

        val clipFiles = clipPaths.map { File(it) }

        try {
            // Phase 2: SRT
            println("📝 [FinalizeVideo] Generating SRT file...")
            val srtFile = File(workspace, "subtitles.srt")
            generateSrtFile(subtitles, durations, srtFile)

            if (!srtFile.exists() || srtFile.length() == 0L) {
                println("❌ [FinalizeVideo] SRT file generation failed")
                return ""
            }
            println("✅ [FinalizeVideo] SRT file created: ${srtFile.length()} bytes")

            // Phase 3: Merge
            println("🔗 [FinalizeVideo] Merging ${clipFiles.size} clips...")
            val mergedFile = File(workspace, "merged_no_subs.mp4")
            mergeClipsWithoutSubtitles(clipFiles, mergedFile, workspace)
            println("✅ [FinalizeVideo] Clips merged: ${mergedFile.length() / 1024} KB")

            // Phase 4: Burn Subtitles & Mix BGM
            println("🔥 [FinalizeVideo] Burning subtitles and mixing BGM...")
            val sanitizedTitle = title.take(20).replace(Regex("[^a-zA-Z0-9가-힣]"), "_").lowercase()
            val outcomeDir = File("shared-data/videos/$effectiveChannelId").apply { mkdirs() }
            val finalOutput = File(outcomeDir, "shorts_${sanitizedTitle}_$videoId.mp4")

            burnSubtitlesAndMixBGM(mergedFile, srtFile, finalOutput, mood, workspace, silenceRanges)

            if (!finalOutput.exists()) {
                println("❌ [FinalizeVideo] Output file DOES NOT EXIST at ${finalOutput.absolutePath}")
                return ""
            }

            if (finalOutput.length() == 0L) {
                println("❌ [FinalizeVideo] Output file is 0 BYTES at ${finalOutput.absolutePath}")
                return ""
            }

            val fileSizeKB = finalOutput.length() / 1024
            val fileSizeMB = String.format("%.2f", fileSizeKB / 1024.0)
            println("✅ [FinalizeVideo] COMPLETE: ${finalOutput.name} (${fileSizeMB} MB)")
            logPublisher.info("shorts-controller", "Production Completed: $title", "Path: ${finalOutput.name}, Size: ${fileSizeMB}MB", traceId = videoId)

            return finalOutput.absolutePath

        } catch (e: Exception) {
            println("❌ [FinalizeVideo] EXCEPTION during finalization: ${e.message}")
            e.printStackTrace()
            return ""
        }
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
            val rawDuration = try {
                 audioService.generateAudio(scene.sentence, audioFile)
            } catch (e: Exception) {
                println("⚠️ Audio generation failed: ${e.message}")
                5.0
            }
            val effectiveDuration = rawDuration / 1.15

            // 3. Edit Scene (1.15x speed-up applied inside)
            editSceneWithoutSubtitle(videoFile, audioFile, effectiveDuration, clipFile)
            
            clipFiles.add(clipFile)
            durations.add(effectiveDuration)
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
        if (!video.exists()) {
            println("❌ [editSceneWithoutSubtitle] Video file does not exist: ${video.absolutePath}")
            throw IllegalArgumentException("Video file not found: ${video.name}")
        }

        val isImage = video.extension.lowercase() in listOf("jpg", "jpeg", "png")

        // GPU 코덱 사용 시도
        if (useGpuCodec) {
            val success = tryEditSceneWithCodec(video, audio, duration, output, isImage, "h264_nvenc", "p4")
            if (success) return

            // GPU 코덱 실패 시 fallback
            println("⚠️ GPU codec failed, switching to software encoding (libx264) for all future scenes")
            useGpuCodec = false
        }

        // Software 코덱으로 재시도 또는 첫 시도
        val success = tryEditSceneWithCodec(video, audio, duration, output, isImage, "libx264", "medium")
        if (!success) {
            throw RuntimeException("Failed to edit scene with both GPU and CPU codecs: ${video.name}")
        }
    }

    private fun tryEditSceneWithCodec(
        video: File,
        audio: File,
        duration: Double,
        output: File,
        isImage: Boolean,
        videoCodec: String,
        preset: String
    ): Boolean {
        val cmd = mutableListOf("ffmpeg", "-y")

        if (isImage) {
            cmd.addAll(listOf("-loop", "1", "-i", video.absolutePath))
        } else {
            cmd.addAll(listOf("-stream_loop", "-1", "-i", video.absolutePath))
        }

        if (audio.exists()) {
            cmd.addAll(listOf("-i", audio.absolutePath))
        } else {
            cmd.addAll(listOf("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100"))
        }

        cmd.addAll(listOf(
            "-t", "$duration",
            "-vf", vfScaleFilter,
            "-r", "60",
            "-pix_fmt", "yuv420p",
            "-map", "0:v", "-map", "1:a",
            "-af", "atempo=1.15",
            "-c:v", videoCodec,
            "-c:a", "aac",
            "-ar", "44100",
            "-ac", "2",
            "-shortest",
            "-preset", preset,
            output.absolutePath
        ))

        return executeFFmpeg(cmd, output, "Scene Edit (codec: $videoCodec)", 5)
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
        if (clips.isEmpty()) {
            throw IllegalArgumentException("Cannot merge: clip list is empty")
        }

        // 모든 클립 파일이 존재하는지 검증
        clips.forEachIndexed { index, clip ->
            if (!clip.exists()) {
                println("❌ Clip $index does not exist: ${clip.absolutePath}")
                throw IllegalArgumentException("Clip file missing: ${clip.name}")
            }
            if (clip.length() == 0L) {
                println("❌ Clip $index is 0 bytes: ${clip.absolutePath}")
                throw IllegalArgumentException("Clip file is empty: ${clip.name}")
            }
        }

        val listFile = File(workspace, "list.txt")
        listFile.bufferedWriter().use { out ->
            clips.forEach { out.write("file '${it.absolutePath}'\n") }
        }

        val cmd = listOf(
            "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,
            "-c", "copy",
            output.absolutePath
        )

        val success = executeFFmpeg(cmd, output, "Merge Clips", 10)
        if (!success) {
            throw RuntimeException("Failed to merge clips")
        }
    }

    // Phase 3b: Burn subtitles and Mix BGM into final video
    private fun burnSubtitlesAndMixBGM(inputVideo: File, srtFile: File, output: File, mood: String, workspace: File, silenceRanges: List<com.sciencepixel.domain.SilenceRange> = emptyList()) {
        if (!inputVideo.exists()) {
            throw IllegalArgumentException("Input video does not exist: ${inputVideo.absolutePath}")
        }
        if (!srtFile.exists()) {
            throw IllegalArgumentException("SRT file does not exist: ${srtFile.absolutePath}")
        }

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
            try {
                if (audioService.generateBgm(prompt, 15, aiBgmFile)) {
                    bgmFile = aiBgmFile
                }
            } catch (e: Exception) {
                println("⚠️ BGM generation failed: ${e.message}")
            }
        }

        // GPU 코덱 먼저 시도
        val videoCodec = if (useGpuCodec) "h264_nvenc" else "libx264"
        val preset = if (useGpuCodec) "p4" else "medium"

        val cmd = buildBurnSubtitlesCommand(
            inputVideo, bgmFile, srtPath, subtitleFilter,
            silenceRanges, videoCodec, preset, output
        )

        var success = executeFFmpeg(cmd, output, "Burn Subtitles & Mix BGM (codec: $videoCodec)", 15)

        // GPU 코덱 실패 시 software codec으로 재시도
        if (!success && useGpuCodec) {
            println("⚠️ GPU codec failed in final burn, retrying with software codec")
            useGpuCodec = false
            output.delete() // 실패한 파일 삭제

            val fallbackCmd = buildBurnSubtitlesCommand(
                inputVideo, bgmFile, srtPath, subtitleFilter,
                silenceRanges, "libx264", "medium", output
            )
            success = executeFFmpeg(fallbackCmd, output, "Burn Subtitles & Mix BGM (fallback: libx264)", 15)
        }

        if (!success) {
            throw RuntimeException("Failed to burn subtitles and mix BGM with all available codecs")
        }
    }

    private fun buildBurnSubtitlesCommand(
        inputVideo: File,
        bgmFile: File,
        srtPath: String,
        subtitleFilter: String,
        silenceRanges: List<com.sciencepixel.domain.SilenceRange>,
        videoCodec: String,
        preset: String,
        output: File
    ): List<String> {
        val cmd = mutableListOf("ffmpeg", "-y", "-i", inputVideo.absolutePath)

        if (bgmFile.exists()) {
            println("🎵 Mixing BGM: ${bgmFile.name}")
            cmd.addAll(listOf("-stream_loop", "-1", "-i", bgmFile.absolutePath))

            // Filter complex: mix voice and bgm
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
            println("⚠️ BGM file not found. Proceeding without BGM.")
            cmd.addAll(listOf("-vf", subtitleFilter, "-c:a", "copy"))
        }

        val codecSpecificArgs = if (videoCodec == "h264_nvenc") {
            listOf("-c:v", videoCodec, "-preset", preset, "-cq", "23")
        } else {
            listOf("-c:v", videoCodec, "-preset", preset, "-crf", "23")
        }

        cmd.addAll(codecSpecificArgs)
        cmd.addAll(listOf("-c:a", "aac", "-ar", "44100", "-ac", "2", "-movflags", "+faststart", output.absolutePath))

        return cmd
    }
}
