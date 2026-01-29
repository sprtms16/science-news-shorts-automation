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
    private val rssSourceRepository: com.sciencepixel.repository.RssSourceRepository
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
            .chunk<NewsItem, VideoHistory>(1, transactionManager)
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
        // Fetch active feeds from DB
        val activeSources = rssSourceRepository.findByIsActiveTrue()
        val feeds = activeSources.map { it.url }
        
        if (feeds.isEmpty()) {
            println("⚠️ No active RSS sources found in DB! Using fallback...")
             return RssItemReader(listOf("https://www.wired.com/feed/rss"), remainingSlots?.toInt() ?: 10)
        }
        
        println("📡 Loaded ${feeds.size} active RSS feeds from DB.")
        return RssItemReader(feeds, remainingSlots?.toInt() ?: 10)
    }
}
