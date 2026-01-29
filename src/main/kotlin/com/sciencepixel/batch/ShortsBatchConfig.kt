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
        val feeds = listOf(
            // 1. Global Tech News
            "https://techcrunch.com/feed/",
            "https://www.wired.com/feed/rss",
            "https://www.theverge.com/rss/index.xml",
            "http://feeds.arstechnica.com/arstechnica/index",
            "https://www.engadget.com/rss.xml",
            
            // 2. Science & Deep Tech
            "https://www.sciencedaily.com/rss/all.xml",
            "https://www.technologyreview.com/feed/",
            "http://www.nature.com/nature.rss",
            "http://rss.sciam.com/ScientificAmerican-Global",
            
            // 3. Korean Tech News
            "https://news.hada.io/rss",
            "https://zdnet.co.kr/rss/",
            "https://www.itworld.co.kr/rss/feed/index.php",
            "https://rss.etnews.com/Section901.xml",
            
            // 4. Tech Blogs
            "https://d2.naver.com/d2.atom",
            "https://tech.kakao.com/rss/",
            "https://techblog.woowahan.com/feed/",
            "https://engineering.linecorp.com/ko/feed/",
            "http://korea.googleblog.com/atom.xml",
            "https://news.ycombinator.com/rss"
        )
        return RssItemReader(feeds, remainingSlots?.toInt() ?: 10)
    }
}
