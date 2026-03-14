package eu.kanade.tachiyomi.animeextension.en.kickassanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class KickAssAnime : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "KickAssAnime"
    override val baseUrl = "https://kickassanime.am"
    override val lang = "en"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/api/anime".toHttpUrl().newBuilder()
            .addQueryParameter("type", "popular")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<AnimeListResponse>()
        return AnimesPage(data.result.map { it.toSAnime() }, data.result.size >= 24)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/anime".toHttpUrl().newBuilder()
            .addQueryParameter("type", "recent")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$baseUrl/api/anime/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        if (query.isNotBlank()) urlBuilder.addQueryParameter("q", query)
        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    val selected = filter.state.filter { it.state }.joinToString(",") { it.name.lowercase() }
                    if (selected.isNotEmpty()) urlBuilder.addQueryParameter("genre", selected)
                }
                is YearFilter -> {
                    if (filter.state.isNotBlank()) urlBuilder.addQueryParameter("year", filter.state.trim())
                }
                is SubDubFilter -> {
                    val value = SubDubFilter.OPTIONS[filter.state].second
                    if (value.isNotEmpty()) urlBuilder.addQueryParameter("type", value)
                }
                is SortFilter -> {
                    urlBuilder.addQueryParameter("sort", SortFilter.OPTIONS[filter.state].second)
                }
                else -> {}
            }
        }
        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters are ignored when searching by keyword"),
        AnimeFilter.Separator(),
        GenreFilter(),
        YearFilter(),
        SubDubFilter(),
        SortFilter(),
    )

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/api${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val data = response.parseAs<AnimeDetailResponse>()
        return SAnime.create().apply {
            title         = data.name
            thumbnail_url = buildThumbnailUrl(data.poster)
            description   = buildString {
                data.synopsis?.let { append(it) }
                data.year?.let { append("\n\nYear: $it") }
                data.type?.let { append("\nType: $it") }
            }
            genre  = data.genres?.joinToString(", ")
            status = when (data.status?.lowercase()) {
                "currently airing" -> SAnime.ONGOING
                "finished airing"  -> SAnime.COMPLETED
                else               -> SAnime.UNKNOWN
            }
        }
    }

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl/api${anime.url}/episodes?ep=1", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data       = response.parseAs<EpisodeListResponse>()
        val subDubPref = preferences.getString(PREF_SUB_DUB, "sub") ?: "sub"
        return data.result
            .filter { ep -> ep.type == null || subDubPref == "both" || ep.type.lowercase() == subDubPref }
            .mapIndexed { idx, ep ->
                SEpisode.create().apply {
                    url            = ep.link ?: ""
                    val typeTag    = ep.type?.let { " [${it.uppercase()}]" } ?: ""
                    name           = "Episode ${ep.episodeString ?: (idx + 1)}$typeTag"
                    episode_number = ep.episodeString?.toFloatOrNull() ?: (idx + 1f)
                }
            }
            .reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val data     = response.parseAs<EpisodeDetailResponse>()
        val priority = preferences.getStringSet(PREF_SERVER_PRIORITY, DEFAULT_SERVER_PRIORITY) ?: DEFAULT_SERVER_PRIORITY
        val videos   = data.servers?.mapNotNull { server ->
            val src = server.src ?: return@mapNotNull null
            Video(src, server.name ?: "Server", src)
        } ?: emptyList()
        return videos.sortedWith(compareBy(
            { video -> priority.indexOfFirst { video.quality.contains(it, ignoreCase = true) }.let { if (it == -1) Int.MAX_VALUE else it } },
            { it.quality },
        ))
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SUB_DUB
            title = "Preferred audio"
            entries = arrayOf("Subbed", "Dubbed", "Both")
            entryValues = arrayOf("sub", "dub", "both")
            setDefaultValue("sub")
            summary = "%s"
            screen.addPreference(this)
        }
        MultiSelectListPreference(screen.context).apply {
            key = PREF_SERVER_PRIORITY
            title = "Server priority (top picks load first)"
            entries = SERVER_LIST.toTypedArray()
            entryValues = SERVER_LIST.toTypedArray()
            setDefaultValue(DEFAULT_SERVER_PRIORITY)
            screen.addPreference(this)
        }
    }

    private fun buildThumbnailUrl(poster: String?): String? {
        if (poster == null) return null
        return if (poster.startsWith("http")) poster else "https://cdn.kickassanime.am/uploads/$poster"
    }

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromString(body.string())

    private fun AnimeResult.toSAnime(): SAnime = SAnime.create().apply {
        url           = "/anime/$slug"
        title         = name ?: "Unknown"
        thumbnail_url = buildThumbnailUrl(poster)
    }

    companion object {
        private const val PREF_SUB_DUB        = "pref_sub_dub"
        private const val PREF_SERVER_PRIORITY = "pref_server_priority"
        private val SERVER_LIST = listOf("Vidstreaming","Vidcloud","StreamSB","Doodstream","Filemoon","MyCloud","Streamtape","Mp4Upload")
        private val DEFAULT_SERVER_PRIORITY = linkedSetOf("Vidstreaming","Vidcloud","StreamSB")
    }
}

class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name, false)
class GenreFilter : AnimeFilter.Group<GenreCheckBox>("Genre", listOf("Action","Adventure","Comedy","Drama","Ecchi","Fantasy","Horror","Isekai","Mahou Shoujo","Mecha","Music","Mystery","Psychological","Romance","Sci-Fi","Slice of Life","Sports","Supernatural","Thriller").map { GenreCheckBox(it) })
class YearFilter : AnimeFilter.Text("Year  (e.g. 2024)")
class SubDubFilter : AnimeFilter.Select<String>("Sub / Dub", OPTIONS.map { it.first }.toTypedArray()) {
    companion object { val OPTIONS = listOf("All" to "","Sub" to "sub","Dub" to "dub") }
}
class SortFilter : AnimeFilter.Select<String>("Sort by", OPTIONS.map { it.first }.toTypedArray()) {
    companion object { val OPTIONS = listOf("Popular" to "popular","Latest" to "latest","A – Z" to "title_az","Z – A" to "title_za","Year ↑" to "year_asc","Year ↓" to "year_desc") }
}

@Serializable data class AnimeListResponse(val result: List<AnimeResult> = emptyList())
@Serializable data class AnimeResult(val slug: String = "", val name: String? = null, val poster: String? = null)
@Serializable data class AnimeDetailResponse(val name: String = "", val poster: String? = null, val synopsis: String? = null, val genres: List<String>? = null, val status: String? = null, val year: Int? = null, val type: String? = null)
@Serializable data class EpisodeListResponse(val result: List<EpisodeResult> = emptyList())
@Serializable data class EpisodeResult(val episodeString: String? = null, val link: String? = null, val type: String? = null)
@Serializable data class EpisodeDetailResponse(val servers: List<ServerResult>? = null)
@Serializable data class ServerResult(val name: String? = null, val src: String? = null)