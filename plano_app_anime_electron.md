# Projeto de aplicativo Electron para catálogo, download e reprodução de anime

## 1. Visão geral

O projeto será um aplicativo desktop semelhante ao Stremio na experiência de descoberta e reprodução, mas inteiramente orientado a anime.

O aplicativo reunirá em uma única interface:

- catálogo visual de animes;
- busca por nome, sinônimos e título japonês;
- temporadas, episódios, especiais, OVAs e filmes;
- pesquisa de releases em provedores BitTorrent configuráveis;
- suporte opcional ao Nyaa como provedor de pesquisa;
- comparação automática de qualidade dos releases;
- download completo ou reprodução enquanto baixa;
- legendas internas, externas e pesquisadas automaticamente;
- histórico, progresso, biblioteca e lista de acompanhamento;
- gerenciamento de downloads e armazenamento local;
- integração opcional com AniList;
- funcionamento local, sem depender de um servidor central do projeto.

Nome provisório usado neste documento: **Kitsune**.

> O aplicativo não deve hospedar arquivos de mídia nem contornar bloqueios, DRM ou mecanismos de acesso. Provedores de torrent devem ser usados somente para conteúdo cuja obtenção e compartilhamento sejam permitidos ao usuário. A arquitetura será genérica para também aceitar torrents legais, arquivos locais e fontes autorizadas.

---

## 2. Objetivos do produto

### 2.1 Objetivo principal

Permitir que o usuário encontre um anime, escolha um episódio e chegue à reprodução com o mínimo possível de etapas manuais.

Fluxo ideal:

1. abrir o aplicativo;
2. selecionar um anime;
3. escolher o episódio;
4. o aplicativo localizar releases compatíveis;
5. selecionar automaticamente o melhor release ou mostrar opções;
6. localizar legenda em português quando necessária;
7. iniciar a reprodução;
8. registrar o progresso automaticamente.

### 2.2 Princípios

- **Anime em primeiro lugar:** temporadas, OVAs, especiais, filmes, episódios duplos e numeração absoluta devem ser tratados corretamente.
- **Local-first:** banco, cache, biblioteca, progresso e configurações ficam no computador do usuário.
- **Provedores desacoplados:** catálogo, torrents, legendas e tracking não devem depender de uma única API.
- **Baixo consumo:** interface leve, carregamento progressivo, cache agressivo e poucas chamadas externas.
- **Controle do usuário:** toda escolha automática deve poder ser revisada ou desativada.
- **Sem servidor obrigatório:** o aplicativo deve funcionar sem conta própria e sem backend central.
- **Recuperação de falhas:** se uma API parar, o restante do aplicativo continua funcionando.

---

## 3. Plataforma inicial

### 3.1 Primeira plataforma

Prioridade inicial:

1. Windows 10 e 11;
2. Linux;
3. macOS em fase posterior.

Android e televisão não devem fazer parte do primeiro ciclo porque BitTorrent, armazenamento, codecs, background e distribuição nas lojas tornam o escopo muito maior.

### 3.2 Formato de distribuição

- instalador `.exe` para Windows;
- versão portátil `.exe` sem instalação;
- AppImage ou Flatpak para Linux;
- atualizador automático opcional;
- modo sem instalação para testes.

---

## 4. Stack recomendada

### 4.1 Aplicativo

| Camada | Tecnologia sugerida | Motivo |
|---|---|---|
| Shell desktop | Electron | Desenvolvimento somente com JavaScript/TypeScript e distribuição em `.exe` |
| Interface | React + TypeScript | Ecossistema amplo, tipagem e componentes reutilizáveis |
| Build da interface | Vite | Inicialização e build rápidos |
| Processo principal | Node.js + TypeScript | Arquivos, banco, processos externos, rede e integração nativa |
| Bridge segura | Electron preload + `contextBridge` | Expõe apenas operações permitidas ao renderer |
| IPC | `ipcMain.handle` + `ipcRenderer.invoke` | Chamadas tipadas entre interface e processo principal |
| Estado da interface | Zustand | Estado local simples e pouco verboso |
| Dados assíncronos | TanStack Query | Cache, retries, invalidação e deduplicação |
| Validação | Zod | Validar payloads de IPC, configurações e respostas externas |
| Estilo | Tailwind CSS | Desenvolvimento rápido e consistente |
| Componentes | Radix UI ou shadcn/ui | Componentes reutilizáveis e acessíveis |
| Banco local | SQLite com `better-sqlite3` | Banco local rápido, síncrono e simples de distribuir |
| Migrations | Drizzle Kit ou migrations SQL próprias | Evolução controlada do banco |
| Empacotamento | electron-builder | Instalador NSIS, `.exe` portátil, assinatura e atualização |
| Player | mpv executado como processo filho | Excelente suporte a MKV, ASS/SSA, HEVC, AV1 e áudio múltiplo |
| Inspeção de mídia | FFmpeg e ffprobe | Detectar codecs, faixas, duração, capítulos e gerar thumbnails |
| Torrent no MVP | qBittorrent via Web API local | Evita construir um cliente BitTorrent próprio imediatamente |
| Torrent avançado | serviço separado baseado em libtorrent | Priorização de peças, streaming e seek mais precisos |

### 4.2 Por que Electron neste projeto

Electron é a escolha mais prática porque o projeto depende de várias integrações locais:

- iniciar e controlar mpv;
- executar FFmpeg e ffprobe;
- controlar qBittorrent;
- ler e organizar arquivos grandes;
- observar pastas da biblioteca;
- manter banco SQLite;
- criar instalador e versão portátil para Windows.

O desenvolvimento exige apenas Node.js LTS, Git e um editor. Rust, Cargo e toolchain C++ não fazem parte da base do projeto.

O usuário final não instala Node.js. O runtime do Electron e todos os arquivos necessários ficam dentro do instalador.

### 4.3 Motivo para não usar somente o player HTML

Anime é frequentemente distribuído em MKV com:

- vídeo HEVC ou AV1;
- áudio FLAC, Opus, AAC ou AC3;
- múltiplas faixas de áudio;
- capítulos;
- legendas ASS/SSA estilizadas;
- fontes anexadas ao container;
- profundidade de cor de 10 bits.

Um elemento HTML `<video>` não oferece suporte consistente para esse conjunto. O mpv deve ser o mecanismo principal.

### 4.4 Estratégia de integração com o mpv

#### MVP

Executar o mpv como processo filho pelo processo principal do Electron e controlá-lo por JSON IPC.

Fluxo:

```text
React renderer
    ↓ IPC validado
Electron main
    ↓ spawn com argumentos fixos
mpv
    ↕ JSON IPC por named pipe/socket local
Electron main
    ↓ eventos normalizados
React renderer
```

Vantagens:

- integração simples;
- atualizações independentes;
- estabilidade;
- nenhum acesso do renderer ao shell;
- suporte imediato aos formatos comuns de anime.

#### Versão posterior

Avaliar uma janela de player dedicada, transparente ou sem moldura, sincronizada com a janela principal. Embutir `libmpv` diretamente só deve ser considerado se a integração visual justificar a complexidade e os módulos nativos adicionais.

### 4.5 Ambiente de desenvolvimento

Requisitos mínimos no computador do desenvolvedor:

```text
Node.js LTS
Git
VS Code ou outro editor
npm, pnpm ou yarn
```

Comandos esperados:

