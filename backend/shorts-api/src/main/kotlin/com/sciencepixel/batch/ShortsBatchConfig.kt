package com.sciencepixel.batch

import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.VideoHistory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.item.ItemReader
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

@Configuration
class ShortsBatchConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val videoProcessor: VideoProcessor,
    private val mongoWriter: MongoWriter,
    private val rssSourceRepository: com.sciencepixel.repository.RssSourceRepository,
    private val contentProviderService: com.sciencepixel.service.ContentProviderService,
    private val channelBehavior: com.sciencepixel.config.ChannelBehavior,
    @Value("\${SHORTS_CHANNEL_ID:science}") private val channelId: String
) {

    @Bean
    fun shortsJob(): Job {
        return JobBuilder("shortsJob", jobRepository)
            .start(shortsStep())
            .listener(object : JobExecutionListener {
                override fun afterJob(jobExecution: JobExecution) {
                    println("🧹 Batch Job Finished. Status: ${jobExecution.status}")
                }
            })
            .build()
    }

    @Bean
    fun shortsStep(): Step {
        return StepBuilder("step1", jobRepository)
            .chunk<List<NewsItem>, VideoHistory>(1, transactionManager)
            .reader(realRssReader(null)) // null here, injected via StepScope
            .processor(videoProcessor)
            .writer(mongoWriter)
            .build()
    }

    @Bean
    @StepScope
    fun realRssReader(
        @Value("#{jobParameters['remainingSlots']}") remainingSlots: Long?
    ): RssItemReader {
        // Fetch active feeds from DB for this channel ONLY
        val activeSources = rssSourceRepository.findByChannelIdAndIsActive(channelId, true)
        
        if (activeSources.isEmpty()) {
            println("⚠️ No active sources found for channel '$channelId' in DB! (Batch will be empty)")
             // Return empty reader safely
             return RssItemReader(emptyList(), contentProviderService, 0, channelBehavior)
        }
        
        println("📡 Loaded ${activeSources.size} active sources from DB for Batch.")
        return RssItemReader(activeSources, contentProviderService, remainingSlots?.toInt() ?: 10, channelBehavior)
    }
}
