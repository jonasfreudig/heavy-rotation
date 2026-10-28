package app.recordshelf

import app.recordshelf.MiniHttp.Res
import app.recordshelf.net.LrclibClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lyrics from LRCLIB: the exact song first, else a search for a version of about the same length. */
class LyricsTest {
    @Test
    fun exactSongFirstThenTheClosestVersion() {
        val server = MiniHttp().start()
        try {
            server.handler = { r ->
                when {
                    r.path.endsWith("/get") && r.params["track_name"] == "Known" ->
                        Res(200, """{"trackName":"Known","artistName":"Band","duration":200,"instrumental":false,"plainLyrics":"la","syncedLyrics":"[00:01.00] la"}""")
                    r.path.endsWith("/get") -> Res(404, """{"code":404,"name":"TrackNotFound"}""")
                    r.params["track_name"] == "Nowhere" -> Res(200, "[]")
                    else -> Res(
                        200,
                        """[{"trackName":"Song (Live)","artistName":"Band","duration":420,"plainLyrics":"live words","syncedLyrics":null},
                            {"trackName":"Song","artistName":"Band","duration":241,"plainLyrics":"plain only","syncedLyrics":null},
                            {"trackName":"Song","artistName":"Band","duration":239,"plainLyrics":"words","syncedLyrics":"[00:12.30] words"},
                            {"trackName":"Song","artistName":"Other Band","duration":240,"plainLyrics":"wrong","syncedLyrics":"[00:01.00] wrong"}]""",
                    )
                }
            }
            val c = LrclibClient(server.base + "/api")
            val known = c.find("Band", "Known", "Album", 200)!!
            assertEquals("[00:01.00] la", known.synced)
            assertEquals("Album", server.requests.first().params["album_name"])
            val song = c.find("Band", "Song", "", 240)!!
            assertEquals("the timed version of about the same length, by the same artist", "[00:12.30] words", song.synced)
            assertTrue(!song.instrumental)
            assertNull(c.find("Band", "Nowhere", "", 100))
        } finally {
            server.stop()
        }
    }
}