```bash
pnpm install
pnpm dev
pnpm build:win
pnpm build:portable
```

O repositório deve incluir scripts para baixar ou validar os binários de desenvolvimento de mpv, FFmpeg e qBittorrent, evitando configuração manual de PATH.

### 4.6 Distribuição plug and play

O pacote final para Windows deve oferecer:

- `Kitsune-Setup-x64.exe`: instalador NSIS;
- `Kitsune-Portable-x64.exe`: executável portátil;
- atualização automática opcional para a versão instalada;
- primeiro início com detecção automática dos componentes;
- configuração guiada somente quando algo realmente precisar da decisão do usuário.

Estrutura dos recursos empacotados:

```text
resources/
├── app.asar
├── bin/
│   ├── mpv/
│   ├── ffmpeg/
│   └── qbittorrent/
├── licenses/
└── migrations/
```

Os binários redistribuídos devem ter licença, avisos e código-fonte ou links exigidos por suas respectivas licenças. Caso a redistribuição de determinado build seja inconveniente, o instalador pode detectar uma instalação existente ou oferecer download explícito do componente.

---

## 5. Arquitetura geral

```text
┌──────────────────────────────────────────────────────────────┐
│                    Renderer React                            │
│ Home · Busca · Anime · Episódio · Downloads · Configurações │
│ Sem Node.js, sem filesystem e sem execução de processos      │
└──────────────────────────────┬───────────────────────────────┘
                               │ API tipada exposta pelo preload
┌──────────────────────────────▼───────────────────────────────┐
│                    Preload isolado                           │
│ contextBridge · validação superficial · subscriptions        │
└──────────────────────────────┬───────────────────────────────┘
                               │ IPC invoke/event
┌──────────────────────────────▼───────────────────────────────┐
│                Processo principal Electron                   │
│                                                              │
│ CatalogService        ReleaseService       SubtitleService   │
│ LibraryService        PlaybackService      DownloadService   │
│ MatchEngine           TrackingService      CacheService      │
│ SettingsService       UpdateService        Diagnostics       │
└───────────────┬──────────────────┬──────────────────┬─────────┘
                │                  │                  │
       ┌────────▼────────┐ ┌───────▼────────┐ ┌──────▼─────────┐
       │ SQLite + cache  │ │ qBittorrent    │ │ mpv + FFmpeg  │
       └─────────────────┘ └────────────────┘ └────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────┐
│                        Provedores                            │
│ AniList · Jikan · Kitsu · TMDB · Release · OpenSubtitles   │
└──────────────────────────────────────────────────────────────┘
```

### 5.1 Processos

#### Renderer

Responsável apenas por:

- interface;
- navegação;
- formulários;
- renderização de estado;
- cache de consulta da interface;
- envio de comandos de alto nível.

Não pode acessar diretamente:

- `fs`;
- `child_process`;
- SQLite;
- tokens;
- caminhos internos;
- portas administrativas;
- APIs completas do Electron.

#### Preload

Expõe uma API mínima e tipada:

```ts
interface KitsuneDesktopApi {
  catalog: CatalogApi;
  releases: ReleaseApi;
  downloads: DownloadApi;
  playback: PlaybackApi;
  subtitles: SubtitleApi;
  library: LibraryApi;
  settings: SettingsApi;
  events: EventApi;
}
```

O preload não expõe `ipcRenderer` bruto. Cada função possui canal, payload e resposta conhecidos.

#### Processo principal

Responsável por:

- banco local;
- filesystem;
- chamadas externas que exigem segredos;
- processos mpv, FFmpeg e qBittorrent;
- menus, tray, notificações e atalhos globais;
- atualizações;
- IPC;
- encerramento e recuperação de sessão.

#### Workers e processos auxiliares

Tarefas pesadas não devem bloquear o processo principal:

- hash de arquivos;
- parsing em lote;
- geração de thumbnails;
- varredura de bibliotecas grandes;
- sincronização de milhares de registros.

Usar `worker_threads`, filas internas ou processos auxiliares conforme a carga.

### 5.2 Módulos principais

- `catalog`: catálogo, metadados e imagens;
- `release`: pesquisa e normalização de releases;
- `matcher`: associação entre anime, episódio e arquivo;
- `torrent`: downloads e streaming;
- `media`: inspeção de arquivos e geração de thumbnails;
- `subtitle`: legendas internas, locais e remotas;
- `player`: controle do mpv;
- `library`: biblioteca local e organização de arquivos;
- `tracking`: progresso e integração com serviços externos;
- `storage`: SQLite, cache e migrations;
- `settings`: preferências e perfis;
- `plugins`: provedores adicionais instaláveis;
- `desktop`: janelas, tray, atalhos e atualizações;
- `diagnostics`: logs, integridade dos binários e relatório de falhas.

---

## 6. Fontes de metadados e thumbnails

### 6.1 Provedor principal: AniList

O AniList deve ser a principal fonte de:

- título romaji;
- título em inglês;
- título nativo;
- sinônimos;
- capa em alta resolução;
- banner;
- descrição;
- gêneros;
- tags;
- formato;
- temporada;
- ano;
- quantidade de episódios;
- duração média;
- status de lançamento;
- data e horário de exibição;
- personagens e estúdio;
- relações entre temporadas, sequências, prequelas, especiais e filmes;
- ID correspondente do MyAnimeList, quando disponível.

A aplicação deve armazenar os dados localmente e atualizar somente quando necessário.

### 6.2 Fallback de metadados

Ordem sugerida:

1. AniList;
2. Jikan/MyAnimeList;
3. Kitsu;
4. dados locais previamente salvos.

O fallback não deve substituir silenciosamente dados melhores. Cada campo pode guardar sua origem.

Exemplo:

```json
{
  "title": {
    "value": "Sousou no Frieren",
    "source": "anilist"
  },
  "episode_count": {
    "value": 28,
    "source": "anilist"
  }
}
```

### 6.3 Capas e banners

Para a página do anime:

- capa vertical: AniList `coverImage.extraLarge`;
- banner horizontal: AniList `bannerImage`;
- fallback: Kitsu ou TMDB;
- fallback final: gradiente gerado com título.

As imagens devem ser armazenadas em cache WebP ou AVIF em vários tamanhos:

- 160 px para listas pequenas;
- 320 px para cards;
- 600 px para detalhes;
- original somente quando necessário.

### 6.4 Thumbnails de episódios

O AniList normalmente não é uma fonte completa de imagens por episódio. A estratégia deve ser:

1. procurar still do episódio no TMDB;
2. procurar imagem equivalente em outro provedor configurado;
3. após parte suficiente do arquivo estar disponível, gerar thumbnail local com FFmpeg;
4. permitir que o usuário troque a imagem manualmente;
5. reutilizar o frame gerado em futuras execuções.

Heurística para thumbnail local:

- ignorar abertura inicial quando possível;
- amostrar frames entre 15% e 75% do episódio;
- evitar frames pretos ou com pouco contraste;
- evitar frame dominado por texto;
- preferir frame com maior variação visual;
- salvar em WebP, 640×360.

### 6.5 Mapeamento entre bancos

Criar uma tabela interna de IDs:

```text
anime_id interno
├── anilist_id
├── mal_id
├── kitsu_id
├── tmdb_id
├── anidb_id opcional
└── aliases normalizados
```

O ID interno não deve depender de um provedor externo.

---

## 7. Provedor de releases BitTorrent

### 7.1 Interface genérica

