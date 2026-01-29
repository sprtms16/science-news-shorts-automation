package com.sciencepixel.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

@Document(collection = "rss_sources")
data class RssSource(
    @Id
    val id: String? = null,
    val url: String,
    val title: String, // e.g., "Wired Science"
    val category: String = "General", // e.g., "Tech", "Space"
    val isActive: Boolean = true,
    val createdAt: String = LocalDateTime.now().toString()
)
