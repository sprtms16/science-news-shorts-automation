package com.sciencepixel.event

import java.time.Instant

/**
 * Kafka 이벤트 모델들
 * 비디오 생성 파이프라인의 이벤트 드리븐 아키텍처를 위한 이벤트 정의
 */

// 비디오 생성 완료 이벤트
data class VideoCreatedEvent(
    val channelId: String = "science", // 추가
    val videoId: String,
    val title: String,
    val summary: String,
    val description: String = "",
    val link: String,
    val filePath: String,
    val keywords: List<String> = emptyList(),
    val thumbnailPath: String = "",
    val timestamp: Long = Instant.now().toEpochMilli()
)

// YouTube 업로드 성공 이벤트
data class VideoUploadedEvent(
    val channelId: String = "science", // 추가
    val videoId: String,
    val youtubeUrl: String,
    val timestamp: Long = Instant.now().toEpochMilli()
)

// 업로드 실패 이벤트 (재시도용)
data class UploadFailedEvent(
    val channelId: String = "science", // 추가
    val videoId: String,
    val title: String,
    val filePath: String,
    val reason: String,
    val retryCount: Int,
    val keywords: List<String> = emptyList(),
    val thumbnailPath: String = "",
    val timestamp: Long = Instant.now().toEpochMilli()
)

// 비디오 재생성 요청 이벤트
data class RegenerationRequestedEvent(
    val channelId: String = "science", // 추가
    val videoId: String,
    val title: String,
    val summary: String,
    val link: String,
    val regenCount: Int,
    val timestamp: Long = Instant.now().toEpochMilli()
)

// === SAGA Pipeline Events ===

data class RssNewItemEvent(
    val channelId: String = "science", // 추가
    val url: String,
    val title: String,
    val summary: String? = null, // Added summary for manual requests
    val category: String? = null,
    val timestamp: Long = Instant.now().toEpochMilli()
)

data class ScriptCreatedEvent(
    val channelId: String = "science", // 추가
    val videoId: String,
    val title: String,
    val script: String,
    val summary: String,
    val sourceLink: String,
    val keywords: List<String> = emptyList(),
    val timestamp: Long = Instant.now().toEpochMilli()
)

data class AudioCreatedEvent(
    val channelId: String = "science", // 추가
    val videoId: String,
    val audioPath: String,
    val bgmPath: String? = null,
    val scriptEvent: ScriptCreatedEvent, // Carry over previous context
    val timestamp: Long = Instant.now().toEpochMilli()
)

data class VideoAssetsReadyEvent(
    val channelId: String = "science", // 추가
    val videoId: String,
    val title: String,
    val mood: String,
    val clipPaths: List<String>,
    val durations: List<Double>,
    val subtitles: List<String>,
    val keywords: List<String>,
    val scriptEvent: ScriptCreatedEvent?, 
    val timestamp: Long = Instant.now().toEpochMilli()
)