Todos os provedores devem implementar a mesma interface:

```ts
interface ReleaseProvider {
  id: string;
  name: string;
  capabilities(): ProviderCapabilities;
  search(input: ReleaseSearchInput): Promise<ReleaseCandidate[]>;
  resolve(candidate: ReleaseCandidate): Promise<ResolvedRelease>;
  healthCheck(): Promise<ProviderHealth>;
}
```

### 7.2 NyaaProvider

O adaptador do Nyaa deve ficar isolado em um plugin/provedor próprio.

Responsabilidades:

- receber uma pesquisa já normalizada;
- consultar somente os filtros configurados pelo usuário;
- interpretar resultados;
- converter o resultado para o modelo interno;
- registrar seeds, leechers, tamanho, data, categoria e uploader;
- identificar links magnet ou arquivos `.torrent` disponíveis;
- respeitar cache, limites e erros;
- não executar bypass de bloqueios, CAPTCHA ou proteção do site.

O provedor deve poder ser:

- ativado ou desativado;
- atualizado sem recompilar o aplicativo inteiro;
- substituído por outro provedor;
- limitado a feeds ou pesquisas definidos pelo usuário.

### 7.3 Modelo normalizado de release

```ts
interface ReleaseCandidate {
  providerId: string;
  providerItemId: string;
  title: string;
  detailsUrl?: string;
  magnetUri?: string;
  torrentUrl?: string;
  publishedAt?: string;
  sizeBytes?: number;
  seeders?: number;
  leechers?: number;
  completed?: number;
  uploader?: string;
  trusted?: boolean;
  remake?: boolean;
  category?: string;
  parsed: ParsedRelease;
}
```

```ts
interface ParsedRelease {
  animeTitle?: string;
  season?: number;
  episodeStart?: number;
  episodeEnd?: number;
  absoluteEpisode?: number;
  batch?: boolean;
  resolution?: number;
  source?: "WEB" | "WEB-DL" | "BluRay" | "TV" | "DVD" | "Unknown";
  videoCodec?: "AV1" | "HEVC" | "H264" | "Unknown";
  bitDepth?: 8 | 10 | 12;
  audioCodec?: string;
  audioLanguages: string[];
  subtitleLanguages: string[];
  fansubGroup?: string;
  dualAudio?: boolean;
  uncensored?: boolean;
  checksum?: string;
}
```

### 7.4 Parser de nomes de release

O parser deve entender exemplos como:

```text
[Grupo] Anime Name - 07 [1080p][HEVC 10bit][AAC][PT-BR].mkv
Anime.Name.S02E03.1080p.WEB-DL.x265.mkv
[Grupo] Anime Name [01-12][BD 1080p][FLAC]
Anime Name - 13v2 [1920x1080]
Anime Name OVA 01
Anime Name - SP01
```

Etapas:

1. remover extensão;
2. detectar grupo entre colchetes;
3. extrair tags técnicas;
4. identificar temporada e episódio;
5. diferenciar resolução de número de episódio;
6. detectar batch;
7. normalizar título;
8. comparar com aliases do anime;
9. gerar nível de confiança.

Pode ser usada uma biblioteca como Anitomy, com regras próprias para casos brasileiros e releases recentes.

### 7.5 Geração da consulta

O mecanismo de busca deve gerar poucas consultas de alta qualidade, em vez de pesquisar todos os aliases indiscriminadamente.

Prioridade:

1. título romaji principal;
2. título em inglês, quando muito diferente;
3. título nativo somente quando necessário;
4. sinônimo reconhecido nos releases;
5. número absoluto e número de temporada, conforme o anime.

Exemplo conceitual:

```text
Sousou no Frieren 07
Frieren Beyond Journey's End 07
Sousou no Frieren S01E07
```

O sistema deve interromper novas consultas quando já houver candidatos suficientes e confiáveis.

---

## 8. Motor de seleção automática de release

### 8.1 Preferências configuráveis

O usuário poderá ordenar suas preferências:

- resolução: 2160p, 1080p, 720p;
- codec: AV1, HEVC, H.264;
- fonte: Blu-ray, WEB-DL, WEB, TV;
- áudio original ou dual audio;
- legenda PT-BR embutida;
- tamanho máximo por episódio;
- grupos preferidos;
- grupos bloqueados;
- exigir uploader confiável;
- evitar remakes;
- mínimo de seeders;
- preferência por batch ou episódios individuais.

### 8.2 Pontuação sugerida

```text
score =
  confiança_do_título        × 35
+ correspondência_episódio   × 30
+ preferência_resolução      × 12
+ preferência_fonte          × 8
+ preferência_codec          × 5
+ disponibilidade_legenda    × 5
+ reputação_do_uploader      × 3
+ saúde_do_torrent           × 2
- penalidade_remake
- penalidade_tamanho
- penalidade_idade_sem_seeds
- penalidade_de_ambiguidade
```

O aplicativo deve mostrar uma explicação legível:

```text
Escolhido porque:
- episódio corresponde exatamente;
- título reconhecido com 99% de confiança;
- 1080p WEB-DL;
- HEVC 10-bit;
- possui legenda PT-BR;
- 48 seeders;
- grupo marcado como preferido.
```

### 8.3 Modos de seleção

- **Automático:** abre o melhor resultado.
- **Perguntar sempre:** mostra a lista ordenada.
- **Automático com confirmação:** pré-seleciona e aguarda um clique.
- **Somente regras:** baixa apenas quando todas as condições forem atendidas.

---

## 9. Torrents e downloads

### 9.1 Fase 1: integração com qBittorrent

O MVP deve controlar uma instalação local do qBittorrent ou uma instância empacotada.

Funções necessárias:

- adicionar magnet ou `.torrent`;
- selecionar arquivos;
- pausar e retomar;
- remover torrent;
- remover torrent e dados;
- consultar progresso;
- consultar velocidade;
- consultar seeders e peers;
- alterar prioridade de arquivos;
- ativar download sequencial;
- priorizar primeira e última peça;
- limitar velocidade;
- definir pasta de destino;
- aplicar categoria e tags;
- detectar conclusão;
- importar torrents existentes.

Tags sugeridas:

```text
kitsune
anime:<internal_id>
episode:<number>
season:<number>
quality:1080p
state:watching
```

### 9.2 Fase 2: serviço próprio de streaming BitTorrent

Depois que o produto estiver estável, criar um serviço local baseado em libtorrent.

Responsabilidades:

- controle de sessão BitTorrent;
- DHT, trackers e PEX;
- seleção de arquivos;
- prioridade por peças;
- buffer inicial;
- buffer móvel em torno da posição atual;
- reordenação de peças ao buscar outro ponto do vídeo;
- endpoint HTTP local para o player;
- verificação de hashes;
- limites de upload e download;
- persistência de sessão;
- retomada após reiniciar o aplicativo.

### 9.3 Estratégia de buffer

O buffer não deve ser um número fixo em megabytes. Deve considerar o bitrate estimado.

```text
buffer_inicial_segundos = 45
buffer_seek_segundos = 20
buffer_seguro_segundos = 90
```

Exemplo:

```text
bitrate estimado: 8 Mbps
buffer inicial: 45 s
bytes necessários: aproximadamente 45 MB
```

O player começa quando:

- cabeçalho e metadados do arquivo estão disponíveis;
- primeira região necessária foi verificada;
- buffer inicial atingiu o mínimo;
- taxa média de download é suficiente ou o usuário força a reprodução.

