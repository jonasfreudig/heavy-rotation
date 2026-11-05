package app.recordshelf

import app.recordshelf.MiniHttp.Res
import app.recordshelf.discogs.CollectionItem
import app.recordshelf.history.Discography
import app.recordshelf.history.Genres
import app.recordshelf.history.History
import app.recordshelf.history.Stats
import app.recordshelf.net.DeezerClient
import app.recordshelf.net.DzRelease
import app.recordshelf.util.finiteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun albumCoverPrefersTheAlbumOverItsSingle() {
        val server = MiniHttp().start()
        try {
            server.handler = { r ->
                if (r.path == "/search/album") Res(
                    200,
                    """{"data":[{"id":1,"title":"Nobody's Coming To Save You","record_type":"single","nb_tracks":1,"cover_big":"https://single","artist":{"name":"Gurriers"}},
                    {"id":2,"title":"Nobody's Coming To Save You","record_type":"album","nb_tracks":10,"cover_big":"https://album","artist":{"name":"Gurriers"}}]}""",
                ) else Res(404, "")
            }
            assertEquals("https://album", DeezerClient(server.base).albumCover("Gurriers", "Nobody's Coming to Save You"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun aSongYouHaventPlayedGetsItsAlbumsCover() {
        val server = MiniHttp().start()
        try {
            server.handler = { r ->
                if (r.path == "/search/track") Res(
                    200,
                    """{"data":[{"title":"Laidu","artist":{"name":"Someone Else"},"album":{"cover_big":"https://wrong"}},
                    {"title":"Laïdu","artist":{"name":"Rokia Traoré"},"album":{"cover_big":"https://right"}}]}""",
                ) else Res(404, "")
            }
            assertEquals("https://right", DeezerClient(server.base).trackCover("Rokia Traoré", "Laïdu"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun aCreditOfSeveralFindsTheFirstArtistsPictures() {
        // Deezer has Black Messiah and its photo by "D'Angelo" alone.
        val server = MiniHttp().start()
        try {
            server.handler = { r ->
                val q = r.params["q"].orEmpty()
                val first = q.contains("artist:\"D'Angelo\"")
                when {
                    r.path == "/search/artist" && q == "D'Angelo" ->
                        Res(200, """{"data":[{"id":1,"name":"D'Angelo","picture_big":"https://photo","nb_fan":159953}]}""")
                    r.path == "/search/album" && first -> Res(
                        200,
                        """{"data":[{"id":2,"title":"Black Messiah","record_type":"album","nb_tracks":12,"cover_big":"https://cover","artist":{"name":"D'Angelo"}}]}""",
                    )
                    r.path == "/search/track" && first ->
                        Res(200, """{"data":[{"title":"Really Love","artist":{"name":"D'Angelo"},"album":{"cover_big":"https://cover"}}]}""")
                    r.path.startsWith("/search/") -> Res(200, """{"data":[]}""")
                    else -> Res(404, "")
                }
            }
            val d = DeezerClient(server.base)
            for (credit in listOf("D'Angelo & The Vanguard", "D'Angelo and the Vanguard", "D'Angelo, The Vanguard", "D'Angelo feat. Q-Tip")) {
                assertEquals(credit, "https://photo", d.artistPhoto(credit))
                assertEquals(credit, "https://cover", d.albumCover(credit, "Black Messiah"))
                assertEquals(credit, "https://cover", d.trackCover(credit, "Really Love"))
            }
            assertEquals("the album's title still has to match", null, d.albumCover("D'Angelo & The Vanguard", "Voodoo"))
            assertEquals("a duo isn't given the photo of one of them", null, d.artistPhoto("D'Angelo & Kenny"))
            assertEquals("its album is", "https://cover", d.albumCover("D'Angelo & Kenny", "Black Messiah"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun anEditionMatchesItsAlbumButAnotherAlbumDoesNot() {
        assertEquals(true, app.recordshelf.net.sameButEdition("rumours super deluxe", "rumours"))
        assertEquals(true, app.recordshelf.net.sameButEdition("ok computer", "ok computer 2017 remaster"))
        assertEquals("Blue Train isn't Blue", false, app.recordshelf.net.sameButEdition("blue train", "blue"))
        assertEquals(false, app.recordshelf.net.sameButEdition("a", "a deluxe"))
    }

    @Test
    fun aBusyServiceIsAskedAgainAfterAPause() {
        var calls = 0
        val waits = mutableListOf<Long>()
        val r = app.recordshelf.net.Http.retryBusy(sleep = { waits += it }) {
            calls++
            if (calls < 3) app.recordshelf.net.HttpResponse(429, "", if (calls == 1) mapOf("Retry-After" to listOf("2")) else emptyMap())
            else app.recordshelf.net.HttpResponse(200, "ok", emptyMap())
        }
        assertEquals(200, r.code)
        assertEquals("Retry-After first, then two seconds", listOf(2000L, 2000L), waits)
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

    @Test
    fun anArtistStillPlayedInAnotherGenreIsntOneToRediscover() {
        // Surprise Chef's soul record last played in April, their jazz record all of October; Air quiet since spring.
        val b = History.Builder()
        repeat(12) { b.add(at(2026, 3, 1 + it), "Surprise Chef", "Velvet Jacket", "Daylight Savings") }
        repeat(12) { b.add(at(2026, 3, 14 + it), "Air", "La Femme d'Argent", "Moon Safari") }
        repeat(10) { b.add(at(2026, 10, 1 + it), "Surprise Chef", "Iced Vovos", "Education & Recreation") }
        val shelf = listOf(
            CollectionItem(1, 1, "Daylight Savings", "Surprise Chef", 2023, 0, "", "", "LP", "", listOf("Funk / Soul"), listOf("Soul"), 0),
            CollectionItem(2, 2, "Moon Safari", "Air", 1998, 0, "", "", "LP", "", listOf("Funk / Soul"), listOf("Soul"), 0),
            CollectionItem(3, 3, "Education & Recreation", "Surprise Chef", 2025, 0, "", "", "LP", "", listOf("Jazz"), listOf("Jazz-Funk"), 0),
        )
        val s = Stats(b.build(), ZoneId.of("UTC"), at(2026, 10, 25), shelf, Genres(shelf, emptyMap()))
        val lost = s.genrePage("Funk / Soul")!!.getJSONArray("lost")
        assertEquals(listOf("Air"), (0 until lost.length()).map { lost.getJSONObject(it).getString("name") })
    }

    @Test
    fun genrePagesOfGenresLongAgo() {
        // Ambient and bebop from the artists' Last.fm tags, played years ago, a few plays each; this year mostly rock.
        val b = History.Builder()
        repeat(4) { b.add(at(2023, 3, 1 + it), "Brian Eno", "An Ending", "Apollo") }
        repeat(2) { b.add(at(2024, 1, 3 + it), "Stars of the Lid", "Requiem for Dying Mothers", "") }
        b.add(at(2024, 5, 2), "Charlie Parker", "Ko-Ko", "Bird")
        b.add(at(2025, 2, 2), "Dizzy Gillespie feat. Charlie Parker", "Salt Peanuts", "")
        repeat(3) { b.add(at(2026, 10, 1 + it), "IDLES", "Grace", "TANGK") }
        repeat(5) { b.add(at(2026, 9, 1 + it), "Radiohead", "Treefingers", "Kid A") }
        val h = b.build()
        val tags = mapOf(
            Genres.artistKey("Brian Eno") to Genres.fromTags(listOf("ambient" to 100, "electronic" to 70, "experimental" to 50), "Brian Eno"),
            Genres.artistKey("Stars of the Lid") to Genres.fromTags(listOf("drone" to 100, "ambient" to 90), "Stars of the Lid"),
            Genres.artistKey("Charlie Parker") to Genres.fromTags(listOf("jazz" to 100, "bebop" to 90), "Charlie Parker"),
            Genres.artistKey("Dizzy Gillespie") to Genres.fromTags(listOf("jazz" to 100, "bebop" to 80), "Dizzy Gillespie"),
            Genres.artistKey("IDLES") to Genres.fromTags(listOf("post-punk" to 100, "punk" to 60), "IDLES"),
            Genres.artistKey("Radiohead") to Genres.fromTags(listOf("alternative" to 100, "ambient" to 30, "experimental" to 40), "Radiohead"),
        )
        val s = Stats(h, ZoneId.of("UTC"), at(2026, 10, 25), emptyList(), Genres(emptyList(), tags))
        val opts = s.genreOptions()
        val keys = (0 until opts.length()).flatMap { i ->
            val o = opts.getJSONObject(i)
            listOf(o.getString("key")) + (0 until (o.optJSONArray("subs")?.length() ?: 0)).map { o.getJSONArray("subs").getJSONObject(it).getString("key") }
        }
        assertTrue(keys.toString(), "ambient" in keys && "jazz:bebop" in keys)
        for (k in keys) {
            val g = s.genrePage(k)
            assertTrue("$k has a page", g != null && g.getInt("plays") > 0)
            assertTrue("$k built whole: ${g!!.optJSONArray("broken")}", !g.has("broken"))
            assertEquals("$k has only numbers JSON can carry", emptyList<String>(), g.finiteJson())
        }
    }
}
