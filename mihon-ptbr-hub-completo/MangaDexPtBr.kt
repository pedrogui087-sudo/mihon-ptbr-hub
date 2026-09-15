package eu.kanade.tachiyomi.extension.pt.mihonptbrhub

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant

@Source
abstract class MangaDexPtBr : KeiSource() {
    private val pageSize = 20

    private val isMangaDex: Boolean
        get() = baseUrl.host == "api.mangadex.org"

    override suspend fun getPopularManga(page: Int): MangasPage = if (isMangaDex) mangaDexSearch("", page) else htmlSearch("", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = if (isMangaDex) mangaDexSearch("", page) else htmlSearch("", page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage =
        if (isMangaDex) mangaDexSearch(query, page) else htmlSearch(query, page)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? =
        if (url.host == "mangadex.org") fetchManga(url.pathSegments.getOrNull(1) ?: return null)
        else fetchHtmlManga(url.toString())

    private suspend fun mangaDexSearch(query: String, page: Int): MangasPage {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())
            .addQueryParameter("availableTranslatedLanguage[]", "pt-br")
            .addQueryParameter("contentRating[]", "safe")
            .addQueryParameter("contentRating[]", "suggestive")
            .addQueryParameter("order[latestUploadedChapter]", "desc")
            .addQueryParameter("includes[]", "cover_art")
            .apply { if (query.isNotBlank()) addQueryParameter("title", query) }
            .build()
        val json = client.get(url).parseAs<JsonElement>().jsonObject
        val data = json["data"]?.jsonArray.orEmpty()
        val total = json["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: data.size
        val mangas = data.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val attributes = obj["attributes"]?.jsonObject ?: return@mapNotNull null
            val title = firstLocalized(attributes["title"]?.jsonObject, "pt-br", "en") ?: id
            val cover = findCoverFileName(obj)
            SManga.create().apply {
                this.url = id
                this.title = title
                this.thumbnail_url = cover?.let { "https://uploads.mangadex.org/covers/$id/$it" }
                this.status = SManga.UNKNOWN
            }
        }
        return MangasPage(mangas, page * pageSize < total)
    }

    private suspend fun fetchManga(id: String): SManga? {
        val json = client.get("$baseUrl/manga/$id?includes[]=cover_art").parseAs<JsonElement>().jsonObject
        val obj = json["data"]?.jsonObject ?: return null
        val attributes = obj["attributes"]?.jsonObject ?: return null
        return SManga.create().apply {
            url = id
            title = firstLocalized(attributes["title"]?.jsonObject, "pt-br", "en") ?: id
            description = firstLocalized(attributes["description"]?.jsonObject, "pt-br", "en").orEmpty()
            status = SManga.UNKNOWN
            findCoverFileName(obj)?.let { thumbnail_url = "https://uploads.mangadex.org/covers/$id/$it" }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = if (isMangaDex) fetchMangaDexUpdate(manga, chapters, fetchDetails, fetchChapters) else fetchHtmlUpdate(manga, chapters, fetchDetails, fetchChapters)

    private suspend fun fetchMangaDexUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val id = manga.url
        val mangaJson = client.get("$baseUrl/manga/$id?includes[]=cover_art").parseAs<JsonElement>().jsonObject
        val obj = mangaJson["data"]?.jsonObject ?: return SMangaUpdate(manga, chapters)
        val attributes = obj["attributes"]?.jsonObject ?: return SMangaUpdate(manga, chapters)
        if (fetchDetails) {
            firstLocalized(attributes["title"]?.jsonObject, "pt-br", "en")?.let { manga.title = it }
            manga.description = firstLocalized(attributes["description"]?.jsonObject, "pt-br", "en").orEmpty()
            findCoverFileName(obj)?.let { manga.thumbnail_url = "https://uploads.mangadex.org/covers/$id/$it" }
        }
        if (!fetchChapters) return SMangaUpdate(manga, chapters)
        val chapterJson = client.get("$baseUrl/chapter?manga=$id&translatedLanguage[]=pt-br&limit=100&order[chapter]=desc").parseAs<JsonElement>().jsonObject
        val chapterList = chapterJson["data"]?.jsonArray.orEmpty().mapNotNull { raw ->
            val chapter = raw.jsonObject
            val chapterId = chapter["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val a = chapter["attributes"]?.jsonObject ?: return@mapNotNull null
            val number = a["chapter"]?.jsonPrimitive?.content
            val title = a["title"]?.jsonPrimitive?.content
            SChapter.create().apply {
                url = chapterId
                name = when {
                    !title.isNullOrBlank() && !number.isNullOrBlank() -> "Capítulo $number - $title"
                    !number.isNullOrBlank() -> "Capítulo $number"
                    !title.isNullOrBlank() -> title
                    else -> "Capítulo"
                }
                chapter_number = number?.toFloatOrNull() ?: 0f
                date_upload = a["publishAt"]?.jsonPrimitive?.content?.let { parseDate(it) } ?: 0L
            }
        }
        return SMangaUpdate(manga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = if (isMangaDex) mangaDexPages(chapter) else htmlPages(chapter)

    private suspend fun mangaDexPages(chapter: SChapter): List<Page> {
        val json = client.get("$baseUrl/at-home/server/${chapter.url}").parseAs<JsonElement>().jsonObject
        val base = json["baseUrl"]?.jsonPrimitive?.content ?: return emptyList()
        val chapterObject = json["chapter"]?.jsonObject ?: return emptyList()
        val hash = chapterObject["hash"]?.jsonPrimitive?.content ?: return emptyList()
        return chapterObject["data"]?.jsonArray.orEmpty().mapIndexed { index, item ->
            Page(index, imageUrl = "$base/data/$hash/${item.jsonPrimitive.content}")
        }
    }

    private suspend fun htmlSearch(query: String, page: Int): MangasPage {
        val url = if (query.isBlank()) "$baseUrl/manga/page/$page/" else "$baseUrl/?s=${query.encodeUrl()}&post_type=wp-manga"
        val doc = client.get(url).asJsoup()
        val cards = doc.select(".c-tabs-item__content, .row.c-tabs-item__content, .page-item-detail.manga")
        val mangas = cards.mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val title = card.selectFirst(".tab-summary .post-title a, .post-title a, .post-title")?.text()?.ifBlank { null } ?: link.text().ifBlank { return@mapNotNull null }
            SManga.create().apply {
                url = link.absUrl("href").substringAfter(baseUrl.toString()).ifBlank { link.absUrl("href") }
                this.title = title
                thumbnail_url = card.selectFirst("img")?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }
            }
        }
        return MangasPage(mangas.distinctBy { it.url }, cards.isNotEmpty())
    }

    private suspend fun fetchHtmlManga(url: String): SManga? {
        val doc = client.get(url).asJsoup()
        val title = doc.selectFirst(".post-title h1, h1.entry-title, h1")?.text()?.ifBlank { null } ?: return null
        val manga = SManga.create().apply {
            this.url = url
            this.title = title
            description = doc.selectFirst(".summary__content, .description-summary, .summary_content")?.text().orEmpty()
            thumbnail_url = doc.selectFirst(".summary_image img, .summary_image img[data-src], .tab-summary img")?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }
            status = SManga.UNKNOWN
        }
        return manga
    }

    private suspend fun fetchHtmlUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val doc = client.get(if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}").asJsoup()
        if (fetchDetails) {
            doc.selectFirst(".post-title h1, h1.entry-title, h1")?.text()?.let { manga.title = it }
            doc.selectFirst(".summary__content, .description-summary, .summary_content")?.text()?.let { manga.description = it }
            doc.selectFirst(".summary_image img, .summary_image img[data-src], .tab-summary img")?.let { manga.thumbnail_url = it.absUrl("data-src").ifBlank { it.absUrl("src") } }
        }
        if (!fetchChapters) return SMangaUpdate(manga, chapters)
        val chapterList = doc.select(".wp-manga-chapter a, .version-chap a, .chapter-list a").mapNotNull { link ->
            val name = link.text().ifBlank { return@mapNotNull null }
            SChapter.create().apply {
                url = link.absUrl("href")
                this.name = name
                chapter_number = Regex("(?:chapter|cap[ií]tulo|cap)\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
            }
        }.distinctBy { it.url }
        return SMangaUpdate(manga, chapterList)
    }

    private suspend fun htmlPages(chapter: SChapter): List<Page> {
        val doc = client.get(chapter.url).asJsoup()
        val images = doc.select(".reading-content img, .chapter-content img, .page-break img, img[data-src]")
        return images.mapIndexedNotNull { index, img ->
            val url = img.absUrl("data-src").ifBlank { img.absUrl("src") }
            if (url.isBlank()) null else Page(index, imageUrl = url)
        }
    }

    override fun getMangaUrl(manga: SManga): String = if (isMangaDex) "https://mangadex.org/title/${manga.url}" else if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = if (isMangaDex) "https://mangadex.org/chapter/${chapter.url}" else chapter.url

    private fun findCoverFileName(manga: JsonObject): String? {
        val relationships = manga["relationships"]?.jsonArray ?: return null
        val cover = relationships.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "cover_art" } ?: return null
        return cover.jsonObject["attributes"]?.jsonObject?.get("fileName")?.jsonPrimitive?.content
    }

    private fun firstLocalized(obj: JsonObject?, vararg languages: String): String? {
        if (obj == null) return null
        for (language in languages) {
            val value = obj[language]?.jsonPrimitive?.content
            if (!value.isNullOrBlank()) return value
        }
        return obj.values.firstOrNull()?.jsonPrimitive?.content
    }

    private fun parseDate(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