### 9.4 Busca durante streaming

Ao mover o player para outro ponto:

1. receber o novo timestamp do mpv;
2. converter timestamp em intervalo aproximado de bytes;
3. mapear bytes para peças do torrent;
4. elevar prioridade das peças próximas;
5. manter pequena margem antes e depois;
6. reduzir prioridade da região antiga;
7. pausar o player até o novo buffer estar pronto, quando necessário.

### 9.5 Seleção de arquivos em batches

Quando um torrent possui uma temporada inteira:

- analisar a lista de arquivos;
- mapear cada arquivo a um episódio;
- marcar somente o episódio atual como prioridade máxima;
- permitir baixar os próximos episódios em baixa prioridade;
- ignorar extras, NCOP e NCED por padrão;
- permitir selecionar extras manualmente.

### 9.6 Política de armazenamento

Modos:

- **Streaming temporário:** apagar automaticamente depois de assistir;
- **Manter episódio:** mover para biblioteca;
- **Manter temporada:** preservar todos os arquivos;
- **Perguntar ao finalizar:** mostrar decisão;
- **Somente download:** não abrir o player.

### 9.7 Limpeza automática

Regras possíveis:

- apagar episódios concluídos após X dias;
- manter os últimos X episódios;
- nunca apagar favoritos;
- nunca apagar arquivos sem backup;
- limpar cache quando exceder limite;
- preservar torrents incompletos;
- mostrar simulação antes de uma limpeza grande.

---

## 10. Legendas

### 10.1 Ordem de prioridade

1. faixa PT-BR embutida no arquivo;
2. legenda PT-BR na mesma pasta e com nome correspondente;
3. legenda previamente escolhida pelo usuário;
4. OpenSubtitles com credenciais/API do usuário;
5. outros plugins de legenda autorizados;
6. legenda em português de Portugal;
7. inglês;
8. nenhuma legenda.

### 10.2 Inspeção das faixas embutidas

Usar `ffprobe` ou propriedades do mpv para detectar:

- idioma;
- título da faixa;
- formato;
- flags default e forced;
- sinais de PT-BR, `por`, `pt-BR`, `Brazilian Portuguese`, `Português Brasil`;
- legendas de sinais e músicas;
- legendas completas.

### 10.3 Pesquisa remota

A pesquisa de legendas deve usar, em ordem:

- hash do arquivo, quando suportado;
- tamanho do arquivo;
- ID do episódio no provedor;
- nome do anime;
- temporada e episódio;
- release group;
- duração aproximada;
- FPS.

A legenda deve receber uma pontuação de sincronização provável.

### 10.4 Sincronização

Ferramentas no player:

- atraso/adiantamento em passos de 50 ms;
- salvar offset por arquivo;
- offset automático baseado em pontos de diálogo;
- trocar FPS da legenda quando necessário;
- escolher encoding;
- converter SRT para UTF-8;
- preservar ASS/SSA sem destruir estilos.

### 10.5 Legenda automática por IA

Feature futura e opcional:

- extrair áudio;
- transcrever localmente;
- traduzir para PT-BR;
- gerar SRT/ASS;
- marcar claramente como legenda automática;
- nunca enviar áudio para serviço remoto sem consentimento explícito.

Essa função deve ser plugin separado para não aumentar o instalador principal.

---

## 11. Player

### 11.1 Controles básicos

- play e pause;
- buscar na timeline;
- volume;
- velocidade;
- tela cheia;
- sempre no topo;
- escolher áudio;
- escolher legenda;
- atraso de áudio e legenda;
- capítulos;
- próximo episódio;
- episódio anterior;
- picture-in-picture quando suportado;
- screenshot;
- estatísticas técnicas opcionais.

### 11.2 Recursos específicos para anime

- pular abertura;
- pular encerramento;
- pular recapitulação;
- detectar preview do próximo episódio;
- auto-play do próximo episódio;
- preservar idioma e faixa preferida por grupo;
- perfis de shader para anime;
- controle de banding;
- upscaling opcional;
- redução de judder sem interpolação obrigatória;
- capítulos nomeados;
- exibir nome do fansub e release.

### 11.3 Detecção de abertura e encerramento

Fases:

1. usar capítulos existentes no MKV;
2. armazenar marcações comunitárias de plugins autorizados;
3. detectar duração semelhante entre episódios locais;
4. fingerprint de áudio opcional;
5. marcação manual pelo usuário.

O botão de pular só aparece quando a confiança ultrapassar um limite.

### 11.4 Progresso

Salvar a cada 10 segundos ou em eventos importantes:

```text
anime_id
season
episode
file_hash
position_seconds
duration_seconds
completed
updated_at
```

Critério de conclusão padrão:

```text
posição >= 90% da duração
OU
restante <= 120 segundos
```

Tudo deve ser configurável.

### 11.5 Reprodução contínua

Ao chegar perto do final:

- pesquisar o próximo episódio em background local;
- resolver release;
- iniciar download/buffer;
- mostrar contador;
- permitir cancelar;
- evitar iniciar se o episódio seguinte não foi confirmado corretamente.

---

## 12. Interface e experiência de uso

### 12.1 Navegação principal

Barra lateral:

- Início;
- Descobrir;
- Minha lista;
- Biblioteca;
- Downloads;
- Histórico;
- Calendário;
- Configurações.

Barra superior:

- voltar e avançar;
- pesquisa global;
- status de download;
- botão de conexão/provedores;
- perfil local/AniList.

### 12.2 Tela inicial

Seções:

- Continuar assistindo;
- Próximos episódios;
- Em exibição nesta temporada;
- Recentemente adicionados;
- Minha lista;
- Downloads concluídos;
- Recomendações baseadas em gêneros e histórico local.

Não carregar todas as seções de uma vez. Priorizar conteúdo visível e usar carregamento virtualizado.

### 12.3 Tela de anime

Cabeçalho:

- banner;
- capa;
- título;
- ano, temporada e formato;
- nota;
- status;
- gêneros;
- botões `Assistir`, `Adicionar à lista` e `Baixar`.

Conteúdo:

- sinopse;
- episódios;
- relações;
- personagens;
- trailers autorizados;
- informações técnicas;
- releases encontrados;
- atividade do usuário.

### 12.4 Lista de episódios

Cada episódio mostra:

- número;
- título, quando disponível;
- thumbnail;
- data de exibição;
- duração;
- status assistido;
- progresso;
- disponibilidade local;
- estado de download;
- melhor release encontrado;
- botão de opções.

A lista deve suportar:

- grade ou lista;
- busca por número;
- marcar vários como assistidos;
- baixar intervalo;
- corrigir numeração;
- associar arquivo manualmente.

### 12.5 Tela de seleção de release

Colunas úteis:

- grupo;
- resolução;
- codec;
- fonte;
- áudio;
- legenda;
- tamanho;
- seeders;
- idade;
- confiança;
- pontuação final.

Filtros rápidos:

- 1080p;
- PT-BR;
- dual audio;
- HEVC;
- AV1;
- confiável;
- não remake;
- tamanho máximo.

### 12.6 Downloads

Separar em:

- ativos;
- aguardando;
- concluídos;
- pausados;
- com erro;
- em seed.

Cada item mostra:

- progresso geral;
- arquivo em reprodução;
- velocidade;
- ETA;
- peers;
- seeders;
- proporção;
- estado do buffer;
- pasta;
- ações rápidas.

