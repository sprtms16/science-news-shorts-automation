import java.io.File

fun main() {
    println("🚀 Starting Science News Shorts Automation...")

    // 1. Initialize Components
    val serviceUrl = System.getenv("AI_SERVICE_URL") ?: "http://localhost:8000"
    val mediaService = PythonMediaService(serviceUrl)
    
    // Wait for AI Service
    println("⏳ Waiting for AI Service to be ready at $serviceUrl...")
    var retries = 0
    while (!mediaService.healthCheck() && retries < 30) {
        Thread.sleep(2000)
        retries++
        print(".")
    }
    println("\n")
    if (retries >= 30) {
        println("❌ AI Service not reachable. Please check Docker containers.")
        return
    }
    println("✅ AI Service Connected!")

    val newsFetcher = NewsFetcher()
    val scriptWriter = ScriptWriter()
    val videoEditor = VideoEditor()
    val uploader = YouTubeUploader()

    val sharedDir = File("shared-data")
    if (!sharedDir.exists()) sharedDir.mkdirs()

    // 2. Fetch News
    val newsList = newsFetcher.fetchLatestNews()
    if (newsList.isEmpty()) {
        println("No news found.")
        return
    }

    // Process only the first news item for this run
    val news = newsList.first()
    println("📰 Processing News: ${news.title}")

    // 3. Create Script
    val script = scriptWriter.createScript(news)
    println("📝 Script Hook: ${script.hook}")

    // 4. Generate Media (Audio & Video)
    try {
        // Audio
        val audioFile = File(sharedDir, "output_audio.mp3")
        mediaService.generateAudio(script.hook + " " + script.body, audioFile)
        println("🔊 Audio Generated: ${audioFile.absolutePath}")

        // Video (Simulated Image Input)
        // In real app, we download an image based on keywords. 
        // Here we expect a 'input_image.jpg' in shared-data for testing as per PDF
        val inputImage = File(sharedDir, "input_image.jpg")
        if (!inputImage.exists()) {
            println("⚠ 'input_image.jpg' not found in shared-data. Please add it for video generation.")
            // Create a dummy image or skip? 
            // We'll skip video generation call to avoid error if file not found, 
            // but normally we'd download one.
        } else {
            val videoFile = File(sharedDir, "output_video.mp4")
            mediaService.generateVideo(inputImage, videoFile)
            println("🎥 Video Generated: ${videoFile.absolutePath}")
            
            // 5. Edit Video
            val finalVideo = File(sharedDir, "final_shorts.mp4")
            videoEditor.mergeVideoAndAudio(videoFile, audioFile, finalVideo)
            
            // 6. Upload
            uploader.uploadVideo(finalVideo, news.title + " #Shorts", script.body)
        }

    } catch (e: Exception) {
        println("❌ Error during processing: ${e.message}")
        e.printStackTrace()
    }

    println("✅ Workflow Complete.")
}
