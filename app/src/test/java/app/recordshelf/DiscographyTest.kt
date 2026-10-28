package app.recordshelf

import app.recordshelf.MiniHttp.Res
import app.recordshelf.discogs.CollectionItem
import app.recordshelf.history.Discography
import app.recordshelf.history.Genres
import app.recordshelf.history.History
import app.recordshelf.history.Stats
import app.recordshelf.net.DeezerClient
import app.recordshelf.net.DzRelease
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class DiscographyTest {
    private fun rel(id: Long, title: String, type: String, date: String) = DzRelease(id, title, type, date, "cover$id", "link$id")

    @Test
    fun editionsFoldIntoTheirRecord() {
        val e = Discography.merge(
            listOf(
                rel(1, "Dopamine Chamber", "album", "2026-10-16"),
                rel(2, "Romance", "album", "2024-08-23"),
                rel(3, "Romance (Deluxe Edition)", "album", "2025-04-18"),
                rel(4, "Starburster", "single", "2024-04-17"),
                rel(5, "Skinty Fia (Edit)", "single", "2022-03-21"),
                rel(6, "Skinty Fia", "single", "2022-03-20"),
                rel(7, "In The Modern World", "ep", "2024-08-20"),
                rel(8, "Skinty Fia", "album", "2022-04-22"),
            ),
        )
        assertEquals(
            "albums first, newest first; then EPs, then singles",
            listOf("Dopamine Chamber", "Romance", "Skinty Fia", "In The Modern World", "Starburster", "Skinty Fia"),
            e.map { it.release.title },
        )
        assertEquals("the deluxe edition counts for Romance", 2, e[1].editions.size)
        assertEquals("the earliest edition is the record", 6L, e[5].release.id)

        val shelfId = mapOf("Romance (Deluxe Edition)" to 4242L)
        val plays = mapOf("Romance" to 120, "Romance (Deluxe Edition)" to 120, "Skinty Fia" to 30)
        val j = Discography.json(e, { plays[it] ?: 0 }, { shelfId[it] ?: 0L })
        val romance = j.getJSONObject(1)
        assertEquals("editions are one album in the history: not counted twice", 120, romance.getInt("plays"))
        assertEquals("owned as the deluxe edition", 4242L, romance.getLong("owned"))
        assertEquals(2024, romance.getInt("year"))
        assertEquals(0, j.getJSONObject(0).getInt("plays"))
    }

    @Test
    fun deezerReleasesAndAlbums() {
        val server = MiniHttp().start()
        try {
            server.handler = { r ->
                when {
                    r.path == "/artist/7/albums" && !r.query.contains("index") -> Res(
                        200,
                        """{"data":[{"id":1,"title":"Dopamine Chamber","record_type":"album","release_date":"2026-10-16","cover_big":"c1","link":"l1"}],
                        "total":2,"next":"${server.base}/artist/7/albums?limit=100&index=100"}""",
                    )
                    r.path == "/artist/7/albums" -> Res(200, """{"data":[{"id":2,"title":"Liberty Belle","record_type":"single","release_date":"2018-08-20","cover_big":"c2","link":"l2"}],"total":2}""")
                    r.path == "/album/1" -> Res(
                        200,
                        """{"id":1,"title":"Dopamine Chamber","release_date":"2026-10-16","label":"XL Recordings","cover_xl":"big","link":"l1",
                        "artist":{"name":"Fontaines D.C."},"genres":{"data":[{"id":85,"name":"Alternative"}]},
                        "tracks":{"data":[{"title":"Six Shot Morning","duration":296},{"title":"Marianne","duration":225}]}}""",
                    )
                    else -> Res(404, "")
                }
            }
            val d = DeezerClient(server.base)
            val rels = d.releases(7)
            assertEquals("both pages", listOf("Dopamine Chamber", "Liberty Belle"), rels.map { it.title })
            assertEquals(2018, rels[1].year)
            val a = d.album(1)
            assertEquals("XL Recordings", a.label)
            assertEquals(listOf("Six Shot Morning" to 296, "Marianne" to 225), a.tracks)
            assertEquals(listOf("Alternative"), a.genres)
        } finally {
            server.stop()
        }
    }

    @Test
    fun albumOfWhatPlays() {
        val server = MiniHttp().start()
        try {
            server.handler = { r ->
                when {
                    r.path == "/search/album" -> Res(
                        200,
                        // A namesake by another artist first; the music app's "(Deluxe Edition)" doesn't matter.
                        """{"data":[{"id":5,"title":"Romance","artist":{"name":"Camila Cabello"},"cover_big":"https://e-cdns/5.jpg"},{"id":9,"title":"Romance","artist":{"name":"Fontaines D.C."},"cover_big":"https://e-cdns/9.jpg"}]}""",
                    )
                    r.path == "/album/9" -> Res(
                        200,
                        """{"id":9,"title":"Romance","artist":{"name":"Fontaines D.C."},"tracks":{"data":[{"title":"Romance","duration":242},{"title":"Starburster","duration":213}]}}""",
                    )
                    else -> Res(404, "")
                }
            }
            val d = DeezerClient(server.base)
            val a = d.findAlbum("Fontaines D.C.", "Romance (Deluxe Edition)")
            assertEquals(9L, a?.id)
            assertEquals(listOf("Romance", "Starburster"), a?.tracks?.map { it.first })
            assertEquals(null, d.findAlbum("Fontaines D.C.", "Skinty Fia"))
            // Covers for albums off the shelf come from the same search, in one request.
            assertEquals("https://e-cdns/9.jpg", d.albumCover("Fontaines D.C.", "Romance"))
            assertEquals(null, d.albumCover("Fontaines D.C.", "Skinty Fia"))
        } finally {
            server.stop()
        }
    }

    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).toEpochSecond(ZoneOffset.UTC)

    @Test
    fun statsForOneGenre() {
        val b = History.Builder()
        repeat(3) { b.add(at(2026, 10, 1 + it), "IDLES", "Grace", "TANGK") }
        repeat(2) { b.add(at(2026, 10, 5 + it), "Air", "La Femme d'Argent", "Moon Safari") }
        b.add(at(2026, 10, 9), "Air", "Cherry Blossom Girl", "Talkie Walkie")
        val h = b.build()
        val shelf = listOf(
            CollectionItem(1, 1, "TANGK", "IDLES", 2024, 0, "", "", "LP", "", listOf("Rock"), listOf("Post-Punk"), 0),
            CollectionItem(2, 2, "Moon Safari", "Air", 1998, 0, "", "", "LP", "", listOf("Electronic"), listOf("Downtempo"), 0),
        )
        val s = Stats(h, ZoneId.of("UTC"), at(2026, 10, 25), shelf, Genres(shelf, emptyMap()))
        val opts = s.genreOptions()
        assertEquals("Punk & Post-Punk", opts.getJSONObject(0).getString("label"))
        assertEquals(3, opts.getJSONObject(0).getInt("plays"))
        val only = h.filter(s.inGenre("downtempo"))
        assertEquals("Moon Safari and, by the artist's styles, Talkie Walkie", 3, only.size)
        assertEquals(listOf("Air"), only.artistNames.toList())
        assertEquals(2, s.albumPlaysOf("Air", "Moon Safari (Remastered)"))
    }

    @Test
    fun genrePage() {
        val b = History.Builder()
        repeat(3) { b.add(at(2026, 10, 1 + it), "IDLES", "Grace", "TANGK") }
        repeat(2) { b.add(at(2026, 10, 5 + it), "Air", "La Femme d'Argent", "Moon Safari") }
        b.add(at(2026, 10, 9), "Air", "Cherry Blossom Girl", "Talkie Walkie")
        val h = b.build()
        val shelf = listOf(
            CollectionItem(1, 1, "TANGK", "IDLES", 2024, 0, "", "", "LP", "", listOf("Rock"), listOf("Post-Punk"), 0),
            CollectionItem(2, 2, "Moon Safari", "Air", 1998, 0, "", "", "LP", "", listOf("Electronic"), listOf("Downtempo"), 0),
        )
        val s = Stats(h, ZoneId.of("UTC"), at(2026, 10, 25), shelf, Genres(shelf, emptyMap()))
        val g = s.genrePage("downtempo")!!
        assertEquals("a style opens its subgenre, as Discogs spells it", "Downtempo", g.getString("label"))
        assertEquals("Electronic", g.getJSONObject("family").getString("label"))
        assertEquals(3, g.getInt("plays"))
        assertEquals(0.5, g.getDouble("share"), 0.001)
        assertEquals("tied with Post-Punk", 1, g.getInt("rank"))
        assertEquals(listOf(2L), (0 until g.getJSONArray("shelf").length()).map { g.getJSONArray("shelf").getLong(it) })
        assertEquals(1, g.getInt("artists"))
        val top = g.getJSONObject("top")
        assertEquals("Air", top.getJSONArray("artists").getJSONObject(0).getString("name"))
        assertEquals("La Femme d'Argent", top.getJSONArray("tracks").getJSONObject(0).getString("title"))
        assertEquals("the year from the shelf", 1990, g.getJSONObject("decades").getJSONArray("decades").getJSONObject(0).getInt("decade"))
        assertEquals(null, s.genrePage("polka"))
    }
}
