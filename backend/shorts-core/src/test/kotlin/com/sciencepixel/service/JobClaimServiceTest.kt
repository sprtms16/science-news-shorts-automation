package com.sciencepixel.service

import com.sciencepixel.domain.VideoHistory
import com.sciencepixel.domain.VideoStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class JobClaimServiceTest {

    @Mock
    private lateinit var mongoTemplate: MongoTemplate

    private lateinit var jobClaimService: JobClaimService

    @BeforeEach
    fun setUp() {
        jobClaimService = JobClaimService(mongoTemplate)
    }

    @Test
    fun `claimJob returns true when video is in expected status`() {
        // Given
        val videoId = "test-video-id"
        val existingVideo = VideoHistory(
            id = videoId,
            channelId = "science",
            title = "Test Video",
            status = VideoStatus.QUEUED,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
        
        whenever(mongoTemplate.findAndModify(
            any<Query>(),
            any<Update>(),
            any<FindAndModifyOptions>(),
            any<Class<VideoHistory>>()
        )).thenReturn(existingVideo)

        // When
        val result = jobClaimService.claimJob(videoId, VideoStatus.QUEUED, VideoStatus.CREATING)

        // Then
        assertTrue(result)
    }

    @Test
    fun `claimJob returns false when video is not in expected status`() {
        // Given
        val videoId = "test-video-id"
        
        whenever(mongoTemplate.findAndModify(
            any<Query>(),
            any<Update>(),
            any<FindAndModifyOptions>(),
            any<Class<VideoHistory>>()
        )).thenReturn(null)

        // When
        val result = jobClaimService.claimJob(videoId, VideoStatus.QUEUED, VideoStatus.CREATING)

        // Then
        assertFalse(result)
    }

    @Test
    fun `claimJobFromAny returns true when video is in one of allowed statuses`() {
        // Given
        val videoId = "test-video-id"
        val existingVideo = VideoHistory(
            id = videoId,
            channelId = "science",
            title = "Test Video",
            status = VideoStatus.COMPLETED,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
        
        whenever(mongoTemplate.findAndModify(
            any<Query>(),
            any<Update>(),
            any<FindAndModifyOptions>(),
            any<Class<VideoHistory>>()
        )).thenReturn(existingVideo)

        // When
        val allowedStatuses = listOf(VideoStatus.COMPLETED, VideoStatus.UPLOAD_FAILED)
        val result = jobClaimService.claimJobFromAny(videoId, allowedStatuses, VideoStatus.UPLOADING)

        // Then
        assertTrue(result)
    }

    @Test
    fun `claimJobFromAny returns false when video is already claimed`() {
        // Given
        val videoId = "test-video-id"
        
        whenever(mongoTemplate.findAndModify(
            any<Query>(),
            any<Update>(),
            any<FindAndModifyOptions>(),
            any<Class<VideoHistory>>()
        )).thenReturn(null)

        // When
        val allowedStatuses = listOf(VideoStatus.COMPLETED, VideoStatus.UPLOAD_FAILED)
        val result = jobClaimService.claimJobFromAny(videoId, allowedStatuses, VideoStatus.UPLOADING)

        // Then
        assertFalse(result)
    }
}
