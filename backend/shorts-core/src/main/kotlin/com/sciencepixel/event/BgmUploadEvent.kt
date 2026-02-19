package com.sciencepixel.event

data class BgmUploadEvent(
    val bgmId: String,
    val filePath: String,
    val filename: String,
    val channelId: String
)
