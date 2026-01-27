package com.sciencepixel.service

import com.sciencepixel.domain.NewsItem
import org.springframework.stereotype.Service
import java.io.File

@Service
class ProductionService(
    private val pexelsService: PexelsService,
    private val audioService: AudioService,
    private val geminiService: GeminiService
) {
    
    // Entry point for Batch Job
    fun produceVideo(news: NewsItem): String {
        println("🎬 Producing video for: ${news.title}")
        
        // 1. Script Generation (Gemini)
        val scenes = geminiService.writeScript(news.title, news.summary)
        if (scenes.isEmpty()) {
            println("⚠️ No script generated for ${news.title}")
            return ""
        }
        
        return produceVideoFromScenes(news.title, scenes)
    }

    // Core Logic - 3 Phase Pipeline
    private fun produceVideoFromScenes(title: String, scenes: List<Scene>): String {
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

            // 1. Video (Pexels)
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
        
        val finalOutput = File(workspace.parentFile, "shorts_${System.currentTimeMillis()}.mp4")
        burnSubtitles(mergedFile, srtFile, finalOutput)
        
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
        }
        
        cmd.addAll(listOf("-t", "$duration", "-vf", vfFilter, "-r", "60"))
        
        if (audio.exists()) {
            cmd.addAll(listOf("-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-c:a", "aac"))
        } else {
            cmd.addAll(listOf("-c:v", "libx264", "-an"))
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

    // Phase 3b: Burn subtitles into final video
    private fun burnSubtitles(inputVideo: File, srtFile: File, output: File) {
        // Use FFmpeg subtitles filter with styling
        // Note: SRT path needs to be escaped for Windows/special characters
        val srtPath = srtFile.absolutePath.replace("\\", "/").replace(":", "\\:")
        
        val subtitleFilter = "subtitles='$srtPath':force_style='FontName=NanumGothic,FontSize=10,PrimaryColour=&HFFFFFF,OutlineColour=&H000000,Outline=0.8,Shadow=0.5,Alignment=2,MarginV=50'"
        
        val cmd = listOf(
            "ffmpeg", "-y",
            "-i", inputVideo.absolutePath,
            "-vf", subtitleFilter,
            "-c:v", "libx264", "-preset", "fast", "-crf", "23",
            "-c:a", "copy",
            "-movflags", "+faststart",
            output.absolutePath
        )
        
        println("Executing FFmpeg Burn Subtitles: ${cmd.joinToString(" ")}")
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val processOutput = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            println("FFmpeg Subtitle Burn Error (exit $exitCode): $processOutput")
        } else {
            println("✅ Subtitles burned successfully: ${output.absolutePath}")
        }
    }
}
