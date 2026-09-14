import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Mihon PT-BR Hub"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "MangaDex PT-BR"
        baseUrl = "https://api.mangadex.org"
        lang = "pt-BR"
    }

    deeplink {
        path("/manga/.*")
    }
}
