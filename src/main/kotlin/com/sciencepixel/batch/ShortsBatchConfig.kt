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
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

@Configuration
class ShortsBatchConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val videoProcessor: VideoProcessor,
    private val mongoWriter: MongoWriter
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
            .reader(realRssReader())
            .processor(videoProcessor)
            .writer(mongoWriter)
            .build()
    }

    @Bean
    fun realRssReader(): RssItemReader {
        // Google News Science
        return RssItemReader("https://news.google.com/rss/search?q=science&hl=en-US&gl=US&ceid=US:en")
    }
}
