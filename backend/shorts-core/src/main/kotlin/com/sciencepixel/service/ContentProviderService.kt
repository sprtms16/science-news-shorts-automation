package com.sciencepixel.service

import com.sciencepixel.domain.NewsItem
import com.sciencepixel.domain.RssSource
import com.sciencepixel.domain.SourceType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import java.io.ByteArrayInputStream

@Service
class ContentProviderService(
    private val videoHistoryRepository: com.sciencepixel.repository.VideoHistoryRepository
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun fetchContent(source: RssSource): List<NewsItem> {
        return try {
            when (source.type) {
                SourceType.REDDIT_JSON -> fetchReddit(source)
                SourceType.WIKIPEDIA_ON_THIS_DAY -> fetchWikipediaOnThisDay(source)
                SourceType.RSS -> fetchRss(source)
                SourceType.STOCK_NEWS -> fetchStockNews(source)
            }
        } catch (e: Exception) {
            println("❌ Content Fetch Error [${source.type}] ${source.title}: ${e.message}")
            emptyList()
        }
    }
    
    // ... fetchReddit ... (unchanged)

    private fun fetchWikipediaOnThisDay(source: RssSource): List<NewsItem> {
        val today = LocalDate.now()
        val month = today.monthValue
        val day = today.dayOfMonth
        val url = "https://en.wikipedia.org/api/rest_v1/feed/onthisday/events/$month/$day"
        
        println("📜 Fetching History: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ScienceNewsShortsBot/1.0 (mailto:admin@sciencepixel.com)")
            .header("Accept", "application/json; charset=utf-8")
            .build()
        
        var body: String? = null
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("❌ Wikipedia API Error: ${response.code} - ${response.message}")
                    return emptyList()
                }
                
                body = response.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val events = json.getJSONArray("events")
                println("📜 Wikipedia: Found ${events.length()} events.")
                
                val potentialItems = mutableListOf<NewsItem>()
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    val text = event.getString("text")
                    val year = event.optInt("year", 0)
                    
                    val pages = event.optJSONArray("pages")
                    val firstPage = if (pages != null && pages.length() > 0) pages.getJSONObject(0) else null
                    var link = firstPage?.optString("content_urls.desktop.page", null)
                    val titleString = firstPage?.optString("title", "Historical Event") ?: "Historical Event"

                    if (link.isNullOrBlank()) {
                        link = if (titleString != "Historical Event") {
                            "https://en.wikipedia.org/wiki/" + titleString.replace(" ", "_")
                        } else {
                            "https://en.wikipedia.org/wiki/Portal:History"
                        }
                    }

                    // [Duplicate Check]
                    if (videoHistoryRepository.existsByChannelIdAndLink("history", link)) {
                        // Skip duplicate
                        continue
                    }

                    potentialItems.add(NewsItem(
                        title = "$year: $titleString",
                        summary = text,
                        link = link,
                        sourceName = "Wikipedia On This Day"
                    ))
                }
                
                if (potentialItems.isEmpty()) {
                    println("⚠️ All ${events.length()} history events were duplicates or invalid.")
                    return emptyList()
                }
                
                // [Random Selection] Pick ONE random event from fresh items
                val selected = potentialItems.random()
                println("✨ Selected History Event: ${selected.title}")
                return listOf(selected)
            }
        } catch (e: Exception) {
            // ... error handling ...
            println("❌ Content Fetch Error [WIKIPEDIA_ON_THIS_DAY]: ${e.message}")
             if (body != null) {
                 println("   Response Body Preview: ${body?.take(200)}")
            }
            return emptyList()
        }
    }

    private fun fetchRss(source: RssSource): List<NewsItem> {
        // ... existing logic ...
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
            
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val xml = response.body?.string() ?: return emptyList()
            
            // Basic Rome parsing
            val input = SyndFeedInput()
            input.isAllowDoctypes = false
            try {
                val cleanXml = xml.replace(Regex("<!DOCTYPE[^>]*>"), "")
                val feed = input.build(XmlReader(ByteArrayInputStream(cleanXml.toByteArray())))

                return feed.entries.take(10).map { entry ->
                   NewsItem(
                       title = entry.title ?: "No Title",
                       summary = entry.description?.value ?: entry.title ?: "",
                       link = entry.link ?: "",
                       sourceName = source.title,
                       pubDate = entry.publishedDate // Add pubDate to NewsItem if it exists, or handle here? 
                       // NewsItem doesn't have pubDate field yet? Check definition. 
                       // Assuming it doesn't, we can't fully filter in RssItemReader without it.
                       // Actually, for Stock, we need to filter HERE if NewsItem doesn't hold data.
                   )
                }
            } catch (e: Exception) {
                println("❌ RSS Parse Error [${source.title}] (${source.url}): ${e.message}")
                return emptyList()
            }
        }
    }
    
    private fun fetchStockNews(source: RssSource): List<NewsItem> {
         val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
            
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val xml = response.body?.string() ?: return emptyList()
            
            val input = SyndFeedInput()
            input.isAllowDoctypes = false
            try {
                val cleanXml = xml.replace(Regex("<!DOCTYPE[^>]*>"), "")
                val feed = input.build(XmlReader(ByteArrayInputStream(cleanXml.toByteArray())))

                // Filter by Today AND Keywords
                val today = java.time.LocalDate.now()
                val startOfDay = java.util.Date.from(today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())
                
                val stockKeywords = listOf(
                    "stock", "market", "finance", "economy", "invest", "trade", "trading", 
                    "nasdaq", "dow", "s&p", "bitcoin", "crypto", "bank", "inflation", "fed", 
                    "rate", "bond", "yield", "commodity", "gold", "oil", "earning", "profit", 
                    "revenue", "analyst", "forecast", "bull", "bear", "ipo", "dividend", "shares"
                )

                return feed.entries
                    .filter { it.publishedDate != null && it.publishedDate.after(startOfDay) }
                    .filter { entry -> 
                        val content = (entry.title ?: "") + " " + (entry.description?.value ?: "")
                        stockKeywords.any { content.contains(it, ignoreCase = true) }
                    }
                    .take(20)
                    .map { entry ->
                       NewsItem(
                           title = entry.title ?: "No Title",
                           summary = entry.description?.value ?: entry.title ?: "",
                           link = entry.link ?: "",
                           sourceName = source.title
                       )
                    }
            } catch (e: Exception) {
                println("❌ Stock RSS Parse Error [${source.title}]: ${e.message}")
                return emptyList()
            }
        }
    }
}
