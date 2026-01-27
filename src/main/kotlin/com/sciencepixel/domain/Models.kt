package com.sciencepixel.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.LocalDateTime

@Document(collection = "video_history")
data class VideoHistory(
    @Id
    val id: String? = null,
    val title: String,
    val summary: String,
    val link: String,
    val filePath: String = "",
    val youtubeUrl: String = "",
    val status: String = "PENDING_PROCESSING", // PENDING_PROCESSING, COMPLETED, UPLOADED, FAILED
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

data class NewsItem(
    val title: String,
    val summary: String,
    val link: String = ""
)

// VideoHistoryRepository moved to repository package