### 12.7 Modo compacto

Janela pequena com:

- episódio atual;
- progresso;
- downloads;
- próximo episódio;
- controles do player.

### 12.8 Temas

- escuro padrão;
- OLED;
- claro;
- usar cores da capa do anime;
- reduzir animações;
- densidade compacta ou confortável.

---

## 13. Biblioteca local

### 13.1 Importação

Permitir adicionar uma ou mais pastas.

O scanner:

1. encontra arquivos de vídeo;
2. ignora arquivos temporários;
3. analisa nomes;
4. calcula hash parcial e tamanho;
5. extrai duração, codec e faixas;
6. tenta identificar anime e episódio;
7. gera thumbnails;
8. apresenta ambiguidades para correção.

### 13.2 Organização de pastas

Modelo opcional:

```text
Anime/
└── Sousou no Frieren (2023)/
    ├── Season 01/
    │   ├── Sousou no Frieren - S01E01.mkv
    │   └── Sousou no Frieren - S01E02.mkv
    └── Extras/
```

O usuário pode escolher:

- não renomear;
- copiar;
- mover;
- hardlink;
- symlink;
- organização personalizada por template.

### 13.3 Templates

```text
{title_romaji} ({year})/Season {season:02}/{title_romaji} - S{season:02}E{episode:02} [{resolution}][{codec}]
```

### 13.4 Duplicatas

Detectar por:

- hash completo;
- hash parcial + tamanho;
- mesmo anime/episódio;
- duração semelhante;
- release diferente.

Nunca apagar duplicata automaticamente sem regra explícita.

---

## 14. Tracking e listas

### 14.1 Perfil local

Sem login, permitir:

- planejando assistir;
- assistindo;
- concluído;
- pausado;
- abandonado;
- repetir;
- nota;
- progresso;
- favoritos;
- tags pessoais.

### 14.2 Integração com AniList

Opcional:

- login OAuth;
- importar lista;
- atualizar progresso;
- atualizar status;
- atualizar nota;
- sincronizar alterações offline posteriormente;
- mostrar conflito antes de sobrescrever dados.

### 14.3 Estratégia de conflito

```text
local mais recente + remoto antigo -> enviar local
remoto mais recente + local antigo -> importar remoto
ambos alterados -> pedir decisão
```

Manter log de sincronização para desfazer erros.

---

## 15. Calendário e episódios novos

### 15.1 Calendário

Mostrar:

- episódios exibidos hoje;
- próximos sete dias;
- horário local;
- atraso conhecido;
- episódios da lista do usuário;
- disponibilidade de release separada da data de exibição.

### 15.2 Monitor local de releases

Regras opcionais:

```text
Quando aparecer episódio novo de um anime da minha lista:
- exigir 1080p;
- preferir grupo X;
- exigir pelo menos 5 seeders;
- baixar automaticamente;
- não baixar remake;
- notificar antes de iniciar.
```

O monitor deve funcionar em intervalo moderado, usar cache e evitar consultas excessivas.

### 15.3 Estados do episódio

```text
scheduled
 aired
 searching
 release_found
 downloading
 buffered
 available
 watched
 failed
```

---

## 16. Banco de dados

### 16.1 Tabelas principais

```text
anime
anime_titles
anime_relations
anime_external_ids
episodes
episode_images
providers
provider_cache
release_candidates
release_matches
torrents
torrent_files
download_jobs
library_roots
library_files
media_tracks
subtitles
watch_progress
watch_history
user_lists
sync_queue
settings
notifications
plugin_registry
```

### 16.2 Exemplo simplificado

```sql
CREATE TABLE anime (
    id TEXT PRIMARY KEY,
    anilist_id INTEGER UNIQUE,
    mal_id INTEGER,
    tmdb_id INTEGER,
    title_romaji TEXT NOT NULL,
    title_english TEXT,
    title_native TEXT,
    description TEXT,
    cover_url TEXT,
    banner_url TEXT,
    episode_count INTEGER,
    format TEXT,
    status TEXT,
    season TEXT,
    season_year INTEGER,
    metadata_updated_at TEXT NOT NULL
);
```

```sql
CREATE TABLE episodes (
    id TEXT PRIMARY KEY,
    anime_id TEXT NOT NULL,
    season_number INTEGER,
    episode_number REAL NOT NULL,
    absolute_number REAL,
    title TEXT,
    aired_at TEXT,
    duration_seconds INTEGER,
    thumbnail_path TEXT,
    FOREIGN KEY (anime_id) REFERENCES anime(id)
);
```

### 16.3 Cache

Tipos:

- memória: resultados usados na sessão;
- SQLite: metadados e pesquisas;
- disco: imagens, thumbnails e respostas grandes;
- cache negativo: pesquisas sem resultado por curto período.

TTLs sugeridos:

| Dado | TTL |
|---|---:|
| anime concluído | 30 dias |
| anime em exibição | 6 horas |
| calendário | 30 minutos |
| pesquisa de release recente | 5–15 minutos |
| pesquisa antiga sem resultado | 6 horas |
| capa/banner | 30 dias |
| saúde de provedor | 2 minutos |

---

## 17. API interna entre renderer e processo principal

### 17.1 Canais IPC

Usar preferencialmente `ipcRenderer.invoke` no preload e `ipcMain.handle` no processo principal.

```text
catalog:search
catalog:get-anime
catalog:get-episodes
catalog:refresh-anime

releases:search
releases:resolve
releases:explain-score

downloads:add
downloads:pause
downloads:resume
downloads:remove
downloads:set-priorities

playback:open
playback:pause
playback:seek
playback:select-audio
playback:select-subtitle
playback:close

library:scan
library:match-file
library:rename

subtitles:search
subtitles:download
subtitles:set-offset

tracking:update-progress
tracking:sync
```

### 17.2 Eventos do processo principal

```text
download:progress
download:state
playback:position
playback:tracks
playback:ended
provider:health
library:scan-progress
subtitle:found
sync:conflict
app:update-available
app:update-progress
```

### 17.3 Contrato tipado

Os tipos compartilhados ficam em `packages/contracts` e são usados por main, preload e renderer.

```ts
export type IpcResult<T> =
  | { ok: true; data: T }
  | { ok: false; error: AppErrorDto };

export interface AppErrorDto {
  code: string;
  message: string;
  recoverable: boolean;
  details?: Record<string, unknown>;
}
```

Todos os payloads de entrada devem ser validados com Zod no processo principal, mesmo que já tenham sido validados na interface.

### 17.4 Exemplo de preload

```ts
contextBridge.exposeInMainWorld('kitsune', {
  catalog: {
    search: (input: CatalogSearchInput) =>
      ipcRenderer.invoke('catalog:search', input),
  },
  playback: {
    open: (input: OpenPlaybackInput) =>
      ipcRenderer.invoke('playback:open', input),
    onPosition: (callback: (event: PlaybackPosition) => void) => {
      const handler = (_event: Electron.IpcRendererEvent, value: PlaybackPosition) => callback(value);
      ipcRenderer.on('playback:position', handler);
      return () => ipcRenderer.removeListener('playback:position', handler);
    },
  },
});
```

### 17.5 Regras

