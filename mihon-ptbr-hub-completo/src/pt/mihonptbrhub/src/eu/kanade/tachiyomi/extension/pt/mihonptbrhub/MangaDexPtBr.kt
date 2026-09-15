package eu.kanade.tachiyomi.extension.pt.mihonptbrhub

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder
import java.time.Instant

@Source
abstract class MangaDexPtBr : KeiSource() {

    private val pageSize = 20

    private val isMangaDex: Boolean
        get() = baseUrl.toHttpUrl().host == "api.mangadex.org"

    override suspend fun getPopularManga(page: Int): MangasPage {
        return if (isMangaDex) {
            mangaDexSearch("", page)
        } else {
            htmlSearch("", page)
        }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return if (isMangaDex) {
            mangaDexSearch("", page)
        } else {
            htmlSearch("", page)
        }
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        return if (isMangaDex) {
            mangaDexSearch(query, page)
        } else {
            htmlSearch(query, page)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }

        return if (isMangaDex) {
            val id = url.pathSegments.getOrNull(1) ?: return null
            fetchManga(id)
        } else {
            fetchHtmlManga(url)
        }
    }

    private suspend fun mangaDexSearch(
        query: String,
        page: Int,
    ): MangasPage {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())
            .addQueryParameter("availableTranslatedLanguage[]", "pt-br")
            .addQueryParameter("contentRating[]", "safe")
            .addQueryParameter("contentRating[]", "suggestive")
            .addQueryParameter("order[latestUploadedChapter]", "desc")
            .addQueryParameter("includes[]", "cover_art")
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("title", query)
                }
            }
            .build()

        val json = client.get(url).parseAs<JsonElement>().jsonObject
        val data = json["data"]?.jsonArray.orEmpty()
        val total = json["total"]
            ?.jsonPrimitive
            ?.content
            ?.toIntOrNull()
            ?: data.size

        val mangas = data.mapNotNull { item ->
            val manga = item.jsonObject

            val id = manga["id"]
                ?.jsonPrimitive
                ?.content
                ?: return@mapNotNull null

            val attributes = manga["attributes"]
                ?.jsonObject
                ?: return@mapNotNull null

            val title = firstLocalized(
                attributes["title"]?.jsonObject,
                "pt-br",
                "en",
            ) ?: id

            val cover = findCoverFileName(manga)

            SManga.create().apply {
                url = id.toHttpUrl()
                this.title = title
                thumbnail_url = cover?.let {
                    "https://uploads.mangadex.org/covers/$id/$it"
                }
                status = SManga.UNKNOWN
            }
        }

        return MangasPage(
            mangas,
            page * pageSize < total,
        )
    }

    private suspend fun fetchManga(id: String): SManga? {
        val json = client.get(
            "$baseUrl/manga/$id?includes[]=cover_art",
        ).parseAs<JsonElement>().jsonObject

        val manga = json["data"]
            ?.jsonObject
            ?: return null

        val attributes = manga["attributes"]
            ?.jsonObject
            ?: return null

        return SManga.create().apply {
            url = id.toHttpUrl()
            title = firstLocalized(
                attributes["title"]?.jsonObject,
                "pt-br",
                "en",
            ) ?: id

            description = firstLocalized(
                attributes["description"]?.jsonObject,
                "pt-br",
                "en",
            ).orEmpty()

            status = SManga.UNKNOWN

            findCoverFileName(manga)?.let {
                thumbnail_url =
                    "https://uploads.mangadex.org/covers/$id/$it"
            }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        return if (isMangaDex) {
            fetchMangaDexUpdate(
                manga,
                chapters,
                fetchDetails,
                fetchChapters,
            )
        } else {
            fetchHtmlUpdate(
                manga,
                chapters,
                fetchDetails,
                fetchChapters,
            )
        }
    }

    private suspend fun fetchMangaDexUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.toString()

        val mangaJson = client.get(
            "$baseUrl/manga/$id?includes[]=cover_art",
        ).parseAs<JsonElement>().jsonObject

        val mangaObject = mangaJson["data"]
            ?.jsonObject
            ?: return SMangaUpdate(manga, chapters)

        val attributes = mangaObject["attributes"]
            ?.jsonObject
            ?: return SMangaUpdate(manga, chapters)

        if (fetchDetails) {
            firstLocalized(
                attributes["title"]?.jsonObject,
                "pt-br",
                "en",
            )?.let {
                manga.title = it
            }

            manga.description = firstLocalized(
                attributes["description"]?.jsonObject,
                "pt-br",
                "en",
            ).orEmpty()

            findCoverFileName(mangaObject)?.let {
                manga.thumbnail_url =
                    "https://uploads.mangadex.org/covers/$id/$it"
            }
        }

        if (!fetchChapters) {
            return SMangaUpdate(manga, chapters)
        }

        val chapterJson = client.get(
            "$baseUrl/chapter" +
                "?manga=$id" +
                "&translatedLanguage[]=pt-br" +
                "&limit=100" +
                "&order[chapter]=desc",
        ).parseAs<JsonElement>().jsonObject

        val chapterList = chapterJson["data"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { raw ->
                val chapter = raw.jsonObject

                val chapterId = chapter["id"]
                    ?.jsonPrimitive
                    ?.content
                    ?: return@mapNotNull null

                val attributesObject = chapter["attributes"]
                    ?.jsonObject
                    ?: return@mapNotNull null

                val number = attributesObject["chapter"]
                    ?.jsonPrimitive
                    ?.content

                val title = attributesObject["title"]
                    ?.jsonPrimitive
                    ?.content

                SChapter.create().apply {
                    url = chapterId.toHttpUrl()

                    name = when {
                        !title.isNullOrBlank() &&
                            !number.isNullOrBlank() -> {
                            "Capítulo $number - $title"
                        }

                        !number.isNullOrBlank() -> {
                            "Capítulo $number"
                        }

                        !title.isNullOrBlank() -> {
                            title
                        }

                        else -> {
                            "Capítulo"
                        }
                    }

                    chapter_number =
                        number?.toFloatOrNull() ?: 0f

                    date_upload =
                        attributesObject["publishAt"]
                            ?.jsonPrimitive
                            ?.content
                            ?.let { parseDate(it) }
                            ?: 0L
                }
            }

        return SMangaUpdate(manga, chapterList)
    }

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {
        return if (isMangaDex) {
            mangaDexPages(chapter)
        } else {
            htmlPages(chapter)
        }
    }

    private suspend fun mangaDexPages(
        chapter: SChapter,
    ): List<Page> {
        val chapterId = chapter.url.toString()

        val json = client.get(
            "$baseUrl/at-home/server/$chapterId",
        ).parseAs<JsonElement>().jsonObject

        val base = json["baseUrl"]
            ?.jsonPrimitive
            ?.content
            ?: return emptyList()

        val chapterObject = json["chapter"]
            ?.jsonObject
            ?: return emptyList()

        val hash = chapterObject["hash"]
            ?.jsonPrimitive
            ?.content
            ?: return emptyList()

        return chapterObject["data"]
            ?.jsonArray
            .orEmpty()
            .mapIndexed { index, item ->
                Page(
                    index,
                    imageUrl = "$base/data/$hash/${item.jsonPrimitive.content}",
                )
            }
    }

    private suspend fun htmlSearch(
        query: String,
        page: Int,
    ): MangasPage {
        val url = if (query.isBlank()) {
            "$baseUrl/manga/page/$page/"
        } else {
            "$baseUrl/?s=${query.encodeUrl()}&post_type=wp-manga"
        }

        val document = client.get(url).asJsoup()

        val cards = document.select(
            ".c-tabs-item__content, " +
                ".row.c-tabs-item__content, " +
                ".page-item-detail.manga",
        )

        val mangas = cards.mapNotNull { card ->
            val link = card.selectFirst("a[href]")
                ?: return@mapNotNull null

            val title = card.selectFirst(
                ".tab-summary .post-title a, " +
                    ".post-title a, " +
                    ".post-title",
            )?.text()?.ifEmpty {
                null
            } ?: link.text().ifEmpty {
                return@mapNotNull null
            }

            val mangaUrl = link.absUrl("href").toHttpUrl()

            SManga.create().apply {
                url = mangaUrl
                this.title = title

                thumbnail_url = card.selectFirst("img")?.let {
                    it.absUrl("data-src").ifEmpty {
                        it.absUrl("src")
                    }
                }

                status = SManga.UNKNOWN
            }
        }

        return MangasPage(
            mangas.distinctBy { it.url },
            mangas.isNotEmpty(),
        )
    }

    private suspend fun fetchHtmlManga(
        url: HttpUrl,
    ): SManga? {
        val document = client.get(url).asJsoup()

        val title = document.selectFirst(
            ".post-title h1, h1.entry-title, h1",
        )?.text()?.ifEmpty {
            null
        } ?: return null

        return SManga.create().apply {
            this.url = url
            this.title = title

            description = document.selectFirst(
                ".summary__content, " +
                    ".description-summary, " +
                    ".summary_content",
            )?.text().orEmpty()

            thumbnail_url = document.selectFirst(
                ".summary_image img, " +
                    ".tab-summary img",
            )?.let {
                it.absUrl("data-src").ifEmpty {
                    it.absUrl("src")
                }
            }

            status = SManga.UNKNOWN
        }
    }

    private suspend fun fetchHtmlUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(manga.url).asJsoup()

        if (fetchDetails) {
            document.selectFirst(
                ".post-title h1, h1.entry-title, h1",
            )?.text()?.let {
                manga.title = it
            }

            document.selectFirst(
                ".summary__content, " +
                    ".description-summary, " +
                    ".summary_content",
            )?.text()?.let {
                manga.description = it
            }

            document.selectFirst(
                ".summary_image img, " +
                    ".tab-summary img",
            )?.let {
                manga.thumbnail_url =
                    it.absUrl("data-src").ifEmpty {
                        it.absUrl("src")
                    }
            }
        }

        if (!fetchChapters) {
            return SMangaUpdate(manga, chapters)
        }

        val chapterList = document.select(
            ".wp-manga-chapter a, " +
                ".version-chap a, " +
                ".chapter-list a",
        ).mapNotNull { link ->
            val name = link.text().ifEmpty {
                return@mapNotNull null
            }

            val chapterUrl = link.absUrl("href")

            if (chapterUrl.isBlank()) {
                return@mapNotNull null
            }

            val chapterNumber = Regex(
                "(?:chapter|cap[ií]tulo|cap)\\s*" +
                    "([0-9]+(?:\\.[0-9]+)?)",
                RegexOption.IGNORE_CASE,
            ).find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toFloatOrNull()
                ?: 0f

            SChapter.create().apply {
                url = chapterUrl.toHttpUrl()
                this.name = name
                chapter_number = chapterNumber
            }
        }.distinctBy { it.url }

        return SMangaUpdate(manga, chapterList)
    }

    private suspend fun htmlPages(
        chapter: SChapter,
    ): List<Page> {
        val document = client.get(chapter.url).asJsoup()

        return document.select(
            ".reading-content img, " +
                ".chapter-content img, " +
                ".page-break img, " +
                "img[data-src]",
        ).mapIndexedNotNull { index, image ->
            val imageUrl = image.absUrl("data-src").ifEmpty {
                image.absUrl("src")
            }

            if (imageUrl.isBlank()) {
                null
            } else {
                Page(
                    index,
                    imageUrl = imageUrl,
                )
            }
        }
    }

    override fun getMangaUrl(
        manga: SManga,
    ): String {
        return if (isMangaDex) {
            "https://mangadex.org/title/${manga.url}"
        } else {
            manga.url.toString()
        }
    }

    override fun getChapterUrl(
        chapter: SChapter,
    ): String {
        return if (isMangaDex) {
            "https://mangadex.org/chapter/${chapter.url}"
        } else {
            chapter.url.toString()
        }
    }

    private fun findCoverFileName(
        manga: JsonObject,
    ): String? {
        val relationships = manga["relationships"]
            ?.jsonArray
            ?: return null

        val cover = relationships.firstOrNull {
            it.jsonObject["type"]
                ?.jsonPrimitive
                ?.content == "cover_art"
        } ?: return null

        return cover.jsonObject["attributes"]
            ?.jsonObject
            ?.get("fileName")
            ?.jsonPrimitive
            ?.content
    }

    private fun firstLocalized(
        obj: JsonObject?,
        vararg languages: String,
    ): String? {
        if (obj == null) {
            return null
        }

        for (language in languages) {
            val value = obj[language]
                ?.jsonPrimitive
                ?.content

            if (!value.isNullOrBlank()) {
                return value
            }
        }

        return obj.values
            .firstOrNull()
            ?.jsonPrimitive
            ?.content
    }

    private fun parseDate(
        value: String,
    ): Long {
        return runCatching {
            Instant.parse(value).toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun String.encodeUrl(): String {
        return URLEncoder.encode(
            this,
            Charsets.UTF_8.name(),
        )
    }
}
