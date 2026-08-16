package com.kitsuneandroid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.BitSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun keepsReleaseRankingReasonsTyped() {
        val parsed = ParsedRelease(3, null, 1080, "H264", "WEB_DL", false, true, false)
        val capabilities = PlaybackCapabilities(
            h264 = PlaybackSupport.HARDWARE,
            hevc = PlaybackSupport.UNKNOWN,
            hevcTenBit = PlaybackSupport.UNKNOWN,
            av1 = PlaybackSupport.UNKNOWN,
            av1TenBit = PlaybackSupport.UNKNOWN
        )

        val ranking = rankRelease(parsed, 3, 42, false, false, capabilities)

        assertTrue(ReleaseReason.TitleRecognized in ranking.reasons)
        assertTrue(ReleaseReason.EpisodeMatches(3) in ranking.reasons)
        assertTrue(ReleaseReason.SeedersReported(42) in ranking.reasons)
        assertTrue(ReleaseReason.HardwareDecoding in ranking.reasons)
    }

    @Test
    fun identifiesPlaybackDecoderErrors() {
        assertTrue(isDecoderPlaybackError(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(isDecoderPlaybackError(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertFalse(isDecoderPlaybackError(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }


    @Test
    fun parsesSavedInterfaceLanguageSafely() {
        assertEquals(InterfaceLanguage.ENGLISH, parseInterfaceLanguage("ENGLISH"))
        assertEquals(InterfaceLanguage.PORTUGUESE, parseInterfaceLanguage("unsupported"))
        assertEquals(InterfaceLanguage.PORTUGUESE, parseInterfaceLanguage(null))
    }

    @Test
    fun hidesSourceCuesUntilTranslationIsReady() {
        val sourceCue = androidx.media3.common.text.Cue.Builder()
            .setText("English source")
            .build()

        assertTrue(subtitleCuesForDisplay(listOf(sourceCue), translationPending = true).isEmpty())
        assertEquals(
            listOf(sourceCue),
            subtitleCuesForDisplay(listOf(sourceCue), translationPending = false)
        )
        assertEquals(
            "English • Forced",
            subtitleDisplayLabel("Forced", "en", 1, Locale.ENGLISH)
        )
    }

    @Test
    fun preservesSimultaneousSubtitleCuesAndTheirLayout() {
        fun cue(text: String) = androidx.media3.common.text.Cue.Builder()
            .setText(text)
            .build()

        val duplicate = cue("Festival de Luta Alimentar")
        val duplicateLayers = layoutSubtitleCues(
            listOf(duplicate, duplicate)
        )
        assertEquals(1, duplicateLayers.size)

        val simultaneous = layoutSubtitleCues(
            listOf(cue("Som de fundo"), cue("Segunda fala"), cue("Diálogo principal"))
        )
        assertEquals(3, simultaneous.size)
        assertEquals(0.08f, simultaneous.first().line)
        assertEquals("Som de fundo", simultaneous.first().text.toString())
        assertEquals(0.46f, simultaneous[1].line, 0.0001f)
        assertEquals("Segunda fala", simultaneous[1].text.toString())
        assertEquals(androidx.media3.common.text.Cue.DIMEN_UNSET, simultaneous.last().line)
        assertEquals("Diálogo principal", simultaneous.last().text.toString())
    }

    @Test
    fun doesNotMovePositionedSubtitleCues() {
        val positioned = androidx.media3.common.text.Cue.Builder()
            .setText("Placa no cenário")
            .setLine(0.25f, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION)
            .setPosition(0.7f)
            .setZIndex(4)
            .build()
        val dialogue = androidx.media3.common.text.Cue.Builder()
            .setText("Diálogo")
            .build()

        val result = layoutSubtitleCues(listOf(positioned, dialogue))

        assertSame(positioned, result.first())
        assertEquals(0.25f, result.first().line)
        assertEquals(0.7f, result.first().position)
        assertEquals(4, result.first().zIndex)
        assertSame(dialogue, result.last())
    }

    @Test
    fun buffersUpcomingSubtitleCuesByLanguage() {
        val timeline = SubtitleCueTimeline()
        val englishCue = androidx.media3.common.text.Cue.Builder().setText("Future line").build()
        val portugueseCue = androidx.media3.common.text.Cue.Builder().setText("Fala futura").build()

        timeline.add(
            "eng",
            "English",
            androidx.media3.extractor.text.CuesWithTiming(listOf(englishCue), 10_000_000, 2_000_000)
        )
        timeline.add(
            "pt-BR",
            null,
            androidx.media3.extractor.text.CuesWithTiming(listOf(portugueseCue), 11_000_000, 2_000_000)
        )

        assertEquals(listOf(listOf(englishCue)), timeline.upcoming("en", 9_000_000))
    }

    @Test
    fun keepsLongSubtitleCueAvailableAfterSeekingIntoIt() {
        val timeline = SubtitleCueTimeline()
        val cue = androidx.media3.common.text.Cue.Builder().setText("Long explanation").build()

        timeline.add(
            "eng",
            "English",
            androidx.media3.extractor.text.CuesWithTiming(listOf(cue), 10_000_000, 8_000_000)
        )

        assertEquals(listOf(listOf(cue)), timeline.upcoming("en", 16_000_000))
    }

    @Test
    fun showsTorrentPiecesOnlyWhileStreamingAnIncompleteDownload() {
        assertTrue(shouldShowTorrentPieces("kitsune-stream", TorrentStatus.DOWNLOADING))
        assertFalse(shouldShowTorrentPieces("file", TorrentStatus.DOWNLOADING))
        assertFalse(shouldShowTorrentPieces("kitsune-stream", TorrentStatus.COMPLETED))
        assertFalse(shouldShowTorrentPieces("content", null))
    }

    @Test
    fun parsesJikanFallbackAnime() {
        val anime = parseJikanAnime(JSONObject("""{
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
    fun parsesNekoBtReleaseAndFiltersTheEpisode() {
        val payload = JSONObject("""{
            "data": {"results": [{
                "id": "10498884588040",
                "title": "[Erai-raws] Liar Game - 03 [1080p][MultiSub]",
                "infohash": "0123456789abcdef0123456789abcdef01234567",
                "magnet": "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                "filesize": "1465314114",
                "sub_lang": "en,pt-br",
                "audio_lang": "ja",
                "seeders": "37",
                "leechers": "2"
            }]}
        }""")

        val release = parseNekoBt(payload, listOf("Liar Game"), 3).single()

        assertEquals("nekobt", release.providerId)
        assertEquals(37, release.seeders)
        assertTrue(release.parsed.ptBr)
        assertEquals("https://nekobt.to/torrents/10498884588040", release.sourceUrl)
        assertTrue(parseNekoBt(payload, listOf("Liar Game"), 4).isEmpty())
    }

    @Test
    fun recognizesWhetherAReleaseContainsTheRequestedEpisode() {
        assertTrue(releaseContainsEpisode(parseReleaseTitle("Cowboy Bebop 01-26"), 1))
        assertTrue(releaseContainsEpisode(parseReleaseTitle("Cowboy Bebop Complete Series"), 1))
        assertFalse(releaseContainsEpisode(parseReleaseTitle("Cowboy Bebop Movie"), 1))
    }

    @Test
    fun findsTheRequestedSeasonAndSelectsOnlyItsEpisodeFiles() {
        val anime = Anime(
            180745, null, "Classroom of the Elite 4th Season", "Youkoso Jitsuryoku Shijou Shugi no Kyoushitsu e 4th Season",
            "Classroom of the Elite 4th Season: Second Year, First Semester", "", "https://example.com/cover.jpg", null,
            16, null, 2026, "SPRING", "TV", null, emptyList(), listOf("Classroom of the Elite Season 4")
        )
        assertTrue(releaseSearchQueries(anime, 16).any { it.endsWith("S04E16") })
        val firstSeason = anime.copy(
            title = "Liar Game",
            romajiTitle = "Liar Game",
            englishTitle = "Liar Game",
            aliases = emptyList(),
            seasonNumber = 1
        )
        val firstSeasonQueries = releaseSearchQueries(firstSeason, 3)
        assertEquals("Liar Game 03", firstSeasonQueries.first())
        assertTrue(firstSeasonQueries.size <= 4)
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
    fun pausesPlaybackOnlyAfterLeavingPictureInPicture() {
        assertFalse(shouldPausePlaybackWhenStopped(videoPlaying = true, inPictureInPictureMode = true))
        assertTrue(shouldPausePlaybackWhenStopped(videoPlaying = true, inPictureInPictureMode = false))
        assertFalse(shouldPausePlaybackWhenStopped(videoPlaying = false, inPictureInPictureMode = false))
    }

    @Test
    fun acceptsOnlyValidAnimeIdsFromNotifications() {
        assertEquals(42, validNotificationAnimeId(42))
        assertNull(validNotificationAnimeId(0))
        assertNull(validNotificationAnimeId(-1))
    }

    @Test
    fun normalizesPlaybackSpeedToSupportedQuarterSteps() {
        assertEquals(0.5f, normalizePlaybackSpeed(0.1f))
        assertEquals(1.25f, normalizePlaybackSpeed(1.32f))
        assertEquals(2f, normalizePlaybackSpeed(3f))
        assertEquals(VideoScale.ZOOM, parseVideoScale("ZOOM"))
        assertEquals(VideoScale.FIT, parseVideoScale("unsupported"))
    }

    @Test
    fun formatsAudioTracksWithoutRepeatingTheLanguage() {
        assertEquals("Português (Brasil) • Dublado", audioDisplayLabel("Dublado", "pt-BR", 1))
        assertEquals("Inglês", audioDisplayLabel("en", "en", 2))
        assertEquals("Áudio 3", audioDisplayLabel(null, null, 3))
    }

    @Test
    fun findsEpisodeHistoryByCatalogIdentityOrOfflineUri() {
        val direct = WatchedVideo(
            uri = "https://video.example/episode-3",
            title = "Release",
            positionMs = 60_000,
            watchedAt = 2,
            episode = 3,
            animeId = 42
        )
        val legacyOffline = WatchedVideo(
            uri = "file:///downloads/episode-4.mkv",
            title = "Episode 4",
            positionMs = 90_000,
            watchedAt = 1
        )

        assertEquals(direct, historyForEpisode(listOf(direct, legacyOffline), 42, 3))
        assertEquals(
            legacyOffline,
            historyForEpisode(listOf(direct, legacyOffline), 42, 4, legacyOffline.uri)
        )
        assertNull(historyForEpisode(listOf(direct, legacyOffline), 42, 5))
        assertTrue(historyMatchesEpisode(direct, 42, 3, null))
        assertTrue(historyMatchesEpisode(legacyOffline, null, null, legacyOffline.uri))
        assertFalse(historyMatchesEpisode(direct, 42, 4, null))
    }

    @Test
    fun choosesFullSubtitleTrackForPersistentTranslation() {
        val forced = SubtitleTrackOption(0, 0, "English Forced", "en", true, true)
        val full = SubtitleTrackOption(1, 0, "English", "en", true, true)

        assertEquals(full, preferredSubtitleSource(listOf(forced, full), "pt"))
        assertNull(preferredSubtitleSource(listOf(full.copy(language = "pt")), "pt"))
    }

    @Test
    fun separatesMovieAndSeriesCatalogItems() {
        val series = Anime(
            id = 1,
            malId = null,
            title = "Title",
            romajiTitle = "Title",
            englishTitle = null,
            description = "",
            cover = "",
            banner = null,
            episodes = null,
            score = null,
            year = null,
            season = null,
            format = "TV",
            status = null,
            genres = emptyList(),
            remoteMediaType = "series"
        )
        val movie = series.copy(format = "MOVIE", remoteMediaType = "movie")

        assertTrue(CatalogSection.SERIES.accepts(series))
        assertFalse(CatalogSection.ANIME.accepts(series))
        assertFalse(CatalogSection.SERIES.accepts(movie))
        assertTrue(CatalogSection.MOVIES.accepts(movie))
    }

    @Test
    fun classifiesStremioCatalogsWithoutMixingAnimeAndSeries() {
        fun catalog(id: String, type: String, name: String) = StremioCatalog(
            id = id,
            type = type,
            name = name,
            supportsSearch = true,
            searchRequired = false
        )

        assertEquals(CatalogSection.ANIME, stremioCatalogSection(catalog("anime", "series", "Anime")))
        assertEquals(CatalogSection.SERIES, stremioCatalogSection(catalog("popular", "series", "Popular")))
        assertEquals(CatalogSection.MOVIES, stremioCatalogSection(catalog("movies", "movie", "Movies")))
    }

    @Test
    fun notificationCountStopsBeforeTheNextUnairedEpisode() {
        val airing = Anime(
            id = 1,
            malId = null,
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
            genres = emptyList(),
            nextAiringEpisode = 8
        )

        assertEquals(7, EpisodeUpdateNotifications.releasedEpisodes(airing))
        assertEquals(
            0,
            EpisodeUpdateNotifications.releasedEpisodes(airing.copy(status = "NOT_YET_RELEASED"))
        )
    }

    @Test
    fun formatsStorageAndPlaybackDurations() {
        assertEquals("0 B", formatBytes(-1))
        assertTrue(formatBytes(1_024).endsWith(" KiB"))
        assertEquals("01:01:01", formatDuration(3_661_000))
    }

    @Test
    fun filtersOfflineLibraryAndHistoryWithoutCaseSensitivity() {
        assertTrue(matchesLibraryQuery("bebop", "Cowboy Bebop", null))
        assertTrue(matchesLibraryQuery("  ", null))
        assertFalse(matchesLibraryQuery("episode 9", "Episode 2", "Anime"))
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
    fun mapsCompletedTorrentPiecesToPlayerTimelineBuckets() {
        val completed = BitSet().apply {
            set(1)
            set(2)
        }
        val snapshot = TorrentStreamSnapshot(1_000, 12_000, 4_000, completed)

        assertArrayEquals(
            floatArrayOf(0.25f, 1f, 0.75f),
            snapshot.downloadedFractions(3),
            0.001f
        )
    }

    @Test
    fun comparesAppVersions() {
        assertTrue(isNewerVersion("v1.2.0", "1.1"))
        assertFalse(isNewerVersion("v1.2.0", "1.2"))
        assertTrue(parseReleaseTitle("Anime - 01 [1080p AVC Hi10P]").tenBit)
    }

    @Test
    fun reusesAnActiveOrCompletedUpdateDownload() {
        assertTrue(AppDownloadState(android.app.DownloadManager.STATUS_RUNNING, 10, 100).reusable)
        assertTrue(AppDownloadState(android.app.DownloadManager.STATUS_SUCCESSFUL, 100, 100).reusable)
        assertFalse(AppDownloadState(android.app.DownloadManager.STATUS_FAILED, 10, 100).reusable)
    }

    @Test
    fun selectsTheSmallestCompatibleUpdateApk() {
        val assets = listOf(
            "Kitsune-v1.4.0.apk",
            "Kitsune-v1.4.0-armeabi-v7a.apk",
            "Kitsune-v1.4.0-arm64-v8a.apk",
            "Kitsune-v1.4.0-x86_64.apk"
        )

        assertEquals(
            "Kitsune-v1.4.0-arm64-v8a.apk",
            preferredApkAsset(assets, listOf("arm64-v8a", "armeabi-v7a"))
        )
        assertEquals("Kitsune-v1.4.0.apk", preferredApkAsset(assets, listOf("riscv64")))
        assertNull(preferredApkAsset(listOf("Kitsune-v1.4.0-arm64-v8a-unsigned.apk"), listOf("arm64-v8a")))
    }

    @Test
    fun startsProviderQueriesInParallel() = runBlocking {
        val started = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()

        val results = withTimeout(2_000) {
            parallelProviderRequests(listOf(1, 2, 3)) { value ->
                if (started.incrementAndGet() == 3) {
                    allStarted.complete(Unit)
                }
                allStarted.await()
                value * 2
            }
        }

        assertEquals(listOf(2, 4, 6), results)
    }

    @Test
    fun limitsConcurrentProviderQueries() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()

        parallelProviderRequests((1..8).toList(), maxConcurrency = 2) {
            val current = running.incrementAndGet()
            peak.updateAndGet { previous -> maxOf(previous, current) }
            delay(10)
            running.decrementAndGet()
        }

        assertEquals(2, peak.get())
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
    fun roundTripsLocalMediaLists() {
        val anime = Anime(
            1, null, "Anime", "Anime", null, "", "cover", null,
            12, 80, 2026, "SUMMER", "TV", "RELEASING", listOf("Comedy")
        )
        val lists = listOf(MediaList("watching", "Assistindo", listOf(anime)))

        assertEquals(lists, decodeMediaLists(encodeMediaLists(lists)))
    }

    @Test
    fun excludesDisposableStateFromUserBackups() {
        val preferences = linkedMapOf<String, Any>(
            "favorites" to setOf("1"),
            "video_history" to "[]",
            "catalog_cache:PORTUGUESE" to "large cache",
            "performance_metrics" to "[]",
            "update_download_id" to 42L
        )

        assertEquals(
            setOf("favorites", "video_history"),
            userDataPreferences(preferences).keys
        )
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
    fun hidesEpisodesThatHaveNotAiredYet() {
        val anime = Anime(
            207141, 63403, "Anime", "Anime", "Anime", "", "", null,
            12, null, 2026, "SUMMER", "TV", "RELEASING", emptyList(), nextAiringEpisode = 8
        )
        val listed = (1..12).map { number ->
            Episode(number, "Episode $number", null, null, null, null, false, false, null, null)
        }

        assertEquals((1..7).toList(), completeEpisodeList(anime, listed).map(Episode::number))
    }

    @Test
    fun usesKnownEpisodeCountWhenAiringMetadataIsUnavailable() {
        val anime = Anime(
            207142, 63404, "Saga of Tanya the Evil II", "Youjo Senki II", null, "", "", null,
            12, null, 2026, "SUMMER", "TV", "RELEASING", emptyList()
        )

        assertEquals(
            (1..12).toList(),
            completeEpisodeList(anime, emptyList()).map(Episode::number)
        )
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
        assertEquals(
            "4",
            recommendedRelease(
                releases = releases,
                preferences = ReleasePreferences(ReleaseLanguage.PORTUGUESE, 1080),
                externalSubtitlesMatchPreference = true
            )?.id
        )
    }

    @Test
    fun sortsReleaseOptionsBySelectedPriority() {
        fun release(id: String, size: Long, seeders: Int, score: Int): ReleaseCandidate {
            val title = "Anime - 01 [1080p H264]"
            return ReleaseCandidate(
                id = id,
                title = title,
                infoHash = id.padEnd(40, '0'),
                sizeBytes = size,
                seeders = seeders,
                leechers = 0,
                trusted = true,
                remake = false,
                parsed = parseReleaseTitle(title),
                score = score,
                reasons = emptyList()
            )
        }

        val small = release("small", 500, 5, 80)
        val seeded = release("seeded", 2_000, 50, 70)
        val unknownSize = release("unknown", 0, 20, 100)
        val releases = listOf(small, seeded, unknownSize)

        assertEquals(
            listOf("seeded", "small", "unknown"),
            sortedReleaseOptions(releases, "seeded", ReleaseSort.RECOMMENDED).map(ReleaseCandidate::id)
        )
        assertEquals(
            listOf("seeded", "unknown", "small"),
            sortedReleaseOptions(releases, null, ReleaseSort.SEEDERS).map(ReleaseCandidate::id)
        )
        assertEquals(
            listOf("small", "seeded", "unknown"),
            sortedReleaseOptions(releases, null, ReleaseSort.SIZE).map(ReleaseCandidate::id)
        )
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
            providerId = "nekobt",
            providerIds = setOf("nekobt")
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
                assertEquals(setOf("nyaa", "nekobt"), merged.value.single().providerIds)
                assertTrue(releaseSummary(merged.value.single()).startsWith("Nyaa + nekoBT"))
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
    fun identifiesFailedProviderInUserFacingDiagnostics() {
        val failure = ProviderResult.Failure(
            providerId = "stremio:torrentio",
            message = "HTTP 522"
        )

        assertEquals(
            "Addon Stremio: HTTP 522",
            providerFailureMessage(failure)
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
        val anime = Anime(
            1, 2, "Título", "Titulo", "Title", "Descrição", "cover", "banner",
            12, 90, 2026, "SUMMER", "TV", "RELEASING", listOf("Ação"), listOf("Alias"), 6,
            remoteMediaId = "anime:42",
            remoteMediaType = "series",
            remoteManifestUrl = "https://addon.example/manifest.json"
        )
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
    fun loadsTheNextCatalogPageNearTheEndOfTheGrid() {
        assertTrue(
            shouldLoadNextCatalogPage(
                lastVisibleIndex = 13,
                itemCount = 20,
                canLoadMore = true,
                loadingMore = false
            )
        )
        assertFalse(
            shouldLoadNextCatalogPage(
                lastVisibleIndex = 12,
                itemCount = 20,
                canLoadMore = true,
                loadingMore = false
            )
        )
        assertFalse(
            shouldLoadNextCatalogPage(
                lastVisibleIndex = 19,
                itemCount = 20,
                canLoadMore = true,
                loadingMore = true
            )
        )
    }

    @Test
    fun startsMagnetsBySearchingForPeersInsteadOfQueuing() {
        assertEquals(
            TorrentStatus.SEARCHING_PEERS,
            initialTorrentStatus("magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        )
        assertEquals(TorrentStatus.QUEUED, initialTorrentStatus(null))
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
                    "completedFileIndices": [1, 3],
                    "magnetUri": "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "providerId": "stremio:torrentio"
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
        assertTrue(currentDownload.magnetUri?.startsWith("magnet:") == true)
        assertEquals("stremio:torrentio", currentDownload.providerId)
        assertTrue(legacyDownload.completedFileIndices.isEmpty())
        assertEquals("nyaa", legacyDownload.providerId)
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
            normalizeRemoteProviderUrl("https://addon.example/")
        )
        assertThrows(IllegalArgumentException::class.java) {
            normalizeRemoteProviderUrl("http://127.0.0.1:7000/manifest.json")
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
    fun rejectsNonPublicProviderAddresses() {
        assertFalse(isPublicNetworkAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(isPublicNetworkAddress(InetAddress.getByName("10.20.30.40")))
        assertFalse(isPublicNetworkAddress(InetAddress.getByName("169.254.10.20")))
        assertFalse(isPublicNetworkAddress(InetAddress.getByName("100.64.0.1")))
        assertFalse(isPublicNetworkAddress(InetAddress.getByName("fc00::1")))
        assertTrue(isPublicNetworkAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(isPublicNetworkAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun cachesRemoteManifestsWithoutSharingMutableJson() {
        val url = "https://cache-${System.nanoTime()}.example/manifest.json"
        var fetches = 0
        val fetch = { _: String ->
            fetches++
            JSONObject().put("version", fetches)
        }

        val first = cachedRemoteManifestJson(url, 0, fetch)
        first.put("version", 99)
        val cached = cachedRemoteManifestJson(url, 1, fetch)
        val expired = cachedRemoteManifestJson(url, 16 * 60_000L, fetch)

        assertEquals(1, cached.getInt("version"))
        assertEquals(2, expired.getInt("version"))
        assertEquals(2, fetches)
    }

    @Test
    fun boundsAndDeduplicatesTheStartupCatalogCache() {
        val anime = (1..100).map { id ->
            Anime(
                id = id,
                malId = null,
                title = "Anime $id",
                romajiTitle = "Anime $id",
                englishTitle = null,
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

        val cached = catalogItemsForCache(listOf(anime.first()) + anime)

        assertEquals(90, cached.size)
        assertEquals((1..90).toList(), cached.map(Anime::id))
    }

    @Test
    fun readsTorrentioResourcePrefixesAndBuildsItsKitsuEpisodeId() {
        val manifest = parseStremioManifest(
            JSONObject(
                """{
                    "id": "com.stremio.torrentio.addon",
                    "name": "Torrentio",
                    "types": ["movie", "series", "anime"],
                    "resources": [{
                        "name": "stream",
                        "types": ["movie", "series", "anime"],
                        "idPrefixes": ["tt", "kitsu"]
                    }]
                }"""
            )
        )
        val kitsuAnime = Anime(
            id = -1_000_046_434,
            malId = null,
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
            status = "FINISHED",
            genres = emptyList()
        )

        assertEquals(listOf("tt", "kitsu"), manifest.idPrefixes)
        assertEquals(
            listOf("kitsu:46434:3"),
            stremioIds(
                request = StreamRequest(kitsuAnime, 3, ReleasePreferences()),
                supportedPrefixes = manifest.idPrefixes,
                manifestUrl = "https://torrentio.strem.fun/manifest.json"
            )
        )
        assertEquals(
            "46434",
            parseKitsuMapping(
                JSONObject(
                    """{
                        "data": [{
                            "relationships": {
                                "item": {"data": {"type": "anime", "id": "46434"}}
                            }
                        }]
                    }"""
                )
            )
        )
    }

    @Test
    fun parsesCatalogCapabilityAndAnimeFromAStremioAddon() {
        val manifest = parseStremioManifest(
            JSONObject(
                """{
                    "id": "example.catalog",
                    "name": "Anime Catalog",
                    "types": ["series"],
                    "resources": ["catalog", "meta", "stream"],
                    "catalogs": [{
                        "id": "anime",
                        "type": "series",
                        "name": "Anime",
                        "extra": [{"name": "search", "isRequired": false}]
                    }]
                }"""
            )
        )
        val anime = parseStremioCatalog(
            JSONObject(
                """{
                    "metas": [{
                        "id": "anime:42",
                        "type": "series",
                        "name": "Example Anime",
                        "poster": "https://cdn.example/poster.jpg",
                        "releaseInfo": "2026",
                        "genres": ["Action"]
                    }]
                }"""
            ),
            "https://addon.example/manifest.json",
            "series"
        ).single()

        assertTrue(manifest.catalogs.single().supportsSearch)
        assertEquals("anime:42", anime.remoteMediaId)
        assertEquals("TV", anime.format)
        assertEquals(2026, anime.year)
    }

    @Test
    fun buildsEpisodeIdForImportedStremioSeriesWithoutVideoMetadata() {
        val anime = Anime(
            id = -1,
            malId = null,
            title = "Imported series",
            romajiTitle = "Imported series",
            englishTitle = null,
            description = "",
            cover = "",
            banner = null,
            episodes = 12,
            score = null,
            year = null,
            season = null,
            format = "TV",
            status = null,
            genres = emptyList(),
            seasonNumber = 2,
            remoteMediaId = "tt1234567",
            remoteMediaType = "series",
            remoteManifestUrl = "https://addon.example/manifest.json"
        )

        assertEquals(
            listOf("tt1234567:2:3"),
            stremioIds(
                request = StreamRequest(anime, 3, ReleasePreferences()),
                supportedPrefixes = listOf("tt"),
                manifestUrl = "https://addon.example/manifest.json"
            )
        )
    }

    @Test
    fun reusesImportedCatalogIdWithAnotherStremioStreamProvider() {
        val anime = Anime(
            id = -2,
            malId = null,
            title = "Imported series",
            romajiTitle = "Imported series",
            englishTitle = null,
            description = "",
            cover = "",
            banner = null,
            episodes = 12,
            score = null,
            year = null,
            season = null,
            format = "TV",
            status = null,
            genres = emptyList(),
            seasonNumber = 3,
            remoteMediaId = "tt7654321",
            remoteMediaType = "series",
            remoteManifestUrl = "https://catalog.example/manifest.json"
        )

        assertEquals(
            listOf("tt7654321:3:4"),
            stremioIds(
                request = StreamRequest(anime, 4, ReleasePreferences()),
                supportedPrefixes = listOf("tt"),
                manifestUrl = "https://stream.example/manifest.json"
            )
        )
        assertEquals(
            "series",
            stremioStreamType(
                anime,
                StremioManifest(
                    id = "stream",
                    name = "Stream provider",
                    types = listOf("anime", "series"),
                    resources = listOf("stream"),
                    idPrefixes = listOf("tt")
                )
            )
        )
        assertEquals(
            "https://stream.example/stream/series/tt7654321%3A3%3A4.json",
            stremioResourceUrl(
                manifestUrl = "https://stream.example/manifest.json",
                resource = "stream",
                type = "series",
                mediaId = "tt7654321:3:4"
            )
        )
    }

    @Test
    fun preparesVisibleDownloadNamesAndOptionalNyaaCategories() {
        assertEquals(
            "Anime Episode 01.torrent",
            safeDownloadName("Anime: Episode 01", "torrent")
        )
        assertEquals(
            listOf("1_0"),
            NyaaSource.SUKEBEI.categories(ReleaseLanguage.ANY)
        )
        assertEquals(
            listOf("1_3", "1_2"),
            NyaaSource.NYAA.categories(ReleaseLanguage.JAPANESE)
        )
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
        assertTrue(release.magnetUri?.contains("tracker.opentrackr.org") == true)
        assertEquals(listOf(2, 3) to 2, explicitTorrentSelection(files, 2))
    }

    @Test
    fun keepsOnlyOneSelectedSubtitleTrack() {
        val selected = updatedSubtitleSelection(setOf("pt", "en"), "ja", true)

        assertEquals(setOf("ja"), selected)
        assertEquals(setOf("ja"), updatedSubtitleSelection(selected, "en", false))
        assertEquals(
            "https://api.opensubtitles.com/api/v1",
            openSubtitlesApiBaseUrl("https://attacker.example")
        )
        assertEquals(
            "https://vip-api.opensubtitles.com/api/v1",
            openSubtitlesApiBaseUrl("vip-api.opensubtitles.com")
        )
        assertEquals("Inglês", subtitleDisplayLabel("English subs", "eng", 1))
        assertEquals(
            "Português (Brasil) • OpenSubtitles",
            subtitleDisplayLabel("Portuguese (Brazil) • OpenSubtitles", "pt-BR", 2)
        )
        assertEquals(
            "Português (Brasil) • SubDL",
            subtitleDisplayLabel("Português (Brasil) • SubDL", "pt-BR", 3)
        )
        assertEquals(
            "Português (Brasil) • Tradução automática",
            subtitleDisplayLabel(
                "Português (Brasil) • Tradução automática de Japonês • Google Translate",
                "pt-BR",
                4
            )
        )
        assertEquals("Árabe", subtitleDisplayLabel("ar", null, 3))
        assertEquals("Inglês • Forçada", subtitleDisplayLabel("Forced", "en", 4))
        assertEquals("SDH (acessibilidade)", subtitleDisplayLabel("SDH", null, 5))
        assertEquals("Chinês tradicional", subtitleDisplayLabel("Traditional", null, 6))
        assertEquals("Malaio / Indonésio", subtitleDisplayLabel("ms-ind", null, 7))
        assertEquals("Legenda 8", subtitleDisplayLabel(" ", null, 8))
    }

    @Test
    fun preservesEveryCueInAContextualTranslationBatch() {
        val source = listOf(
            listOf("Correct. And season two has started airing in January 2025."),
            listOf(
                "Which means... way more than three months have passed!",
                "No way!"
            )
        )
        val encoded = encodeSubtitleTranslationBatch(source)
        val translated = "\uE0000:0\uE001\n" +
            "Correto. E a segunda temporada começou em janeiro de 2025.\n" +
            "\uE0001:0\uE001\n" +
            "O que significa... já se passaram muito mais de três meses!\n" +
            "\uE0001:1\uE001\n" +
            "De jeito nenhum!"

        assertTrue(encoded.contains("\uE0000:0\uE001"))
        assertEquals(
            listOf(
                listOf("Correto. E a segunda temporada começou em janeiro de 2025."),
                listOf(
                    "O que significa... já se passaram muito mais de três meses!",
                    "De jeito nenhum!"
                )
            ),
            decodeSubtitleTranslationBatch(translated, source)
        )
    }

    @Test
    fun ordersSubtitleContextAroundTheCurrentDialogue() {
        fun cue(text: String) = listOf(
            androidx.media3.common.text.Cue.Builder().setText(text).build()
        )

        val previousTwo = cue("Who will be next?")
        val previous = cue("Kusuri is next! Can you tell which one is Kusuri?!")
        val current = cue("Uh, you just gave it away.")
        val next = cue("Did I?")
        val later = cue("Yes, you did.")

        val context = subtitleTranslationContext(
            current = current,
            chronological = listOf(previousTwo, previous, current, next, later)
        )

        assertEquals(
            listOf(previousTwo, previous, current, next, later),
            context
        )
    }

    @Test
    fun limitsSubtitleContextWithoutSkippingAdjacentDialogue() {
        fun cue(text: String) = listOf(
            androidx.media3.common.text.Cue.Builder().setText(text).build()
        )
        val previous = cue("Previous")
        val current = cue("Current")
        val oversizedNext = cue("x".repeat(700))
        val later = cue("Later")

        assertEquals(
            listOf(previous, current),
            subtitleTranslationContext(
                current = current,
                chronological = listOf(previous, current, oversizedNext, later)
            )
        )
    }

    @Test
    fun preservesMultiWordNamesAndTitlesDuringTranslation() {
        val marked = markSubtitleTitles(
            "They switched places? Like in Your Name?"
        )
        val translated = marked.text
            .replace("They switched places? Like in ", "Trocaram de lugar? Como em ")
            .replace("Your Name", "seu nome")

        assertEquals(1, marked.titleCount)
        assertEquals(
            "Trocaram de lugar? Como em Seu Nome?",
            restoreSubtitleTitleCase(translated, marked.titleCount)
        )
    }

    @Test
    fun doesNotProtectEnglishContractionsAsTitles() {
        val source = "Except I'm not wearing any diapers!"

        val marked = markSubtitleTitles(source)

        assertEquals(source, marked.text)
        assertEquals(0, marked.titleCount)
    }

    @Test
    fun keepsVisibleEpisodeMetadataStableWhileEnrichingDetails() {
        val initial = Episode(
            number = 1,
            title = "Cousin and Girlfriend",
            japaneseTitle = null,
            romanjiTitle = null,
            airedAt = null,
            durationSeconds = null,
            filler = false,
            recap = false,
            synopsis = null,
            thumbnail = null
        )
        val fetched = initial.copy(
            title = "My Cousin Girlfriend",
            synopsis = "Sinopse carregada",
            thumbnail = "details-image"
        )

        val merged = mergeEpisodeDetails(
            initial = initial,
            fetched = fetched,
            displayedThumbnail = "anime-banner"
        )

        assertEquals("Cousin and Girlfriend", merged.title)
        assertEquals("anime-banner", merged.thumbnail)
        assertEquals("Sinopse carregada", merged.synopsis)
    }

    @Test
    fun selectsTheExactEpisodeFromASubDlSeasonPack() {
        val response = JSONObject(
            """{
                "status": true,
                "subtitles": [{
                    "name": "Season Pack.zip",
                    "unpack_files": [
                        {
                            "name": "Anime.S01E01.srt",
                            "release_name": "CR WEB-DL",
                            "episode": 1,
                            "language": "BR_PT",
                            "format": "srt",
                            "url": "/subtitle/pack/episode-1"
                        },
                        {
                            "name": "Anime.S01E02.srt",
                            "release_name": "CR WEB-DL",
                            "episode": 2,
                            "language": "BR_PT",
                            "format": "srt",
                            "url": "/subtitle/pack/episode-2"
                        }
                    ]
                }]
            }"""
        )
        val request = SubtitleSearchRequest(
            title = "Anime",
            episode = 2,
            language = "pt-br",
            videoFile = null,
            videoName = "Anime S01E02 CR WEB-DL.mkv",
            videoFps = 23.976f
        )

        val selected = requireNotNull(selectSubDlCandidate(response, request))

        assertEquals(2, selected.episode)
        assertEquals("https://dl.subdl.com/subtitle/pack/episode-2", selected.url)
        assertTrue(selected.directFile)
        assertEquals(2, subDlSeasonNumber("Anime S2 - 02 [1080p].mkv"))
    }

    @Test
    fun identifiesSubtitleLanguagesForLiveTranslation() {
        assertEquals("en", subtitleTranslationLanguage("eng", "Inglês"))
        assertEquals("pt", subtitleTranslationLanguage("pt-BR", "Português (Brasil)"))
        assertEquals("th", subtitleTranslationLanguage(null, "Tailandês"))
        assertNull(subtitleTranslationLanguage(null, "Desconhecida"))
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
    fun selectsSubtitleMatchingTheVideoReleaseAndFrameRate() {
        val search = JSONObject(
            """{
                "data": [
                    {"attributes": {
                        "release": "Anime S01E01 25fps-RandomGroup",
                        "fps": 25.0,
                        "download_count": 5000,
                        "files": [{"file_id": 1, "file_name": "random.srt"}]
                    }},
                    {"attributes": {
                        "release": "Anime S01E01 1080p WEBRip-SubsPlease",
                        "fps": 23.976,
                        "download_count": 20,
                        "files": [{"file_id": 2, "file_name": "subsplease.srt"}]
                    }}
                ]
            }"""
        )

        val selected = selectOpenSubtitleCandidate(
            search = search,
            title = "Anime",
            videoName = "[SubsPlease] Anime - 01 (1080p) [ABC123].mkv",
            videoFps = 23.976f
        )

        assertEquals(2, selected?.file?.optInt("file_id"))
        assertEquals(25.0 to 23.976f.toDouble(), subtitleFpsConversion(23.976f, 25.0))
        assertNull(subtitleFpsConversion(23.976f, 23.98))
    }

    @Test
    fun parsesInstallableKitsuneProviderAndItsDirectStream() {
        val manifest = parseKitsuneManifest(
            JSONObject(
                """{
                    "schema": "kitsune-addon/v1",
                    "id": "org.example.anime",
                    "version": "1.0.0",
                    "name": "Example Provider",
                    "capabilities": ["catalog", "metadata", "streams"],
                    "acceptedIds": ["anilist", "mal"],
                    "endpoints": {
                        "catalog": "/v1/catalog",
                        "metadata": "/v1/meta",
                        "streams": "/v1/streams"
                    }
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
            cover = "",
            banner = null,
            episodes = 12,
            score = null,
            year = 2026,
            season = null,
            format = "TV",
            status = null,
            genres = emptyList()
        )
        val config = RemoteProviderConfig(
            manifestUrl = "https://provider.example/manifest.json",
            protocol = RemoteProviderProtocol.KITSUNE,
            providerId = manifest.id
        )
        val releases = parseKitsuneStreams(
            payload = JSONObject(
                """{
                    "streams": [{
                        "id": "release:1",
                        "source": {
                            "kind": "http",
                            "url": "https://cdn.example/episode-1.mkv"
                        },
                        "release": {
                            "title": "Anime - 01 [1080p]"
                        },
                        "availability": {
                            "seeders": 15
                        }
                    }]
                }"""
            ),
            manifest = manifest,
            config = config,
            request = StreamRequest(anime, 1, ReleasePreferences())
        )

        assertEquals(RemoteProviderProtocol.KITSUNE, manifest.descriptor().protocol)
        assertEquals("https://cdn.example/episode-1.mkv", releases.single().directUrl)
        assertEquals("kitsune:org.example.anime", releases.single().providerId)
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
    fun movesRemoteProviderAndRewritesPriorities() {
        val configs = listOf(
            RemoteProviderConfig("https://one.example/manifest.json", priority = 0),
            RemoteProviderConfig("https://two.example/manifest.json", priority = 1)
        )
        val moved = moveRemoteProvider(configs, configs[1].manifestUrl, -1)

        assertEquals(configs[1].manifestUrl, moved[0].manifestUrl)
        assertEquals(listOf(0, 1), moved.map(RemoteProviderConfig::priority))
    }
}