- `nodeIntegration: false`;
- `contextIsolation: true`;
- sandbox habilitado nas janelas compatíveis;
- nenhuma exposição do `ipcRenderer` completo;
- payloads tipados e validados;
- IDs opacos;
- erros estruturados;
- nenhum comando de shell arbitrário vindo do renderer;
- caminhos resolvidos e validados no processo principal;
- rate limit interno para buscas e atualizações;
- cancelamento de listeners ao desmontar componentes;
- eventos de progresso agregados para não saturar o IPC.

---

## 18. Estrutura sugerida do repositório

```text
kitsune/
├── apps/
│   └── desktop/
│       ├── src/
│       │   ├── main/
│       │   │   ├── bootstrap/
│       │   │   ├── ipc/
│       │   │   ├── services/
│       │   │   ├── providers/
│       │   │   ├── storage/
│       │   │   ├── playback/
│       │   │   ├── downloads/
│       │   │   ├── workers/
│       │   │   └── windows/
│       │   ├── preload/
│       │   │   ├── index.ts
│       │   │   └── api/
│       │   └── renderer/
│       │       ├── components/
│       │       ├── features/
│       │       ├── pages/
│       │       ├── stores/
│       │       ├── queries/
│       │       └── lib/
│       ├── resources/
│       │   ├── bin/
│       │   ├── icons/
│       │   └── licenses/
│       ├── electron-builder.yml
│       ├── vite.main.config.ts
│       ├── vite.preload.config.ts
│       └── vite.renderer.config.ts
├── packages/
│   ├── domain/
│   ├── contracts/
│   ├── release-parser/
│   ├── match-engine/
│   ├── provider-sdk/
│   ├── database/
│   ├── media-probe/
│   ├── ui/
│   └── config/
├── plugins/
│   ├── catalog-anilist/
│   ├── catalog-jikan/
│   ├── release-nyaa/
│   └── subtitle-opensubtitles/
├── scripts/
│   ├── download-binaries.mjs
│   ├── verify-binaries.mjs
│   ├── generate-licenses.mjs
│   └── smoke-test-build.mjs
├── docs/
├── tests/
├── pnpm-workspace.yaml
├── package.json
└── tsconfig.base.json
```

### 18.1 Scripts principais

```json
{
  "scripts": {
    "dev": "electron-vite dev",
    "build": "electron-vite build",
    "build:win": "pnpm build && electron-builder --win nsis",
    "build:portable": "pnpm build && electron-builder --win portable",
    "test": "vitest run",
    "test:e2e": "playwright test",
    "lint": "eslint .",
    "typecheck": "tsc --noEmit",
    "prepare:bin": "node scripts/download-binaries.mjs"
  }
}
```

### 18.2 Dados do usuário

Nunca gravar dados mutáveis dentro de `resources` ou `app.asar`.

Usar `app.getPath('userData')`:

```text
Kitsune/
├── kitsune.db
├── cache/
├── images/
├── thumbnails/
├── subtitles/
├── logs/
├── plugins/
└── config.json
```

Downloads de anime ficam em uma pasta escolhida pelo usuário e armazenada nas configurações.

---

## 19. Sistema de plugins

### 19.1 Por que usar plugins

- sites mudam;
- APIs saem do ar;
- cada país possui provedores diferentes;
- atualizações de scraper não devem exigir novo instalador;
- o núcleo deve permanecer legal e genérico.

### 19.2 Tipos

```text
CatalogProvider
ReleaseProvider
SubtitleProvider
TrackingProvider
MetadataMapper
PlayerExtension
```

### 19.3 Manifesto

```json
{
  "id": "release.example",
  "name": "Example Release Provider",
  "version": "1.0.0",
  "apiVersion": 1,
  "type": "release-provider",
  "permissions": [
    "network:example.org",
    "cache:provider"
  ],
  "entry": "dist/provider.js"
}
```

### 19.4 Segurança de plugins

No Electron, plugins JavaScript não devem ser carregados diretamente no processo principal. O MVP deve usar providers internos compilados junto com o aplicativo. Quando plugins externos forem liberados, executá-los em worker ou processo utilitário isolado, com API limitada e manifesto validado. WebAssembly também pode ser aceito para providers compatíveis.

O plugin não deve receber por padrão:

- acesso irrestrito ao sistema de arquivos;
- credenciais de outros provedores;
- cookies do navegador;
- execução de shell;
- acesso a qualquer domínio.

Permissões devem aparecer claramente antes da instalação.

---

## 20. Segurança e privacidade

### 20.1 Configuração obrigatória das janelas

```ts
new BrowserWindow({
  webPreferences: {
    preload: preloadPath,
    nodeIntegration: false,
    contextIsolation: true,
    sandbox: true,
    webSecurity: true,
  },
});
```

Regras adicionais:

- carregar somente arquivos empacotados ou protocolo local controlado;
- não usar `webview` para conteúdo arbitrário;
- bloquear navegação inesperada;
- abrir links externos somente após validação e por allowlist de protocolos;
- aplicar Content Security Policy sem `unsafe-eval` em produção;
- nunca desabilitar `webSecurity` para resolver CORS;
- usar sessão separada quando autenticação web exigir uma janela dedicada.

### 20.2 Princípios

- nenhuma telemetria obrigatória;
- credenciais no cofre do sistema operacional;
- tokens nunca em logs;
- banco local protegido contra consultas externas;
- renderer sem acesso direto ao sistema de arquivos;
- validação de URLs magnet, URLs HTTP e caminhos;
- atualizações assinadas;
- plugins assinados ou marcados como não verificados;
- logs sanitizados;
- histórico privado por padrão;
- dependências auditadas e lockfile versionado.

### 20.3 IPC

- preload expõe funções específicas, não módulos inteiros;
- canais centralizados em um registro único;
- input validado com Zod no main;
- respostas sem stack trace em produção;
- autorização por janela quando existirem janelas secundárias;
- subscriptions retornam função de limpeza;
- renderer nunca escolhe caminho de executável;
- renderer nunca fornece uma linha de comando pronta.

### 20.4 Processos externos

mpv, FFmpeg e qBittorrent devem:

- ser iniciados com `spawn` ou `execFile`, nunca concatenando comandos de shell;
- usar caminho absoluto conhecido;
- receber argumentos em array;
- usar portas locais aleatórias quando aplicável;
- escutar somente em localhost;
- usar credenciais temporárias;
- ser encerrados corretamente;
- não aceitar argumentos arbitrários vindos de metadados remotos;
- registrar apenas informações necessárias;
- ter hash ou versão verificados na primeira inicialização.

### 20.5 Rede BitTorrent

O aplicativo deve informar claramente:

- BitTorrent revela o endereço IP aos peers;
- VPN não é fornecida nem garantida pelo aplicativo;
- bind de interface deve ser configurável;
- não afirmar anonimato;
- se a interface configurada desaparecer, o usuário pode optar por pausar todos os torrents.

### 20.6 Atualizações

- canal estável e opcionalmente beta;
- manifesto servido por HTTPS;
- assinatura de código quando houver distribuição pública;
- atualização nunca altera a biblioteca do usuário;
- migrations com backup transacional;
- rollback ou restauração de banco quando uma migration falhar;
- binários auxiliares versionados separadamente no diagnóstico.

---

## 21. Desempenho

### 21.1 Metas

| Métrica | Meta inicial |
|---|---:|
| abertura até interface utilizável | abaixo de 3 s em SSD comum |
| uso de RAM sem reprodução | 200–350 MB |
| busca local | abaixo de 100 ms |
| troca de página cacheada | abaixo de 150 ms |
| primeiro conteúdo da home | abaixo de 1 s com cache |
| atualização de progresso | sem re-render global |
| uso de CPU em idle | próximo de 0% |

