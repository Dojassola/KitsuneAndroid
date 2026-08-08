package com.kitsuneandroid

import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    fun mergesProviderStreamsWithoutHidingEmptyAndFailureStates() {
        fun release(id: String, score: Int) = ReleaseCandidate(
            id, "Anime - 01 [1080p H264]", id.padEnd(40, '0'), 1_000, 10, 0, true, false,
            parseReleaseTitle("Anime - 01 [1080p H264]"), score, emptyList()
        )
        val best = release("1", 100)
        val duplicate = best.copy(score = 50)

        val merged = mergeStreamResults(listOf(ProviderResult.Success(listOf(best)), ProviderResult.Success(listOf(duplicate))))
        assertEquals(listOf(best), (merged as ProviderResult.Success).value)
        assertEquals(ProviderResult.Empty, mergeStreamResults(listOf(ProviderResult.Empty)))
        assertEquals(
            ProviderResult.Failure("provider", "offline"),
            mergeStreamResults(listOf(ProviderResult.Empty, ProviderResult.Failure("provider", "offline")))
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
    fun offersIntroSkipOnlyInsideOpeningChapter() {
        val opening = MediaChapter("Creditless Opening", 35_000, 125_000)
        assertEquals(opening, introChapterAt(listOf(opening), 60_000))
        assertEquals(null, introChapterAt(listOf(opening), 130_000))
        assertEquals("OP 2", introChapterAt(listOf(MediaChapter("OP 2", 0, 90_000)), 1_000)?.title)
    }
}
