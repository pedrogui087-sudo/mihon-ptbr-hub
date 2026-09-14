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
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Source
abstract class MangaDexPtBr : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage = search("", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = search("", page)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = search(query, page)

    private suspend fun search(
        query: String,
        page: Int,
    ): MangasPage {
        val url = "$baseUrl/manga"

        val json = client.get(url)
            .parseAs<JsonElement>()
            .jsonObject

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
                this.url = "/manga/$id"
                this.description = attrs["description"]
                    ?.jsonObject
                    ?.values
                    ?.firstOrNull()
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                this.status = SManga.UNKNOWN
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
        val id = manga.url.substringAfterLast("/")

        val json = client.get(
            "$baseUrl/manga/$id",
        ).parseAs<JsonElement>().jsonObject

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
            "$baseUrl/chapter?manga=$id&translatedLanguage[]=pt-br&limit=100&order[chapter]=asc",
        ).parseAs<JsonElement>().jsonObject

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
                    this.url = "/chapter/$chapterId"
                    this.name = "Capítulo $number"
                    this.chapter_number = number.toFloatOrNull() ?: 0f
                    this.date_upload = chapterAttrs["publishAt"]
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
        val id = chapter.url.substringAfterLast("/")

        val json = client.get(
            "$baseUrl/at-home/server/$id",
        ).parseAs<JsonElement>().jsonObject

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
                imageUrl = "$base/data/$hash/$filename",
            )
        }
    }

    override fun getMangaUrl(manga: SManga): String {
        val id = manga.url.substringAfterLast("/")
        return "https://mangadex.org/title/$id"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val id = chapter.url.substringAfterLast("/")
        return "https://mangadex.org/chapter/$id"
    }

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
