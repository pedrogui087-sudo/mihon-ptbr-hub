# Mihon PT-BR Hub

Projeto para uma extensão PT-BR do Mihon, usando o padrão atual `KeiSource`.

## O que já está preparado
- módulo PT-BR (`src/pt/mihonptbrhub`)
- fonte baseada na API pública do MangaDex, filtrando traduções `pt-br`
- atualização automática por consulta ao site/API: novos capítulos não exigem nova APK
- workflow do GitHub Actions que baixa o `keiyoushi/extensions-source` atual e compila o módulo
- catálogo de aliases dos 14 títulos para facilitar a busca

## Limitação importante
Esta máquina não tem acesso de rede ao GitHub/Gradle e não possui o ambiente Android/Gradle do projeto upstream. Portanto eu NÃO posso afirmar que a APK foi compilada e testada aqui. O workflow faz a compilação remotamente no GitHub Actions; se a API upstream mudar, o próprio build mostrará o erro e poderemos corrigir.

Também não incluí WebComics como fonte automática: os termos públicos do serviço proíbem sistemas automatizados/offline readers. Não vou contornar essa restrição.

## Como o projeto funciona
O módulo usa `KeiSource`/`libVersion 1.6`. O GitHub Actions faz checkout do projeto oficial de extensões, copia este módulo para ele e executa o Gradle. Isso evita mandar para você um falso projeto "completo" contendo arquivos upstream desatualizados.

## Depois do build
O workflow publica a APK como artefato do GitHub Actions. A instalação manual da APK é o primeiro teste. Depois de confirmar que a extensão funciona no seu Mihon, podemos configurar um repositório/index para instalação direta pelo Mihon.
