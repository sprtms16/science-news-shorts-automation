package com.sciencepixel.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import java.time.LocalDateTime

@Document(collection = "rss_sources")
@CompoundIndexes(
    CompoundIndex(name = "channel_url_idx", def = "{'channelId': 1, 'url': 1}", unique = true)
)
data class RssSource(
    @Id
    val id: String? = null,
    val channelId: String = "science", // 추가: 채널 식별자
    val url: String,
    val title: String, // e.g., "Wired Science"
    val category: String = "General", // e.g., "Tech", "Space"
    val isActive: Boolean = true,
    val createdAt: String = LocalDateTime.now().toString()
)