O aplicativo pode continuar responsivo se o renderer for tratado apenas como interface e todas as tarefas pesadas forem retiradas da thread de renderização.

### 21.2 Otimizações

- uma janela principal em vez de várias janelas Chromium permanentes;
- virtualização de listas;
- imagens responsivas e decodificadas sob demanda;
- cache em disco;
- deduplicação de chamadas;
- debounce de pesquisa;
- cancelamento de requisições antigas;
- queries GraphQL mínimas;
- lazy loading de personagens e relações;
- thumbnails geradas em fila de baixa prioridade;
- scanner de biblioteca incremental;
- hash completo somente quando necessário;
- tarefas pesadas em workers;
- SQLite somente no processo principal ou worker dedicado;
- eventos agregados de progresso, no máximo algumas vezes por segundo;
- desativar animações em segundo plano;
- pausar consultas quando a janela estiver minimizada;
- carregar páginas e módulos sob demanda;
- evitar bibliotecas duplicadas no bundle;
- usar `asarUnpack` somente para binários e módulos que realmente precisem.

### 21.3 Inicialização

Ordem recomendada:

1. abrir janela com shell visual local;
2. carregar configurações essenciais;
3. abrir SQLite;
4. mostrar biblioteca em cache;
5. validar binários em background;
6. iniciar qBittorrent somente quando downloads forem necessários;
7. atualizar catálogo sem bloquear a interface.

O aplicativo não deve iniciar mpv, FFmpeg e qBittorrent todos ao mesmo tempo sem necessidade.

---

## 22. Tratamento de erros

### 22.1 Provedor indisponível

Mostrar:

```text
O provedor de catálogo está indisponível.
Exibindo dados salvos localmente há 3 dias.
```

### 22.2 Nenhum release encontrado

Oferecer:

- pesquisar com outro título;
- remover filtros;
- procurar batch;
- associar magnet manualmente;
- adicionar arquivo local;
- tentar novamente posteriormente.

### 22.3 Anime associado incorretamente

Permitir:

- trocar anime;
- trocar episódio;
- salvar regra por grupo;
- ignorar release;
- reportar regra ao parser local.

### 22.4 Falha no player

Registrar:

- caminho do arquivo;
- codecs;
- saída do mpv sanitizada;
- argumentos seguros usados;
- estado do buffer;
- versão do player.

A interface deve oferecer `Tentar novamente`, `Abrir externamente` e `Copiar diagnóstico`.

---

## 23. Testes

### 23.1 Testes unitários

- normalização de títulos;
- parsing de releases;
- numeração de episódios;
- scoring;
- seleção de faixas;
- templates de arquivos;
- regras de limpeza;
- migrations.

### 23.2 Casos difíceis do parser

- episódios com número decimal;
- `12.5`;
- episódios duplos;
- `01-02`;
- temporadas com numeração absoluta;
- `S2`, `Season 2`, `2nd Season`;
- filmes divididos em partes;
- OVAs e especiais;
- resolução confundida com episódio;
- versão `v2` ou `v3`;
- batch sem intervalo explícito;
- título contendo números;
- nomes em japonês;
- arquivos NCOP/NCED;
- extras de Blu-ray.

### 23.3 Testes de integração

- AniList → anime local;
- anime → consulta de release;
- release → torrent;
- torrent → arquivo;
- arquivo → faixas;
- faixa → player;
- player → progresso;
- progresso → tracking.

### 23.4 Testes end-to-end

Fluxos:

1. pesquisar anime;
2. abrir página;
3. selecionar episódio;
4. escolher release de fixture local;
5. iniciar download simulado;
6. abrir player de teste;
7. marcar episódio como concluído.

Serviços externos devem ser mockados nos testes automatizados.

---

## 24. Roadmap

### Fase 0 — Fundação

- monorepo pnpm;
- Electron + React + TypeScript + Vite;
- separação `main`, `preload` e `renderer`;
- IPC tipado e validado;
- design system;
- SQLite e migrations;
- logs;
- sistema de configurações;
- scripts de binários auxiliares;
- electron-builder com NSIS e portable;
- contratos dos provedores;
- CI para Windows e Linux.

### Fase 1 — Catálogo local

- integração AniList;
- busca;
- home;
- tela de anime;
- relações;
- episódios gerados a partir do total conhecido;
- cache de imagens;
- perfil e lista local.

### Fase 2 — Biblioteca e player

- importar arquivos locais;
- parser inicial;
- ffprobe;
- thumbnails locais;
- mpv por JSON IPC;
- progresso;
- faixas de áudio e legenda;
- próximo episódio.

Esta fase já produz um aplicativo útil sem torrents.

### Fase 3 — Downloads

- integração qBittorrent;
- adicionar magnet e `.torrent` manualmente;
- downloads;
- arquivos do torrent;
- seleção de prioridade;
- organização na biblioteca;
- download sequencial;
- reprodução após buffer mínimo.

### Fase 4 — Provedores de release

- SDK de provider;
- provider Nyaa opcional;
- parser robusto;
- scoring;
- filtros;
- tela de escolha;
- regras por grupo;
- cache de pesquisas.

### Fase 5 — Legendas

- legendas internas;
- busca local;
- OpenSubtitles;
- download e cache;
- offset;
- regras de idioma;
- fallback.

### Fase 6 — Automação

- calendário;
- episódios novos;
- pesquisa periódica;
- auto-download;
- notificações;
- pré-buffer do próximo episódio;
- limpeza automática.

### Fase 7 — Streaming avançado

- substituir ou complementar qBittorrent por serviço libtorrent;
- endpoint HTTP local;
- seek por prioridade de peças;
- buffer adaptativo;
- recuperação de sessão;
- métricas de saúde do stream.

### Fase 8 — Sincronização

- OAuth AniList;
- importação da lista;
- atualização de progresso;
- conflitos;
- fila offline;
- histórico de sincronização.

### Fase 9 — Polimento

- atualizador;
- plugins isolados;
- assinatura de builds;
- diagnóstico;
- acessibilidade;
- tradução;
- onboarding;
- backup e restauração.

---

## 25. MVP realista

O MVP não deve tentar incluir tudo.

Escopo mínimo recomendado:

- Windows 10 e 11 x64;
- Electron + React + TypeScript;
- processo principal Node.js;
- preload isolado e IPC tipado;
- instalador NSIS e versão portátil;
- catálogo AniList;
- capas e banners;
- busca;
- detalhes do anime;
- lista local;
- importação de arquivos locais;
- qBittorrent detectado ou empacotado como componente opcional;
- provider de release configurável;
- parser de nome;
- escolha manual de release;
- mpv externo controlado por JSON IPC;
- FFmpeg e ffprobe empacotados;
- legendas embutidas;
- OpenSubtitles opcional;
- progresso local;
- downloads;
- cache SQLite;
- diagnóstico dos componentes.

Fora do MVP:

- libtorrent próprio;
- `libmpv` embutido;
- Android;
- televisão;
- IA para legendas;
- sincronização entre dispositivos;
- plugins JavaScript arbitrários;
- recomendação por machine learning;
- detecção avançada de abertura;
- atualização silenciosa obrigatória;
- múltiplas janelas complexas.

### 25.1 Experiência de primeira execução

