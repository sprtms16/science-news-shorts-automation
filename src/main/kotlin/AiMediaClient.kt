import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

interface MediaService {
    fun healthCheck(): Boolean
    fun generateAudio(text: String, outputFile: File): File
    fun generateVideo(imageFile: File, outputFile: File): File
}

class PythonMediaService(private val serviceUrl: String) : MediaService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.MINUTES) // SVD generation takes time
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    override fun healthCheck(): Boolean {
        return try {
            val request = Request.Builder().url(serviceUrl).build()
            client.newCall(request).execute().use { response -> 
                response.isSuccessful 
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun generateAudio(text: String, outputFile: File): File {
        val json = JSONObject().put("text", text).put("voice", "ko-KR-SunHiNeural").toString()
        val request = Request.Builder()
            .url("$serviceUrl/generate-audio")
            .post(RequestBody.create("application/json".toMediaType(), json))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("TTS Failed: ${response.code} ${response.body?.string()}")
            
            val responseJson = JSONObject(response.body!!.string())
            val filename = responseJson.getString("filename")
            // In a real scenario with shared volume, the file is already there.
            // We just need to make sure we point to the right path in shared volume.
            // However, the response gives us the filename.
            
            // For this implementation, we assume the shared-data directory is mounted
            // and the Python service writes directly to it.
            // We verify the file exists.
            
            // Note: The Python service saves to /app/output which is mapped to ./shared-data
            // So we should look for the file in the shared directory passed to this service?
            // Actually, let's assume the caller manages the path or we assume a convention.
            
            // IMPORTANT: The Python service returns a filename. 
            // We need to know where that file is located relative to THIS Kotlin process.
            // Let's assume the `outputFile` passed to this function is where we expect it to be,
            // OR we should trust the filename returned by Python service and look for it in the shared folder.
            
            // Re-reading the logic: The Python service writes to a volume.
            // We will copy/rename the file to `outputFile` or just return the path to the file in shared volume.
            // To match the interface `generateAudio(text, outputFile)`, let's try to copy/move it 
            // or just ensure `outputFile` path is used if possible.
            
            // But the Python service generates a UUID filename.
            // So we should find that file in the shared folder.
            val sharedDir = outputFile.parentFile
            val generatedFile = File(sharedDir, filename)
            
            if (generatedFile.exists()) {
                generatedFile.renameTo(outputFile)
            } else {
                 // Fallback or wait? It should be there.
                 throw RuntimeException("Generated audio file not found at ${generatedFile.absolutePath}")
            }
        }
        return outputFile
    }

    override fun generateVideo(imageFile: File, outputFile: File): File {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", imageFile.name, imageFile.asRequestBody("image/jpeg".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("$serviceUrl/generate-video")
            .post(requestBody)
            .build()

        println("Requesting AI Video Generation (This may take time)...")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("SVD Video Failed: ${response.code} ${response.body?.string()}")
            
            val responseJson = JSONObject(response.body!!.string())
            val filename = responseJson.getString("filename")
            
            val sharedDir = outputFile.parentFile
            val generatedFile = File(sharedDir, filename)
             
            if (generatedFile.exists()) {
                generatedFile.renameTo(outputFile)
            } else {
                 throw RuntimeException("Generated video file not found at ${generatedFile.absolutePath}")
            }
        }
        return outputFile
    }
}
