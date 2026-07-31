package com.kitsuneandroid

import org.junit.Test
import java.util.BitSet

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
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
}