```text
Abrir Kitsune
    ↓
Validar banco e recursos
    ↓
Perguntar pasta da biblioteca/downloads
    ↓
Detectar qBittorrent existente ou ativar componente incluído
    ↓
Testar mpv e FFmpeg automaticamente
    ↓
Abrir catálogo
```

Nenhuma etapa deve pedir instalação de Rust, Python, Node.js, FFmpeg ou codecs ao usuário final.

### 25.2 Estratégia para qBittorrent

O aplicativo pode suportar três modos:

1. **Gerenciado:** inicia um qBittorrent local incluído no pacote;
2. **Detectado:** usa uma instalação local encontrada automaticamente;
3. **Remoto:** conecta a uma Web API configurada pelo usuário.

O modo gerenciado é o mais plug and play. O modo detectado reduz o tamanho do instalador. O modo remoto atende usuários avançados e servidores domésticos.

---

## 26. Critérios de aceitação do MVP

O MVP estará funcional quando o usuário conseguir:

1. instalar e abrir o aplicativo;
2. pesquisar um anime pelo título romaji ou inglês;
3. visualizar capa, banner, sinopse e episódios;
4. selecionar um episódio;
5. ver releases normalizados;
6. entender resolução, codec, tamanho e seeds;
7. adicionar o release ao qBittorrent;
8. acompanhar o download;
9. iniciar reprodução no mpv quando houver buffer suficiente;
10. selecionar áudio e legenda;
11. fechar e retomar do mesmo ponto;
12. marcar o episódio como assistido;
13. encontrar o arquivo na biblioteca;
14. usar o aplicativo novamente sem refazer chamadas já cacheadas.

---

## 27. Decisões técnicas importantes

### 27.1 Não misturar catálogo com torrent

O anime deve existir no banco mesmo sem nenhum release encontrado. O release é apenas uma possível fonte de arquivo.

### 27.2 Não usar título como identificador

Títulos mudam, possuem traduções e ambiguidades. Usar ID interno e mapas externos.

### 27.3 Não depender de scraper no núcleo

O provider externo pode quebrar. O aplicativo precisa continuar abrindo biblioteca, player e dados salvos.

### 27.4 Não construir torrent próprio no primeiro ciclo

qBittorrent resolve conexão, DHT, retomada, verificação e downloads. O serviço próprio só deve surgir quando a experiência de streaming justificar o custo.

### 27.5 Não baixar legenda apenas pelo nome

Hash, duração, grupo e FPS aumentam muito a chance de sincronização correta.

### 27.6 Não carregar imagens originais em todas as telas

Gerar e armazenar variantes pequenas para reduzir RAM, disco e rede.

### 27.7 Não colocar Node.js no renderer

Toda operação privilegiada passa pelo preload e pelo processo principal. Isso reduz a superfície de ataque e impede que uma falha na interface vire acesso irrestrito ao computador.

### 27.8 Não executar comandos por string

mpv, FFmpeg e qBittorrent devem ser iniciados com executável conhecido e lista de argumentos. Nunca montar uma linha de shell usando título de anime, nome de arquivo ou resposta de provedor.

### 27.9 Não iniciar todos os serviços no boot

qBittorrent, FFmpeg e mpv são iniciados sob demanda. O catálogo e a biblioteca devem abrir sem esperar esses processos.

### 27.10 Não depender de instalação global

O app deve localizar seus binários em `resources/bin` ou em um caminho configurado. Não depender de `PATH`, codecs do sistema ou programas instalados manualmente.

---

## 28. Melhorias futuras

- controle remoto pelo celular na rede local;
- transmitir para outro computador com o app instalado;
- integração com Jellyfin ou Plex como biblioteca;
- perfis separados;
- modo infantil;
- filtros de conteúdo;
- recomendações locais sem enviar histórico;
- comparação visual de releases;
- thumbnails de capítulos;
- timeline com abertura, encerramento e preview;
- comentários privados por episódio;
- watch party P2P sem retransmitir mídia;
- backup criptografado;
- sincronização local entre computadores;
- suporte a RSS definido pelo usuário;
- regras avançadas semelhantes a Sonarr;
- importação de listas MAL/Kitsu;
- modo somente biblioteca, sem BitTorrent;
- suporte a fontes legais de streaming por plugins oficiais.

---

## 29. Referências técnicas

- Electron — documentação oficial: <https://www.electronjs.org/docs/latest/>
- Electron Process Model: <https://www.electronjs.org/docs/latest/tutorial/process-model>
- Electron IPC: <https://www.electronjs.org/docs/latest/tutorial/ipc>
- Electron Context Isolation: <https://www.electronjs.org/docs/latest/tutorial/context-isolation>
- Electron Security: <https://www.electronjs.org/docs/latest/tutorial/security>
- electron-vite: <https://electron-vite.org/>
- electron-builder: <https://www.electron.build/>
- electron-builder NSIS e portable: <https://www.electron.build/nsis/>
- Node.js child processes: <https://nodejs.org/api/child_process.html>
- AniList GraphQL API: <https://docs.anilist.co/>
- AniList Media object: <https://docs.anilist.co/reference/object/media>
- Jikan API: <https://docs.jikan.moe/>
- Kitsu API: <https://api-docs.kitsu.cloud/>
- OpenSubtitles API: <https://ai.opensubtitles.com/docs>
- qBittorrent WebUI API: <https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-(qBittorrent-5.0)>
- mpv: <https://github.com/mpv-player/mpv>
- FFmpeg: <https://ffmpeg.org/documentation.html>
- FFmpeg filters: <https://ffmpeg.org/ffmpeg-filters.html>
- WebTorrent: <https://github.com/webtorrent/webtorrent>
- libtorrent: <https://github.com/arvidn/libtorrent>
- better-sqlite3: <https://github.com/WiseLibs/better-sqlite3>
- Zod: <https://zod.dev/>
- TanStack Query: <https://tanstack.com/query/latest>
- Zustand: <https://zustand.docs.pmnd.rs/>

---

## 30. Resumo da implementação recomendada

A primeira versão deve ser um aplicativo **Electron com React e TypeScript**, dividido em renderer, preload e processo principal Node.js.

A arquitetura final do MVP será:

```text
Electron
├── renderer React
├── preload seguro e tipado
├── processo principal Node.js
├── SQLite
├── mpv via JSON IPC
├── FFmpeg/ffprobe
├── qBittorrent via Web API
└── provedores modulares
    ├── AniList
    ├── Nyaa opcional
    ├── OpenSubtitles
    └── fallbacks de metadados
```

AniList fornece catálogo, capas e relações. TMDB e thumbnails locais completam imagens de episódios. O mpv reproduz MKV e legendas avançadas. FFmpeg inspeciona os arquivos e gera imagens. qBittorrent cuida dos torrents no MVP. Nyaa entra como provider opcional e isolado, não como parte inseparável do núcleo. OpenSubtitles e arquivos locais fornecem legendas externas.

O computador de desenvolvimento precisa apenas de Node.js LTS, Git e um editor. O usuário final recebe instalador ou executável portátil com o runtime e componentes necessários, sem Rust, Python ou configuração manual.

A ordem correta é:

```text
fundação Electron → catálogo → biblioteca local → player → downloads
→ provider de releases → legendas → automações → streaming avançado
→ sincronização
```

Essa ordem entrega valor cedo, reduz risco e impede que mudanças em um site externo destruam o aplicativo inteiro.
