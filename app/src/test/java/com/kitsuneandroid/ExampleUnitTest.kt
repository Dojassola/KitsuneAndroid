package com.kitsuneandroid

import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.BitSet

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun parsesMyAnimeListFallbackAnime() {
        val anime = parseMalAnime(JSONObject("""{
            "mal_id": 5114, "title": "Hagane no Renkinjutsushi", "title_english": "Fullmetal Alchemist: Brotherhood",
            "titles": [{"type":"Default","title":"Hagane no Renkinjutsushi"}],
            "images": {"webp":{"large_image_url":"https://example.com/cover.webp"}},
            "synopsis":"Alchemy.", "episodes":64, "score":9.1, "year":2009, "season":"spring",
            "type":"TV", "status":"Finished Airing", "airing":false, "genres":[{"name":"Action"}], "title_synonyms":[]
        }"""))

        assertEquals(-5114, anime.id)
        assertEquals("Fullmetal Alchemist: Brotherhood", anime.title)
        assertEquals(91, anime.score)
        assertEquals("FINISHED", anime.status)
    }

    @Test
    fun parsesKitsuFallbackAnime() {
        val anime = parseKitsuAnime(JSONObject("""{
            "id":"12", "attributes":{"canonicalTitle":"Cowboy Bebop","titles":{"en":"Cowboy Bebop","en_jp":"Cowboy Bebop"},
            "posterImage":{"large":"https://example.com/cover.jpg"},"synopsis":"Bounty hunters.","episodeCount":26,
            "averageRating":"82.5","startDate":"1998-04-03","subtype":"TV","status":"finished"}
        }"""))

        assertEquals(-1_000_000_012, anime.id)
        assertEquals(82, anime.score)
        assertEquals("FINISHED", anime.status)
    }

    @Test
    fun cleansAniListDescription() {
        assertEquals("Linha 1\nLinha 2 & fim", cleanDescription("<b>Linha 1</b><br>Linha 2 &amp; fim"))
    }

    @Test
    fun parsesAndRanksNyaaRelease() {
        val xml = """
            <rss xmlns:nyaa="https://nyaa.si/xmlns/nyaa"><channel><item>
              <title>[Fansub] Frieren - 12 [1080p][HEVC][PT-BR]</title>
              <guid>https://nyaa.si/view/123</guid>
              <nyaa:infoHash>0123456789abcdef0123456789abcdef01234567</nyaa:infoHash>
              <nyaa:size>1.5 GiB</nyaa:size><nyaa:seeders>42</nyaa:seeders><nyaa:leechers>3</nyaa:leechers>
              <nyaa:trusted>Yes</nyaa:trusted><nyaa:remake>No</nyaa:remake>
            </item></channel></rss>
        """.trimIndent()

        val release = parseNyaaRss(xml, listOf("Frieren"), 12).single()
        assertEquals(12, release.parsed.episode)
        assertEquals(1080, release.parsed.resolution)
        assertTrue(release.score >= 90)
        assertTrue(matchesAnimeTitle("[Fansub] Kusuriya no Hitorigoto The Apothecary Diaries - 01 [1080p]", listOf("Kusuriya no Hitorigoto")))
    }

    @Test
    fun parsesTokyoToshoReleaseWithMagnet() {
        val xml = """
            <rss><channel><item>
              <category>Anime</category>
              <title>[Fansub] Frieren - 12 [1080p][HEVC]</title>
              <description><![CDATA[
                <a href="magnet:?xt=urn:btih:GPIZ4VLITVT4DNW4UFSQ2PXQVFZPZVOR&tr=https://tracker.example/announce">Magnet</a>
                <a href="https://www.tokyotosho.info/details.php?id=456">Tokyo Tosho</a>
                Size: 1.5GB<br />Authorized: Yes
              ]]></description>
            </item></channel></rss>
        """.trimIndent()

        val release = parseTokyoToshoRss(xml, listOf("Frieren"), 12).single()

        assertEquals("tokyotosho", release.providerId)
        assertEquals("tokyotosho:456", release.id)
        assertEquals("33d19e55689d67c1b6dca1650d3ef0a972fcd5d1", release.infoHash)
        assertEquals(1_500_000_000L, release.sizeBytes)
        assertTrue(release.magnetUri?.startsWith("magnet:?xt=urn:btih:") == true)
        assertTrue(release.trusted)
    }

    @Test
    fun convertsBase32TorrentHashToHex() {
        assertEquals("0".repeat(40), infoHashToHex("A".repeat(32)))
        assertNull(infoHashToHex("not-a-hash"))
    }

    @Test
    fun findsTheRequestedSeasonAndSelectsOnlyItsEpisodeFiles() {
        val anime = Anime(
            180745, null, "Classroom of the Elite 4th Season", "Youkoso Jitsuryoku Shijou Shugi no Kyoushitsu e 4th Season",
            "Classroom of the Elite 4th Season: Second Year, First Semester", "", "https://example.com/cover.jpg", null,
            16, null, 2026, "SPRING", "TV", null, emptyList(), listOf("Classroom of the Elite Season 4")
        )
        assertTrue(releaseSearchQueries(anime, 16).any { it.endsWith("S04E16") })
        assertTrue(matchesAnimeTitle("[Yameii] Classroom of the Elite - S04E16 [1080p]", listOf(anime.romajiTitle, anime.englishTitle!!), 4))
        assertFalse(matchesAnimeTitle("Classroom of the Elite S03E16 1080p", listOf(anime.romajiTitle, anime.englishTitle), 4))
        assertEquals(1, parseReleaseTitle("[Judas] Youjitsu - S04E01v2.mkv").episode)

        val files = listOf(
            TorrentFileChoice(0, "Season 04/Anime S04E01v2.mkv", 1_000, true),
            TorrentFileChoice(1, "Season 04/Anime S04E02.mkv", 1_100, true),
            TorrentFileChoice(2, "Season 04/Anime S04E01.ass", 10, false)
        )
        assertEquals(listOf(0, 2) to 0, defaultTorrentSelection(files, 1))
        assertEquals(listOf(1) to 1, torrentEpisodeSelection(files, 2))
    }

    @Test
    fun tracksWherePlaybackStoppedAndMarksNinetyPercentAsWatched() {
        assertFalse(isWatched(17 * 60_000L, 23 * 60_000L))
        assertTrue(isWatched(21 * 60_000L, 23 * 60_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRssWithDoctype() {
        parseNyaaRss("<!DOCTYPE rss SYSTEM \"https://example.com/evil.dtd\"><rss/>", listOf("Frieren"), null)
    }

    @Test
    fun clampsDoubleTapSeekToVideoBounds() {
        assertEquals(0L, seekTarget(3_000, 60_000, 10, false))
        assertEquals(60_000L, seekTarget(55_000, 60_000, 10, true))
        assertEquals(30_000L, seekTarget(20_000, 60_000, 10, true))
    }

    @Test
    fun restartsCurrentEpisodeBeforeOpeningPreviousEpisode() {
        assertTrue(shouldRestartCurrentEpisode(5_001))
        assertFalse(shouldRestartCurrentEpisode(5_000))
    }

    @Test
    fun stopsStreamingAtFirstMissingTorrentPiece() {
        val available = contiguousFileBytes(1_000, 8_000, 4_000, 0, 2, { it < 1 }, { 4_000 })
        assertEquals(3_000L, available)
    }

    @Test
    fun streamsACompletedPieceRequestedFromTheMiddleOfTheFile() {
        val completed = BitSet().apply { set(2) }
        val snapshot = TorrentStreamSnapshot(1_000, 12_000, 4_000, completed)
        assertEquals(4_000L, snapshot.availableBytes(7_000, 8_000))
    }

    @Test
    fun comparesAppVersions() {
        assertTrue(isNewerVersion("v1.2.0", "1.1"))
        assertFalse(isNewerVersion("v1.2.0", "1.2"))
        assertTrue(parseReleaseTitle("Anime - 01 [1080p AVC Hi10P]").tenBit)
    }

    @Test
    fun roundTripsUserDataBackup() {
        val original = linkedMapOf<String, Any>(
            "favorites" to setOf("1", "2"),
            "video_history" to "[\"episódio 1\"]",
            "progress:file:///Anime 01.mkv" to 42_000L,
            "subtitle_size" to 0.05f,
            "seek_seconds" to 10,
            "enabled" to true
        )
        val output = ByteArrayOutputStream()
        BackupCodec.write(original, output)

        assertEquals(original, BackupCodec.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun completesAiredEpisodesWhenJikanIsBehindAniList() {
        val anime = Anime(
            207141, 63403, "Chainsmoker Cat", "Yani Neko", "Chainsmoker Cat", "", "https://example.com/cover.jpg", null,
            null, null, 2026, "SUMMER", "TV", "RELEASING", emptyList(), nextAiringEpisode = 6
        )
        val jikan = listOf(Episode(1, "Episode 1", null, null, null, null, false, false, null, null))

        assertEquals(listOf(1, 2, 3, 4, 5), completeEpisodeList(anime, jikan).map(Episode::number))
    }

    @Test
    fun recommendsMostSeededCompatibleRelease() {
        fun release(id: String, title: String, seeders: Int, score: Int) = ReleaseCandidate(
            id, title, id.padEnd(40, '0'), 1_000, seeders, 0, true, false, parseReleaseTitle(title), score, emptyList()
        )
        val releases = listOf(
            release("1", "Anime - 01 [720p H264 PT-BR]", 500, 100),
            release("2", "Anime - 01 [1080p H264 PT-BR]", 40, 90),
            release("3", "Anime - 01 [1080p H264 PT-BR]", 120, 80),
            release("4", "Anime - 01 [1080p H264]", 900, 120)
        )

        assertEquals("3", recommendedRelease(releases, ReleasePreferences(ReleaseLanguage.PORTUGUESE, 1080))?.id)
    }

    @Test
    fun recognizesPorBrAndNeverRecommendsWrongLanguage() {
        fun release(id: String, title: String, seeders: Int) = ReleaseCandidate(
            id, title, id.padEnd(40, '0'), 1_000, seeders, 0, true, false,
            parseReleaseTitle(title), 100, emptyList()
        )
        val portuguese = release(
            "pt",
            "[Erai-raws] Suki na Ko ga Megane wo Wasureta - 08 [1080p][POR-BR]",
            3
        )
        val english = release("en", "[SubsPlease] Suki na Ko ga Megane wo Wasureta - 08 [720p]", 4)

        assertTrue(portuguese.parsed.ptBr)
        assertEquals(
            "pt",
            recommendedRelease(
                listOf(english, portuguese),
                ReleasePreferences(ReleaseLanguage.PORTUGUESE, 1080)
            )?.id
        )
        assertNull(
            recommendedRelease(
                listOf(english),
                ReleasePreferences(ReleaseLanguage.PORTUGUESE, 1080)
            )
        )
    }

    @Test
    fun mergesProviderStreamsWithoutHidingEmptyAndFailureStates() {
        fun release(id: String, score: Int): ReleaseCandidate {
            val title = "Anime - 01 [1080p H264]"

            return ReleaseCandidate(
                id = id,
                title = title,
                infoHash = id.padEnd(40, '0'),
                sizeBytes = 1_000,
                seeders = 10,
                leechers = 0,
                trusted = true,
                remake = false,
                parsed = parseReleaseTitle(title),
                score = score,
                reasons = emptyList()
            )
        }

        val best = release("1", 100)
        val duplicate = best.copy(
            score = 50,
            providerId = "tokyotosho",
            providerIds = setOf("tokyotosho")
        )

        val merged = mergeStreamResults(
            listOf(
                ProviderResult.Success(listOf(best)),
                ProviderResult.Success(listOf(duplicate))
            )
        )

        when (merged) {
            is ProviderResult.Success -> {
                assertEquals(1, merged.value.size)
                assertEquals(setOf("nyaa", "tokyotosho"), merged.value.single().providerIds)
                assertTrue(releaseSummary(merged.value.single()).startsWith("Nyaa + Tokyo Toshokan"))
            }

            else -> {
                fail("A agregação deveria retornar as releases encontradas.")
            }
        }

        val emptyResult = mergeStreamResults(listOf(ProviderResult.Empty))
        assertEquals(ProviderResult.Empty, emptyResult)

        val failure = ProviderResult.Failure(
            providerId = "provider",
            message = "offline"
        )
        val failureResult = mergeStreamResults(
            listOf(
                ProviderResult.Empty,
                failure
            )
        )

        assertEquals(
            failure,
            failureResult
        )
    }

    @Test
    fun keepsTranslationRequestsBelowApiLimit() {
        val chunks = translationChunks(List(200) { "descrição" }.joinToString(" "))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 450 })
    }

    @Test
    fun resumesOnlyInsideContiguousPlayableVideo() {
        val duration = 24 * 60_000L
        assertEquals(60_000L, safeStreamingResumePosition(60_000, duration, 750_000_000, 1_500_000_000))
        assertEquals(0L, safeStreamingResumePosition(60_000, duration, 35_000_000, 1_500_000_000))
        assertEquals(12 * 60_000L, bufferedVideoDurationMs(duration, 750_000_000, 1_500_000_000))
        assertEquals(3, priorityWindowLast(2, 20, 1_000_000, 500_000))
        assertFalse(shouldPrefetchNextEpisode(18 * 60_000L + 59_000, duration))
        assertTrue(shouldPrefetchNextEpisode(19 * 60_000L, duration))
    }

    @Test
    fun identifiesTorrentThatStoppedReceivingPayload() {
        assertEquals(
            TorrentStatus.STALLED,
            torrentStatus(false, false, false, peers = 2, downloadRate = 0, now = 21_000, lastPayloadAt = 0)
        )
        assertEquals(
            TorrentStatus.SEARCHING_PEERS,
            torrentStatus(false, false, false, peers = 0, downloadRate = 0, now = 21_000, lastPayloadAt = 0)
        )
        assertEquals(TorrentStatus.DOWNLOADING, TorrentStatus.fromPersisted("downloading"))
        assertEquals(TorrentStatus.QUEUED, TorrentStatus.fromPersisted("valor-antigo-desconhecido"))
    }

    @Test
    fun roundTripsOfflineFavoriteMetadata() {
        val anime = Anime(1, 2, "Título", "Titulo", "Title", "Descrição", "cover", "banner", 12, 90, 2026, "SUMMER", "TV", "RELEASING", listOf("Ação"), listOf("Alias"), 6)
        assertEquals(anime, decodeAnimeList(encodeAnimeList(listOf(anime))).single())
    }

    @Test
    fun selectsOnlyNextEpisodeFromSeasonTorrent() {
        val files = listOf(
            TorrentFileChoice(0, "Anime - 01.mkv", 100, true),
            TorrentFileChoice(1, "Anime - 02.mkv", 100, true),
            TorrentFileChoice(2, "Anime - 02.pt-BR.ass", 1, false),
            TorrentFileChoice(3, "Anime - 03.mkv", 100, true)
        )
        assertEquals(listOf(1, 2) to 1, torrentEpisodeSelection(files, 2))
    }

    @Test
    fun removesOnlyTheChosenEpisodeFromSeasonTorrent() {
        val files = listOf(
            TorrentFileChoice(0, "Anime - 01.mkv", 100, true),
            TorrentFileChoice(1, "Anime - 01.pt-BR.ass", 1, false),
            TorrentFileChoice(2, "Anime - 02.mkv", 100, true),
            TorrentFileChoice(3, "Anime - 02.pt-BR.ass", 1, false)
        )

        val remainingFiles = torrentFilesAfterEpisodeRemoval(
            files = files,
            selectedFileIndices = listOf(0, 1, 2, 3),
            episode = 1,
            videoFileIndex = 0
        )

        assertEquals(listOf(2, 3), remainingFiles)
    }

    @Test
    fun navigatesOfflineEpisodesInsideTheSameAnime() {
        fun episode(animeId: Int, episode: Int, path: String): TorrentDownload {
            return TorrentDownload(
                releaseId = path,
                infoHash = path.padEnd(40, '0'),
                name = "Anime - ${episode.toString().padStart(2, '0')}",
                status = TorrentStatus.COMPLETED,
                progress = 1f,
                downloadSpeed = 0,
                downloadedBytes = 100,
                sizeBytes = 100,
                peers = 0,
                videoPath = path,
                error = null,
                animeId = animeId,
                animeTitle = "Anime",
                episode = episode
            )
        }

        val firstEpisode = episode(animeId = 1, episode = 1, path = "episode-1.mkv")
        val secondEpisode = episode(animeId = 1, episode = 2, path = "episode-2.mkv")
        val fourthEpisode = episode(animeId = 1, episode = 4, path = "episode-4.mkv")
        val otherAnime = episode(animeId = 2, episode = 3, path = "other-anime.mkv")
        val offlineEpisodes = listOf(
            fourthEpisode,
            otherAnime,
            firstEpisode,
            secondEpisode
        )

        assertEquals(
            secondEpisode,
            nextOfflineEpisode(offlineEpisodes, firstEpisode)
        )
        assertEquals(
            secondEpisode,
            previousOfflineEpisode(offlineEpisodes, fourthEpisode)
        )
        assertEquals(
            null,
            previousOfflineEpisode(offlineEpisodes, firstEpisode)
        )
        assertEquals(
            setOf(1, 2),
            offlineAnimeIds(offlineEpisodes)
        )
        assertEquals(
            secondEpisode,
            offlineEpisode(offlineEpisodes, animeId = 1, episodeNumber = 2)
        )
        assertEquals(
            null,
            offlineEpisode(offlineEpisodes, animeId = 1, episodeNumber = 3)
        )
    }

    @Test
    fun mergesCatalogProvidersWithoutDuplicatingMalEntries() {
        fun anime(id: Int, malId: Int?, title: String): Anime {
            return Anime(
                id = id,
                malId = malId,
                title = title,
                romajiTitle = title,
                englishTitle = title,
                description = "",
                cover = "",
                banner = null,
                episodes = null,
                score = null,
                year = null,
                season = null,
                format = null,
                status = null,
                genres = emptyList()
            )
        }

        val anilist = anime(id = 1, malId = 10, title = "Cowboy Bebop")
        val malDuplicate = anime(id = -10, malId = 10, title = "Cowboy Bebop")
        val kitsu = anime(id = -1_000_000_020, malId = null, title = "Samurai Champloo")

        assertEquals(
            listOf(anilist, kitsu),
            mergeCatalogs(listOf(listOf(anilist), listOf(malDuplicate), listOf(kitsu)))
        )
    }

    @Test
    fun readsCompletedTorrentFilesWithoutBreakingLegacyDownloads() {
        val currentDownload = downloadFromJson(
            JSONObject(
                """{
                    "releaseId": "release",
                    "infoHash": "hash",
                    "name": "Anime",
                    "status": "downloading",
                    "completedFileIndices": [1, 3]
                }"""
            )
        )
        val legacyDownload = downloadFromJson(
            JSONObject(
                """{
                    "releaseId": "legacy",
                    "infoHash": "legacy-hash",
                    "name": "Anime antigo",
                    "status": "completed"
                }"""
            )
        )

        assertEquals(listOf(1, 3), currentDownload.completedFileIndices)
        assertTrue(legacyDownload.completedFileIndices.isEmpty())
    }

    @Test
    fun offersIntroSkipOnlyInsideOpeningChapter() {
        val opening = MediaChapter("Creditless Opening", 35_000, 125_000)
        assertEquals(opening, introChapterAt(listOf(opening), 60_000))
        assertEquals(null, introChapterAt(listOf(opening), 130_000))
        assertEquals("OP 2", introChapterAt(listOf(MediaChapter("OP 2", 0, 90_000)), 1_000)?.title)

        val ending = MediaChapter("NCED", 1_250_000, 1_340_000)
        assertEquals(ending, endingChapterAt(listOf(ending), 1_300_000))
        assertNull(endingChapterAt(listOf(ending), 1_200_000))
    }

    @Test
    fun recognizesAllSkippableChapterKinds() {
        val chapters = listOf(
            MediaChapter("Previously on", 0, 30_000),
            MediaChapter("NCOP", 30_000, 120_000),
            MediaChapter("Next Episode Preview", 1_200_000, 1_230_000)
        )

        assertEquals(MediaSegmentKind.RECAP, skippableSegmentAt(chapters, 5_000)?.kind)
        assertEquals(MediaSegmentKind.INTRO, skippableSegmentAt(chapters, 60_000)?.kind)
        assertEquals(MediaSegmentKind.PREVIEW, skippableSegmentAt(chapters, 1_210_000)?.kind)
        assertNull(skippableSegmentAt(chapters, 500_000))
    }

    @Test
    fun offersEpisodeNavigationOnlyNearTheEnd() {
        val duration = 24 * 60_000L

        assertFalse(shouldOfferEpisodeNavigation(20 * 60_000L, duration))
        assertFalse(shouldOfferEpisodeNavigation(duration - 90_001L, duration))
        assertTrue(shouldOfferEpisodeNavigation(duration - 90_000L, duration))
        assertFalse(shouldOfferEpisodeNavigation(0, 0))
    }

    @Test
    fun blocksDownloadsOnlyWhenAnEnabledPolicyRequiresIt() {
        val policy = DownloadPolicyPreferences(
            wifiOnly = true,
            pauseOnLowBattery = true,
            preserveStorage = true
        )

        assertTrue(
            downloadPolicyBlockReason(policy, false, 80, false, 4L * 1024 * 1024 * 1024)
                ?.contains("Wi-Fi") == true
        )
        assertTrue(
            downloadPolicyBlockReason(policy, true, 15, false, 4L * 1024 * 1024 * 1024)
                ?.contains("bateria") == true
        )
        assertTrue(
            downloadPolicyBlockReason(policy, true, 80, false, 512L * 1024 * 1024)
                ?.contains("espaço") == true
        )
        assertNull(
            downloadPolicyBlockReason(policy, true, 80, false, 4L * 1024 * 1024 * 1024)
        )
    }

    @Test
    fun adaptsStreamingPriorityAndExplainsTrackerDifferences() {
        assertEquals(12 * 1024 * 1024, streamPriorityBytes(0))
        assertEquals(40 * 1024 * 1024, streamPriorityBytes(2L * 1024 * 1024))
        assertEquals(64 * 1024 * 1024, streamPriorityBytes(10L * 1024 * 1024))

        val download = TorrentDownload(
            releaseId = "release",
            infoHash = "hash",
            name = "Anime",
            status = TorrentStatus.DOWNLOADING,
            progress = 0.5f,
            downloadSpeed = 1_000,
            downloadedBytes = 50,
            sizeBytes = 100,
            peers = 2,
            videoPath = null,
            error = null,
            connectedSeeders = 2,
            trackerSeeders = 50
        )

        assertTrue(torrentConnectionDiagnostic(download)?.contains("50 seeders") == true)
    }

    @Test
    fun parsesSafeDirectStreamsFromAStremioAddon() {
        assertEquals(
            "https://addon.example/manifest.json",
            normalizeStremioAddonUrl("https://addon.example/")
        )
        assertThrows(IllegalArgumentException::class.java) {
            normalizeStremioAddonUrl("http://127.0.0.1:7000/manifest.json")
        }

        val manifestUrl = "https://addon.example/manifest.json"
        val manifest = parseStremioManifest(
            JSONObject(
                """{
                    "id": "example.anime",
                    "name": "Example Anime",
                    "types": ["anime"],
                    "resources": ["stream"],
                    "idPrefixes": ["mal"]
                }"""
            )
        )
        val anime = Anime(
            id = 1,
            malId = 2,
            title = "Anime",
            romajiTitle = "Anime",
            englishTitle = null,
            description = "",
            cover = "https://example.com/cover.jpg",
            banner = null,
            episodes = 12,
            score = null,
            year = 2026,
            season = null,
            format = "TV",
            status = "RELEASING",
            genres = emptyList()
        )
        val releases = parseStremioStreams(
            payload = JSONObject(
                """{
                    "streams": [{
                        "name": "1080p",
                        "title": "Anime - 01 [1080p]",
                        "url": "https://cdn.example/video.m3u8"
                    }]
                }"""
            ),
            manifest = manifest,
            manifestUrl = manifestUrl,
            request = StreamRequest(anime, 1, ReleasePreferences())
        )

        assertEquals("https://cdn.example/video.m3u8", releases.single().directUrl)
        assertEquals("stremio:example.anime", releases.single().providerId)

        val subtitles = parseStremioSubtitles(
            JSONObject(
                """{
                    "subtitles": [{
                        "id": "Português",
                        "lang": "pt-BR",
                        "url": "https://cdn.example/subtitle.vtt"
                    }]
                }"""
            )
        )
        assertEquals("pt-BR", subtitles.single().language)
    }

    @Test
    fun parsesStremioTorrentAndKeepsItsExplicitEpisodeFile() {
        val manifest = StremioManifest(
            id = "example.torrent",
            name = "Torrent Addon",
            types = listOf("anime"),
            resources = listOf("stream"),
            idPrefixes = listOf("mal")
        )
        val anime = Anime(
            id = 1,
            malId = 2,
            title = "Anime",
            romajiTitle = "Anime",
            englishTitle = null,
            description = "",
            cover = "",
            banner = null,
            episodes = 12,
            score = null,
            year = 2026,
            season = null,
            format = "TV",
            status = "RELEASING",
            genres = emptyList()
        )
        val release = parseStremioStreams(
            payload = JSONObject(
                """{
                    "streams": [{
                        "infoHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "fileIdx": 2,
                        "title": "Anime - 02 [1080p]",
                        "sources": ["tracker:udp://tracker.example:80"]
                    }]
                }"""
            ),
            manifest = manifest,
            manifestUrl = "https://addon.example/manifest.json",
            request = StreamRequest(anime, 2, ReleasePreferences())
        ).single()
        val files = listOf(
            TorrentFileChoice(0, "Anime - 01.mkv", 100, true),
            TorrentFileChoice(1, "Anime - 01.pt-BR.ass", 1, false),
            TorrentFileChoice(2, "Anime - 02.mkv", 100, true),
            TorrentFileChoice(3, "Anime - 02.pt-BR.ass", 1, false)
        )

        assertEquals(2, release.torrentFileIndex)
        assertTrue(release.magnetUri?.contains("urn:btih:aaaaaaaa") == true)
        assertEquals(listOf(2, 3) to 2, explicitTorrentSelection(files, 2))
    }

    @Test
    fun keepsOnlyTwoSelectedSubtitleTracks() {
        val selected = updatedSubtitleSelection(setOf("pt", "en"), "ja", true)

        assertEquals(setOf("en", "ja"), selected)
        assertEquals(setOf("ja"), updatedSubtitleSelection(selected, "en", false))
        assertEquals("Inglês", subtitleDisplayLabel("English subs", "eng", 1))
        assertEquals(
            "Português (Brasil) • OpenSubtitles",
            subtitleDisplayLabel("Portuguese (Brazil) • OpenSubtitles", "pt-BR", 2)
        )
        assertEquals("Árabe", subtitleDisplayLabel("ar", null, 3))
        assertEquals("Inglês • Forçada", subtitleDisplayLabel("Forced", "en", 4))
        assertEquals("SDH (acessibilidade)", subtitleDisplayLabel("SDH", null, 5))
        assertEquals("Chinês tradicional", subtitleDisplayLabel("Traditional", null, 6))
        assertEquals("Malaio / Indonésio", subtitleDisplayLabel("ms-ind", null, 7))
        assertEquals("Legenda 8", subtitleDisplayLabel(" ", null, 8))
    }

    @Test
    fun calculatesOpenSubtitlesHashForCompletedFile() {
        val file = File.createTempFile("kitsune-subtitle-hash", ".mkv")
        try {
            val bytes = ByteArray(128 * 1024).apply {
                this[0] = 1
                this[64 * 1024] = 2
            }
            file.writeBytes(bytes)
            assertEquals("0000000000020003", openSubtitlesHash(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun ranksConfirmedHardwareCodecAboveUnsupportedCodec() {
        val release = ParsedRelease(null, null, 1080, "HEVC", "WEB", false, false, false)
        val hardware = PlaybackCapabilities(
            h264 = PlaybackSupport.HARDWARE,
            hevc = PlaybackSupport.HARDWARE,
            hevcTenBit = PlaybackSupport.HARDWARE,
            av1 = PlaybackSupport.UNSUPPORTED,
            av1TenBit = PlaybackSupport.UNSUPPORTED
        )
        val unsupported = hardware.copy(hevc = PlaybackSupport.UNSUPPORTED)

        assertEquals(10, codecCompatibilityScore(release, hardware).points)
        assertEquals(-50, codecCompatibilityScore(release, unsupported).points)
    }

    @Test
    fun movesStremioAddonAndRewritesPriorities() {
        val configs = listOf(
            StremioAddonConfig("https://one.example/manifest.json", priority = 0),
            StremioAddonConfig("https://two.example/manifest.json", priority = 1)
        )
        val moved = moveStremioAddon(configs, configs[1].manifestUrl, -1)

        assertEquals(configs[1].manifestUrl, moved[0].manifestUrl)
        assertEquals(listOf(0, 1), moved.map(StremioAddonConfig::priority))
    }
}
