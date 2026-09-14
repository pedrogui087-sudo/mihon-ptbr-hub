package eu.kanade.tachiyomi.extension.pt.mihonptbrhub

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class MangaDexPtBr : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage =
        search("", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        search("", page)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = search(query, page)

    private suspend fun search(
        query: String,
        page: Int,
    ): MangasPage {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "20")
            .addQueryParameter("offset", ((page - 1) * 20).toString())
            .addQueryParameter("availableTranslatedLanguage[]", "pt-br")
            .addQueryParameter("contentRating[]", "safe")
            .addQueryParameter("contentRating[]", "suggestive")
            .addQueryParameter("order[latestUploadedChapter]", "desc")
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("title", query)
                }
            }
            .build()

        val json = client.get(url).parseAs<JsonObject>()
        val data = json["data"]?.jsonArray.orEmpty()
        val total = json["total"]?.jsonPrimitive?.intOrNull ?: data.size

        val mangas = data.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content
                ?: return@mapNotNull null
            val attrs = obj["attributes"]?.jsonObject
                ?: return@mapNotNull null

            val title = firstLocalized(
                attrs["title"]?.jsonObject,
                "pt-br",
                "en",
            ) ?: id

            SManga.create().apply {
                this.title = title
                url = "/manga/$id"
                description = attrs["description"]
                    ?.jsonObject
                    ?.values
                    ?.firstOrNull()
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                status = SManga.UNKNOWN
            }
        }

        return MangasPage(
            mangas,
            page * 20 < total,
        )
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.substringAfterLast('/')
        val url = "$baseUrl/manga/$id".toHttpUrl()
        val json = client.get(url).parseAs<JsonObject>()

        val obj = json["data"]?.jsonObject
            ?: return SMangaUpdate(manga, chapters)

        val attrs = obj["attributes"]?.jsonObject
            ?: return SMangaUpdate(manga, chapters)

        if (fetchDetails) {
            manga.title = firstLocalized(
                attrs["title"]?.jsonObject,
                "pt-br",
                "en",
            ) ?: manga.title

            manga.description = attrs["description"]
                ?.jsonObject
                ?.values
                ?.firstOrNull()
                ?.jsonPrimitive
                ?.content
                .orEmpty()
        }

        if (!fetchChapters) {
            return SMangaUpdate(manga, chapters)
        }

        val chapterJson = client.get(
            "$baseUrl/chapter".toHttpUrl().newBuilder()
                .addQueryParameter("manga", id)
                .addQueryParameter("translatedLanguage[]", "pt-br")
                .addQueryParameter("limit", "100")
                .addQueryParameter("order[chapter]", "asc")
                .build(),
        ).parseAs<JsonObject>()

        val list = chapterJson["data"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { raw ->
                val chapter = raw.jsonObject
                val chapterAttrs = chapter["attributes"]?.jsonObject
                    ?: return@mapNotNull null

                val chapterId = chapter["id"]?.jsonPrimitive?.content
                    ?: return@mapNotNull null

                val number = chapterAttrs["chapter"]
                    ?.jsonPrimitive
                    ?.content
                    ?: return@mapNotNull null

                SChapter.create().apply {
                    url = "/chapter/$chapterId"
                    name = "Capítulo $number"
                    chapter_number = number.toFloatOrNull() ?: 0f
                    date_upload = chapterAttrs["publishAt"]
                        ?.jsonPrimitive
                        ?.content
                        ?.let {
                            runCatching {
                                java.time.Instant.parse(it).toEpochMilli()
                            }.getOrDefault(0L)
                        }
                        ?: 0L
                }
            }

        return SMangaUpdate(manga, list)
    }

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {
        val id = chapter.url.substringAfterLast('/')
        val json = client.get(
            "$baseUrl/at-home/server/$id".toHttpUrl(),
        ).parseAs<JsonObject>()

        val base = json["baseUrl"]
            ?.jsonPrimitive
            ?.content
            ?: return emptyList()

        val chapterObj = json["chapter"]?.jsonObject
            ?: return emptyList()

        val hash = chapterObj["hash"]
            ?.jsonPrimitive
            ?.content
            ?: return emptyList()

        val pages = chapterObj["data"]
            ?.jsonArray
            .orEmpty()
            .map { it.jsonPrimitive.content }

        return pages.mapIndexed { index, filename ->
            Page(
                index,
                "$base/data/$hash/$filename",
            )
        }
    }

    override fun getMangaUrl(
        manga: SManga,
    ): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(
        chapter: SChapter,
    ): String = "$baseUrl${chapter.url}"

    private fun firstLocalized(
        obj: JsonObject?,
        vararg keys: String,
    ): String? {
        if (obj == null) {
            return null
        }

        for (key in keys) {
            val value = obj[key]?.jsonPrimitive?.content
            if (!value.isNullOrBlank()) {
                return value
            }
        }

        return obj.values
            .firstOrNull()
            ?.jsonPrimitive
            ?.content
    }
}
