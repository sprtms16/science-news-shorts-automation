package com.sciencepixel.event

data class VideoCreationRequest(
    val videoId: String,
    val channelId: String,
    val title: String,
    val summary: String,
    val link: String,
    val regenCount: Int = 0
)

data class VideoCreationCompleteEvent(
    val videoId: String,
    val channelId: String,
    val filePath: String,
    val thumbnailPath: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val sources: List<String>,
    val keywords: List<String>
)
