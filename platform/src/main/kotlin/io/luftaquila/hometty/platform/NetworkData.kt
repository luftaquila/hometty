package io.luftaquila.hometty.platform

import android.content.Context
import androidx.work.*
import io.luftaquila.hometty.core.Entry
import io.luftaquila.hometty.core.Patterns
import io.luftaquila.hometty.core.StateNames
import io.luftaquila.hometty.data.Secrets
import io.luftaquila.hometty.core.SettingsStore
import io.luftaquila.hometty.core.StateStore
import io.luftaquila.hometty.core.UserMessage
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.get

/** Feeds and weather over HTTPS; feed results are cached in [state]. */
class NetworkData(
    private val store: SettingsStore,
    private val state: StateStore,
    private val secrets: Secrets,
) {
    /** A feed item seen for the first time, rendered with its feed's format. */
    data class FeedItem(val feed: String, val text: String)

    private val _newItems =
        MutableSharedFlow<FeedItem>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Items each fetch finds that earlier fetches of the feed did not have. */
    val newItems: SharedFlow<FeedItem> = _newItems.asSharedFlow()

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()

    suspend fun fetch(url: String): String =
        download(url).let { (bytes, charset) -> String(bytes, charset ?: Charsets.UTF_8) }

    /** The body of [url] (4 MiB at most) and the charset its response names, if any. */
    private suspend fun download(url: String): Pair<ByteArray, java.nio.charset.Charset?> {
        val request = Request.Builder().url(url).build()
        require(request.url.isHttps) { "Use an HTTPS URL" }
        val response =
            suspendCancellableCoroutine<Response> { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (continuation.isActive)
                                continuation.resume(
                                    response,
                                    onCancellation = { _, value, _ -> value.close() },
                                )
                            else response.close()
                        }
                    }
                )
            }
        return withContext(Dispatchers.IO) {
            response.use {
                check(it.isSuccessful) { "HTTP ${it.code}" }
                require(it.request.url.isHttps) { "HTTPS redirect required" }
                val body = requireNotNull(it.body)
                require(body.contentLength() <= 4 * 1024 * 1024) { "Response too large" }
                val source = body.source()
                val sink = okio.Buffer()
                var count = 0L
                while (true) {
                    val n = source.read(sink, 8192)
                    if (n < 0) break
                    count += n
                    require(count <= 4 * 1024 * 1024) { "Response too large" }
                }
                sink.readByteArray() to body.contentType()?.charset()
            }
        }
    }

    /** Fetches [entry] and saves it through [record]. */
    suspend fun feed(entry: Entry, announce: Boolean = true): String {
        val url = requireNotNull(entry.fields["url"]) { "Feed URL missing" }
        val (body, charset) = download(url)
        // Parsing a big feed takes a while: not on the caller's (often the main) thread.
        val items = withContext(Dispatchers.Default) { render(entry, body, charset, url) }
        return record(entry, items, announce)
    }

    /**
     * The items of a fetched feed [body], newest first, filtered, in the feed's format and
     * printable. Without a [charset] from the response, the feed's own declaration decides.
     */
    private fun render(entry: Entry, body: ByteArray, charset: java.nio.charset.Charset?, url: String): List<String> {
        val document =
            Jsoup.parse(java.io.ByteArrayInputStream(body), charset?.name(), url, Parser.xmlParser())
        val items =
            document.select(entry.fields["entryTag"].orEmpty().ifBlank { "item, entry" }).take(100)
        val include = entry.fields["include"].orEmpty()
        val exclude = entry.fields["exclude"].orEmpty()
        val format = entry.fields["format"].orEmpty().ifBlank { "{title}\n{url}" }
        val rendered =
            items.mapNotNull { item ->
                val title = item.selectFirst("title")?.text().orEmpty()
                val description =
                    item.selectFirst("description, summary, content")?.text().orEmpty()
                val link =
                    item
                        .selectFirst("link")
                        ?.let { it.attr("href").ifBlank { it.text() } }
                        .orEmpty()
                val date =
                    item
                        .selectFirst(
                            entry.fields["dateTag"].orEmpty().ifBlank {
                                "pubDate, published, updated"
                            }
                        )
                        ?.text()
                        .orEmpty()
                val match = "$title $description"
                if (
                    include.isNotBlank() && !Patterns.matches(include, match) ||
                        exclude.isNotBlank() && Patterns.matches(exclude, match)
                )
                    null
                else
                    format
                        .replace("{title}", title)
                        .replace("{text}", Jsoup.parse(description).text())
                        .replace("{url}", link)
                        .replace("{date}", date)
            }
        // Feed text reaches the terminal: no escape sequences from it.
        return rendered.take(50).map { io.luftaquila.hometty.core.printable(it.take(2048)) }
    }

    /**
     * Saves a fetch of [entry] with its [items] (newest first) and, when [announce], posts the
     * items no earlier fetch had to [newItems]; the first fetch of a feed only records what it has.
     */
    suspend fun record(entry: Entry, items: List<String>, announce: Boolean = true): String {
        val bounded = items.take(50)
        val text = bounded.joinToString("\n\n").ifEmpty { "No matching entries" }
        val shown = state.getState(StateNames.feedShown(entry.id))
        val known = shown.orEmpty().split(',').filter { it.isNotEmpty() }.toMutableSet()
        val fresh = bounded.filter { known.add(itemId(it)) }
        state.putState(
            mapOf(
                StateNames.feedText(entry.id) to text.take(200000),
                StateNames.feedItems(entry.id) to org.json.JSONArray(bounded).toString(),
                StateNames.feedUpdated(entry.id) to System.currentTimeMillis().toString(),
                StateNames.feedShown(entry.id) to known.toList().takeLast(500).joinToString(","),
            )
        )
        // Feeds list the newest first; they print oldest first.
        if (shown != null && announce) fresh.asReversed().forEach { _newItems.tryEmit(FeedItem(entry.name, it)) }
        return text
    }

    /**
     * The weather at [latitude], [longitude] from the chosen provider, in the weather format;
     * [place] names where it is, the city falling back to the provider's name for it.
     */
    suspend fun weather(latitude: Double, longitude: Double, place: Place? = null): String {
        val settings = store.snapshot()
        val units = settings[Settings.units]
        val unit =
            when (units) {
                "metric" -> "°C"
                "imperial" -> "°F"
                else -> "K"
            }
        val (area, temperature, description) =
            if (settings[Settings.weatherProvider] == "openweather")
                openWeather(latitude, longitude, units)
            else wttr(latitude, longitude, units)
        return settings[Settings.weatherFormat]
            .replace("{city}", place?.city?.ifEmpty { null } ?: area)
            .replace("{district}", place?.district.orEmpty())
            .replace("{temp}", temperature)
            .replace("{unit}", unit)
            .replace("{description}", description)
    }

    /** City, temperature and conditions from wttr.in, which needs no key. */
    private suspend fun wttr(latitude: Double, longitude: Double, units: String): Triple<String, String, String> {
        val url =
            HttpUrl.Builder()
                .scheme("https")
                .host("wttr.in")
                .addPathSegment("$latitude,$longitude")
                .addQueryParameter("format", "j1")
                .build()
        val data = JSONObject(fetch(url.toString()))
        val now = data.getJSONArray("current_condition").getJSONObject(0)
        val celsius = now.getString("temp_C").toDouble()
        val temperature =
            when (units) {
                "imperial" -> now.getString("temp_F")
                "standard" -> "%.1f".format(java.util.Locale.ROOT, celsius + 273.15)
                else -> now.getString("temp_C")
            }
        val city =
            data.optJSONArray("nearest_area")?.optJSONObject(0)?.optJSONArray("areaName")
                ?.optJSONObject(0)?.optString("value").orEmpty()
        val description = now.getJSONArray("weatherDesc").getJSONObject(0).getString("value")
        return Triple(city, temperature, description)
    }

    /** City, temperature and conditions from OpenWeather, with the saved API key. */
    private suspend fun openWeather(latitude: Double, longitude: Double, units: String): Triple<String, String, String> {
        val key =
            secrets.get("weatherKey")?.takeIf { it.isNotBlank() }
                ?: throw UserMessage("message_set_weather_key")
        val lat = latitude.toString()
        val lon = longitude.toString()
        val url =
            HttpUrl.Builder()
                .scheme("https")
                .host("api.openweathermap.org")
                .addPathSegments("data/2.5/weather")
                .addQueryParameter("lat", lat)
                .addQueryParameter("lon", lon)
                .addQueryParameter("appid", key)
                .addQueryParameter("units", units)
                .build()
        val data = JSONObject(fetch(url.toString()))
        return Triple(
            data.optString("name"),
            data.getJSONObject("main").getDouble("temp").toString(),
            data.getJSONArray("weather").getJSONObject(0).getString("description"),
        )
    }

    companion object {
        /** Background feed refresh at the feed interval, while feeds refresh automatically. */
        suspend fun schedule(context: Context, store: SettingsStore) {
            val settings = store.snapshot()
            val manager = WorkManager.getInstance(context)
            if (!settings[Settings.rssEnabled]) {
                manager.cancelUniqueWork(FEED_WORK)
                return
            }
            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (settings[Settings.rssWifiOnly]) NetworkType.UNMETERED
                        else NetworkType.CONNECTED
                    )
                    .build()
            val work =
                PeriodicWorkRequestBuilder<RefreshWorker>(
                        settings[Settings.rssInterval].toLong(),
                        TimeUnit.MINUTES,
                    )
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            manager.enqueueUniquePeriodicWork(FEED_WORK, ExistingPeriodicWorkPolicy.UPDATE, work)
        }

        private const val FEED_WORK = "hometty-rss"

        private fun itemId(text: String) =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray())
                .take(12)
                .joinToString("") { "%02x".format(it) }
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies = applicationContext as PlatformDependencies
        val network = dependencies.network
        return try {
            var failures = 0
            dependencies.storage
                .list("rss")
                .filter { it.fields["enabled"] != "false" }
                .forEach {
                    try {
                        network.feed(it)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failures++
                    }
                }
            if (failures > 0) return if (runAttemptCount < 3) Result.retry() else Result.failure()
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is IOException && runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
