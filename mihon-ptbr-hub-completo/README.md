# Mihon PT-BR Hub PHERME

Projeto pessoal de extensão para Mihon, usando o framework atual Keiyoushi/KeiSource 1.6.

Fontes declaradas nesta versão:
- MangaDex PHERME (PT-BR)
- ChapterCodex PHERME (PT-BR)
- ManhuaRMTL PHERME (EN)
- MangaFire PHERME (all)

A MangaDex usa a API oficial e inclui capa via relacionamento `cover_art`. As fontes HTML usam seletores comuns de sites de mangá; podem precisar de manutenção quando os sites mudarem.

## Build

O workflow em `.github/workflows/build.yml` usa a versão atual do `keiyoushi/extensions-source` como framework e copia este módulo para o upstream antes de compilar.

Importante: o APK é uma extensão manual/não confiável até ser adicionado a um repositório confiável. O Mihon documenta que extensões de terceiros podem exigir instalação manual e recomenda cautela com fontes de terceiros.
