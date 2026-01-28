package com.sciencepixel.service

import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.ProductionResult
import org.springframework.stereotype.Service
import java.io.File

@Service
class ProductionService(
    private val pexelsService: PexelsService,
    private val audioService: AudioService,
    private val geminiService: GeminiService
) {
    
    // Entry point for Batch Job
    fun produceVideo(news: NewsItem): ProductionResult {
        println("🎬 Producing video for: ${news.title}")
        
        // 1. Script Generation (Gemini)
        val response = geminiService.writeScript(news.title, news.summary)
        if (response.scenes.isEmpty()) {
            println("⚠️ No script generated for ${news.title}")
            return ProductionResult("", emptyList())
        }
        
        val keywords = response.scenes.map { it.keyword }.distinct()
        val filePath = produceVideoFromScenes(news.title, response.scenes, response.mood)
        
        return ProductionResult(
            filePath = filePath,
            keywords = keywords,
            title = response.title,
            description = response.description,
            tags = response.tags,
            sources = response.sources
        )
    }

    // Core Logic - 3 Phase Pipeline
    private fun produceVideoFromScenes(title: String, scenes: List<Scene>, mood: String): String {
        val workspace = File("shared-data/workspace_${System.currentTimeMillis()}").apply { mkdirs() }
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
                println("⚠️ Skipping scene $i due to no video found")
                return@forEachIndexed
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
        val finalOutput = File(workspace.parentFile, "shorts_${sanitizedTitle}_${System.currentTimeMillis()}.mp4")
        burnSubtitlesAndMixBGM(mergedFile, srtFile, finalOutput, mood, workspace)
        
        println("✅ Final video with synced subtitles: ${finalOutput.absolutePath}")
        return finalOutput.absolutePath
    }

    // Phase 1: Edit scene WITHOUT subtitles
    private fun editSceneWithoutSubtitle(video: File, audio: File, duration: Double, output: File) {
        val vfFilter = "scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920"
        
        val cmd = mutableListOf(
            "ffmpeg", "-y",
            "-stream_loop", "-1", "-i", video.absolutePath
        )
        
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
        
        cmd.addAll(listOf("-t", "$duration", "-vf", vfFilter, "-r", "60"))
        
        // Map video (0:v) and audio (1:a) - audio is either file or silence
        cmd.addAll(listOf("-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-c:a", "aac"))
        
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
    private fun wrapTextToChunks(text: String, maxCharsPerLine: Int = 22): List<String> {
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
    private fun burnSubtitlesAndMixBGM(inputVideo: File, srtFile: File, output: File, mood: String, workspace: File) {
        val srtPath = srtFile.absolutePath.replace("\\", "/").replace(":", "\\:")
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
            val aiBgmFile = File(workspace.parentFile, "ai_bgm_${System.currentTimeMillis()}.wav")
            
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
            
            // Filter complex: mix voice and bgm (volume 0.30)
            // [0:v] is video from input 0
            // [0:a] is audio from input 0 (TTS)
            // [1:a] is audio from input 1 (BGM)
            // mix voice(1.0) and bgm(0.30) -> duration=first (matches TTS length)
            cmd.addAll(listOf(
                "-filter_complex", "[1:a]volume=0.20[bgm];[0:a][bgm]amix=inputs=2:duration=first[aout];[0:v]$subtitleFilter[vout]",
                "-map", "[vout]", "-map", "[aout]"
            ))
        } else {
            println("⚠️ BGM file not found and Generation failed. Skipping BGM.")
            cmd.addAll(listOf("-vf", subtitleFilter, "-c:a", "copy"))
        }
        
        cmd.addAll(listOf("-c:v", "libx264", "-preset", "fast", "-crf", "23", "-c:a", "aac", "-movflags", "+faststart", output.absolutePath))
        
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
