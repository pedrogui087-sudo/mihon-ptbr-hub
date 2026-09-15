import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Mihon PT-BR Hub PHERME"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "MangaDex PHERME"
        baseUrl = "https://api.mangadex.org"
        lang = "pt-BR"
    }

    source {
        name = "ChapterCodex PHERME"
        baseUrl = "https://chaptercodex.com"
        lang = "pt-BR"
    }

    source {
        name = "ManhuaRMTL PHERME"
        baseUrl = "https://manhuarmtl.com"
        lang = "en"
    }

    source {
        name = "MangaFire PHERME"
        baseUrl = "https://mangafire.to"
        lang = "all"
    }

    deeplink {
        host("mangadex.org")
        path("/title/..*")
        path("/chapter/..*")
    }

    deeplink {
        host("chaptercodex.com")
        path("/pt/manga/..*")
    }

    deeplink {
        host("manhuarmtl.com")
        path("/manga/..*")
    }

    deeplink {
        host("mangafire.to")
        path("/manga/..*")
    }
}
