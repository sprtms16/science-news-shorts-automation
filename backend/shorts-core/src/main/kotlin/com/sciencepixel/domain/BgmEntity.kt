package com.sciencepixel.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

@Document(collection = "bgm_library")
data class BgmEntity(
    @Id
    val id: String? = null,
    val filename: String,
    val filePath: String, // Absolute path or relative to shared
    val status: BgmStatus = BgmStatus.PENDING,
    val mood: String? = null, // "futuristic", "epic", etc.
    val channelId: String = "common", // "common" or specific
    val errorMessage: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

enum class BgmStatus {
    PENDING,    // Uploaded, waiting for AI
    PROCESSING, // AI Analyzing
    COMPLETED,  // Classified and Moved
    FAILED      // Error
}
