package com.sciencepixel.batch

import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import com.sciencepixel.domain.NewsItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.springframework.batch.item.ItemReader
import java.io.ByteArrayInputStream
import java.util.LinkedList
import java.util.concurrent.TimeUnit

open class RssItemReader(
    private val sources: List<com.sciencepixel.domain.RssSource>,
    private val contentProviderService: com.sciencepixel.service.ContentProviderService,
    private val maxItems: Int = 10,
    private val channelBehavior: com.sciencepixel.config.ChannelBehavior
) : ItemReader<NewsItem> {
    private val items = LinkedList<NewsItem>()
    private var initialized = false

    override fun read(): NewsItem? {
        if (!initialized) {
            fetchFeeds()
            initialized = true
        }
        return items.poll()
    }

    private fun fetchFeeds() {
        val allItems = mutableListOf<NewsItem>()
        val seenLinks = mutableSetOf<String>()

        sources.forEach { source ->
            try {
                processSource(source, allItems, seenLinks)
            } catch (e: Exception) {
                println("❌ Global Source Error (${source.title}): ${e.message}")
            }
        }

        // [Stocks Aggregation Logic]
        if (channelBehavior.shouldAggregateNews && allItems.isNotEmpty()) {
            println("📈 Aggregating ${allItems.size} stock news items into a single Monthly Report...")
            
            val todayDate = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_DATE)
            val topItems = allItems.take(10) // Take top 10
            
            val combinedSummary = StringBuilder()
            topItems.forEachIndexed { index, item ->
                combinedSummary.append("${index + 1}. [${item.sourceName}] ${item.title}\n${item.summary}\n\n")
            }
            
            val aggregateItem = NewsItem(
                title = "Global Market Summary ($todayDate)",
                summary = combinedSummary.toString(),
                link = topItems.first().link, // Use first link as primary
                sourceName = "Market Aggregator"
            )
            
            items.clear()
            items.add(aggregateItem)
            println("✅ Created 1 Aggregate Stock Video Item.")
            return
        }

        allItems.shuffle()
        items.addAll(allItems.take(maxItems))
        println("✅ Batch Feed Fetched: ${items.size} unique items (Limit: $maxItems) from ${sources.size} sources.")
    }

    private fun processSource(source: com.sciencepixel.domain.RssSource, allItems: MutableList<NewsItem>, seenLinks: MutableSet<String>) {
        val fetchedItems = contentProviderService.fetchContent(source)
        
        fetchedItems.forEach { item ->
             if (item.link.isNotEmpty() && item.link !in seenLinks) {
                 seenLinks.add(item.link)
                 allItems.add(item)
             }
        }
    }
}
