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
    private val videoHistoryRepository: com.sciencepixel.repository.VideoHistoryRepository,
    private val geminiService: GeminiService
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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
    
    private fun fetchReddit(source: RssSource): List<NewsItem> {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "ScienceNewsShortsBot/1.0")
            .build()
            
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val jsonStr = response.body?.string() ?: return emptyList()
                val json = JSONObject(jsonStr)
                val children = json.getJSONObject("data").getJSONArray("children")
                
                val items = mutableListOf<NewsItem>()
                for (i in 0 until children.length()) {
                    val data = children.getJSONObject(i).getJSONObject("data")
                    if (data.optBoolean("stickied", false)) continue // Skip sticky posts
                    
                    val title = data.optString("title")
                    val selftext = data.optString("selftext")
                    val url = data.optString("url")
                    
                    items.add(NewsItem(
                        title = title,
                        summary = if (selftext.isNotBlank()) selftext else title,
                        link = url,
                        sourceName = "Reddit (${source.title})"
                    ))
                }
                items.take(10)
            }
        } catch (e: Exception) {
            println("❌ Reddit Fetch Error: ${e.message}")
            emptyList()
        }
    }

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
                
                // [Safety Filter Retry - Max 5 attempts]
                var attempts = 0
                val candidates = potentialItems.toMutableList()
                
                while (attempts < 5 && candidates.isNotEmpty()) {
                    val selected = candidates.random()
                    println("🔍 Checking Safety for candidate (Attempt ${attempts + 1}/5): ${selected.title}")
                    
                    try {
                        if (geminiService.checkSensitivity(selected.title, selected.summary, "history")) {
                            println("✨ Selected History Event (Safety OK): ${selected.title}")
                            return listOf(selected)
                        } else {
                            println("⛔ Wikipedia Item Rejected by Safety Filter: ${selected.title}")
                            candidates.remove(selected)
                        }
                    } catch (e: Exception) {
                        println("⚠️ Gemini Safety Check Failed (Transient): ${e.message}. Falling back to default SAFE for this item.")
                        // If Gemini is down/429, don't block the whole process if we have a candidate.
                        // For history, most items are safe unless they are modern political propaganda.
                        return listOf(selected) 
                    }
                    attempts++
                }
                
                println("⚠️ No safe Wikipedia items determined after $attempts attempts (or all rejected).")
                return emptyList()
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

    private fun fetchAndParseRss(url: String): List<com.rometools.rome.feed.synd.SyndEntry> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .build()
            
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                
                val bodyStream = response.body?.byteStream() ?: return emptyList()
                
                val input = SyndFeedInput()
                input.isAllowDoctypes = false
                
                val feed = input.build(XmlReader(bodyStream))
                feed.entries
            }
        } catch (e: Exception) {
            println("❌ RSS Helper Error ($url): ${e.message}")
            emptyList()
        }
    }

    private fun fetchRss(source: RssSource): List<NewsItem> {
        val entries = fetchAndParseRss(source.url)
        return entries.take(10).map { entry ->
           NewsItem(
               title = entry.title ?: "No Title",
               summary = entry.description?.value ?: entry.title ?: "",
               link = entry.link ?: "",
               sourceName = source.title
           )
        }
    }
    
    fun fetchStockNews(source: RssSource): List<NewsItem> {
        println("🔍 Fetching Dynamic Stock News...")
        
        // 1. Fetch General Business News (Base)
        val baseEntries = fetchAndParseRss(source.url)
        println("  - Base Feed: Found ${baseEntries.size} items")
        
        // 2. Extract Headlines for AI Analysis
        val headlines = baseEntries.take(15).joinToString("\n") { 
            "- ${it.title}" 
        }
        
        // 3. AI Analysis: Find Trending Tickers
        val trendingTickers = geminiService.extractTrendingTickers(headlines)
        
        // 4. Fetch Specific News for Tickers
        val specificNewsItems = mutableListOf<NewsItem>()
        
        trendingTickers.forEach { ticker ->
            try {
                // Google News Search RSS
                val searchUrl = "https://news.google.com/rss/search?q=${ticker.replace(" ", "+")}+stock+news&hl=en-US&gl=US&ceid=US:en"
                val searchEntries = fetchAndParseRss(searchUrl)
                
                // Take top 2 items and verify safety
                val topItems = searchEntries.take(5).map { entry ->
                    NewsItem(
                        title = "[${ticker.uppercase()}] ${entry.title}", 
                        summary = entry.description?.value ?: "",
                        link = entry.link ?: "",
                        sourceName = "Google News ($ticker)"
                    )
                }.filter { item ->
                    // Safety check for stock news contents
                    val isSafe = geminiService.checkSensitivity(item.title, item.summary, "stocks")
                    if (!isSafe) println("⛔ Stock Item Rejected for $ticker: ${item.title}")
                    isSafe
                }.take(2)
                
                specificNewsItems.addAll(topItems)
                println("  - Fetched ${topItems.size} safe items for '$ticker'")
                
                // Rate limit politeness
                Thread.sleep(500)
            } catch (e: Exception) {
                println("  - Failed to fetch news for $ticker: ${e.message}")
            }
        }
        
        // 5. Combine & Filter Base Items
        // Filter base items to ensure they are from today (reusing logic)
        val today = java.time.LocalDate.now()
        val startOfDay = java.util.Date.from(today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())
        
        val filteredBaseItems = baseEntries
            .filter { it.publishedDate != null && it.publishedDate.after(startOfDay) }
            .take(5) // Take top 5 general stories
            .map { entry ->
                NewsItem(
                    title = entry.title ?: "No Title",
                    summary = entry.description?.value ?: "",
                    link = entry.link ?: "",
                    sourceName = source.title
                )
            }
            
        val finalResult = (specificNewsItems + filteredBaseItems).distinctBy { it.title }
        println("✅ Dynamic Stock News: ${finalResult.size} items collected.")
        
        return finalResult
    }
}
