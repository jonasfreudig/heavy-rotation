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
    fun catalogSearchGivesAlbumsAsDeezerRanksThem() {
        val server = MiniHttp().start()
        try {
            server.handler = { r ->
                if (r.path == "/search/album" && r.params["q"] == "geese getting killed") Res(
                    200,
                    """{"data":[{"id":7,"title":"Getting Killed","record_type":"album","nb_tracks":11,"cover_big":"https://cover","artist":{"name":"Geese"}},
                    {"id":8,"title":"Taxes","record_type":"single","nb_tracks":1,"cover_big":"","artist":{"name":"Geese"}},
                    {"id":0,"title":"Broken","record_type":"album","nb_tracks":3,"artist":{"name":"Nobody"}}]}""",
                ) else Res(404, "")
            }
            val found = DeezerClient(server.base).searchAlbums("geese getting killed")
            assertEquals(listOf(7L, 8L), found.map { it.id })
            assertEquals("Geese", found[0].artist)
            assertEquals("album", found[0].type)
            assertEquals(11, found[0].tracks)
            assertEquals("https://cover", found[0].cover)
            assertEquals("", found[1].cover)
        } finally {
            server.stop()
        }
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
        assertEquals("a player's \"EP\" at the end", true, app.recordshelf.net.sameButEdition("steaming satellites ep", "steaming satellites"))
        assertEquals("Invaders Must Die", app.recordshelf.net.coreTitle("Invaders Must Die (Special Version)"))
        assertEquals("The Moustache Mozart Affaire", app.recordshelf.net.coreTitle("The Moustache Mozart Affaire EP"))
        assertEquals("Heroes", app.recordshelf.net.coreTitle("Heroes - Single"))
        assertEquals("a title that is only such a word stays", "EP", app.recordshelf.net.coreTitle("EP"))
    }

    @Test
    fun aCoverIsAskedForInMoreWaysThanOne() {
        // Deezer's search by fields finds nothing with what a player adds to a title, and for some songs nothing at all.
        val server = MiniHttp().start()
        try {
            fun album(title: String, artist: String, cover: String, type: String = "album") =
                """{"id":1,"title":"$title","record_type":"$type","nb_tracks":11,"cover_big":"$cover","artist":{"name":"$artist"}}"""
            server.handler = { r ->
                val q = r.params["q"].orEmpty()
                when {
                    r.path == "/search/album" && q == "artist:\"The Prodigy\" album:\"Invaders Must Die\"" -> Res(200, """{"data":[${album("Invaders Must Die", "The Prodigy", "https://invaders")}]}""")
                    r.path == "/search/album" && q == "Steaming Satellites Steaming Satellites EP" -> Res(200, """{"data":[${album("Steaming Satellites", "Steaming Satellites", "https://satellites")}]}""")
                    r.path == "/search/album" && q == "Cat Stevens Tea for the Tillerman" ->
                        Res(200, """{"data":[${album("Tea for the Tillerman", "Somebody Else", "https://wrong")},${album("Tea For The Tillerman", "Yusuf / Cat Stevens", "https://tea")}]}""")
                    r.path == "/search/track" && q == "Roosevelt Forgive" -> Res(
                        200,
                        """{"data":[{"title":"Forgive and Forget","artist":{"name":"Roosevelt"},"album":{"cover_big":"https://wrong"}},
                        {"title":"Forgive feat. Washed Out","artist":{"name":"Roosevelt"},"album":{"cover_big":""}},
                        {"title":"Forgive (feat. Washed Out)","artist":{"name":"Roosevelt"},"album":{"cover_big":"https://forgive"}}]}""",
                    )
                    r.path.startsWith("/search/") -> Res(200, """{"data":[]}""")
                    else -> Res(404, "")
                }
            }
            val d = DeezerClient(server.base)
            assertEquals("without what stands in brackets", "https://invaders", d.albumCover("The Prodigy", "Invaders Must Die (Special Version)"))
            assertEquals("an album a player calls an EP", "https://satellites", d.albumCover("Steaming Satellites", "Steaming Satellites EP"))
            assertEquals("an artist filed under two names", "https://tea", d.albumCover("Cat Stevens", "Tea for the Tillerman"))
            assertEquals("a song Deezer names with its guest, the first that has a cover", "https://forgive", d.trackCover("Roosevelt", "Forgive"))
            assertEquals("nothing is asked twice", server.requests.size, server.requests.map { it.path + "?" + it.query }.toSet().size)
            assertEquals(null, d.albumCover("The Prodigy", "The Fat of the Land"))

            // An album found through one of its songs: the song's release that is this album, not the first one.
            server.handler = { r ->
                if (r.path == "/search/track" && r.params["q"] == "I.L.L. Will Swing Thing") Res(
                    200,
                    """{"data":[{"title":"Swing Thing","artist":{"name":"I.L.L. Will"},"album":{"title":"Jazz Hop Vol. 3","cover_big":"https://other"}},
                    {"title":"Swing thing","artist":{"name":"I.L.L. Will"},"album":{"title":"Expedition Vol. 20: One Will","cover_big":"https://one-will"}}]}""",
                ) else if (r.path.startsWith("/search/")) Res(200, """{"data":[]}""") else Res(404, "")
            }
            assertEquals("https://one-will", d.albumCoverBySong("I.L.L. Will", "One Will", "Swing Thing", false))
            assertEquals("no other release's cover on an album", null, d.albumCoverBySong("I.L.L. Will", "Two Wills", "Swing Thing", false))
            assertEquals("a single or a compilation song takes it", "https://other", d.albumCoverBySong("I.L.L. Will", "Chill Beats", "Swing Thing", true))
            assertEquals("https://other", d.trackCover("I.L.L. Will", "Swing Thing"))
        } finally {
            server.stop()
        }
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
    fun aLongerEditionCountsAsTheAlbum() {
        val b = History.Builder()
        // Skinty Fia mostly; from Skinty Fia go deo, one of its songs and a live version of another.
        repeat(5) { b.add(at(2026, 4, 1 + it), "Fontaines D.C.", "Jackie Down the Line", "Skinty Fia") }
        repeat(3) { b.add(at(2026, 4, 10 + it), "Fontaines D.C.", "Big Shot", "Skinty Fia") }
        b.add(at(2026, 5, 1), "Fontaines D.C.", "Big Shot", "Skinty Fia go deo")
        b.add(at(2026, 5, 2), "Fontaines D.C.", "Jackie Down the Line (Live at Alexandra Palace)", "Skinty Fia go deo")
        // Led Zeppelin II shares no songs with Led Zeppelin: two albums.
        b.add(at(2026, 5, 3), "Led Zeppelin", "Good Times Bad Times", "Led Zeppelin")
        b.add(at(2026, 5, 4), "Led Zeppelin", "Whole Lotta Love", "Led Zeppelin II")
        val h = b.build()
        assertEquals(listOf("Skinty Fia", "Led Zeppelin", "Led Zeppelin II"), h.albumTitles.toList())
        val s = Stats(h, ZoneId.of("UTC"), at(2026, 10, 25))
        assertEquals("every play counts for the album", 10, s.albumPlaysOf("Fontaines D.C.", "Skinty Fia"))
        assertEquals("the edition's name finds it", 10, s.albumPlaysOf("Fontaines D.C.", "Skinty Fia go deo"))
        assertEquals("Skinty Fia", s.editionRoot("Fontaines D.C.", "Skinty Fia go deo"))
        assertEquals(null, s.editionRoot("Led Zeppelin", "Led Zeppelin II"))
        assertEquals(1, s.albumPlaysOf("Led Zeppelin", "Led Zeppelin II"))
        assertEquals("a genre's plays know it too", 10, Stats(h.filter { true }, ZoneId.of("UTC"), at(2026, 10, 25)).albumPlaysOf("Fontaines D.C.", "Skinty Fia go deo"))
        val d = Discography.merge(listOf(rel(2, "Skinty Fia go deo", "album", "2023-04-21"), rel(1, "Skinty Fia", "album", "2022-04-22"))) { s.editionRoot("Fontaines D.C.", it) }
        assertEquals("one record on the discography", listOf("Skinty Fia"), d.map { it.release.title })
        assertEquals(2, d[0].editions.size)
    }

    @Test
    fun aSingleCountsAsTheAlbumItsSongIsOn() {
        val b = History.Builder()
        // shame's single "Quiet Life" before the album came out, then Cutthroat with the same song on it.
        repeat(3) { b.add(at(2025, 7, 1 + it), "shame", "Quiet Life", "Quiet Life - Single") }
        for ((k, t) in listOf("Cutthroat", "Quiet Life", "Cowards Around", "Plaster", "Spartak", "Lampiao").withIndex()) repeat(2) { b.add(at(2025, 9, 1 + k * 2 + it), "shame", t, "Cutthroat") }
        // A single whose song is on no album you played stays a release of its own.
        b.add(at(2025, 9, 20), "shame", "Born In Luton", "Born In Luton")
        // Bowie's "Heroes" album, two songs played, keeps them though a best-of has its title song.
        b.add(at(2025, 9, 21), "David Bowie", "\"Heroes\"", "\"Heroes\"")
        b.add(at(2025, 9, 22), "David Bowie", "Sons of the Silent Age", "\"Heroes\"")
        for (t in listOf("\"Heroes\"", "Changes", "Life on Mars?", "Starman")) b.add(at(2025, 9, 23), "David Bowie", t, "The Best of Bowie")
        val h = b.build()
        assertEquals(listOf("Cutthroat", "Born In Luton", "\"Heroes\"", "The Best of Bowie"), h.albumTitles.toList())
        val s = Stats(h, ZoneId.of("UTC"), at(2025, 10, 25))
        assertEquals("the single's plays count for the album", 15, s.albumPlaysOf("shame", "Cutthroat"))
        assertEquals("its own card keeps its plays", 3, s.albumPlaysOf("shame", "Quiet Life"))
        assertEquals("and it stays a card of its own", null, s.editionRoot("shame", "Quiet Life"))
        assertEquals(1, s.albumPlaysOf("shame", "Born In Luton"))
        assertEquals(2, s.albumPlaysOf("David Bowie", "\"Heroes\""))
        assertEquals("a genre's plays know it too", 15, Stats(h.filter { true }, ZoneId.of("UTC"), at(2025, 10, 25)).albumPlaysOf("shame", "Cutthroat"))
        val d = Discography.merge(listOf(rel(1, "Cutthroat", "album", "2025-09-05"), rel(2, "Quiet Life", "single", "2025-06-20"))) { s.editionRoot("shame", it) }
        assertEquals(listOf("Cutthroat", "Quiet Life"), d.map { it.release.title })
    }

    @Test
    fun aSongOnAnEpAndTheAlbumCountsForTheAlbum() {
        val b = History.Builder()
        // Sorry's EP "More" with a song of its own, then 925 with "More" on it.
        repeat(4) { b.add(at(2019, 6, 1 + it), "Sorry", "More", "More") }
        repeat(2) { b.add(at(2019, 6, 10 + it), "Sorry", "Lies", "More") }
        for ((k, t) in listOf("Right Round the Clock", "As The Sun Sets", "In Unison", "Starstruck", "Snakes", "More", "Rosie").withIndex()) {
            repeat(2) { b.add(at(2020, 4, 1 + k * 2 + it), "Sorry", t, "925") }
        }
        val h = b.build()
        assertEquals("the EP stays, with the song only it has", listOf("More", "925"), h.albumTitles.toList())
        val s = Stats(h, ZoneId.of("UTC"), at(2020, 10, 25))
        assertEquals("More's plays from the EP count for 925", 18, s.albumPlaysOf("Sorry", "925"))
        assertEquals("the EP's card keeps all its own plays", 6, s.albumPlaysOf("Sorry", "More"))
        val more = (0 until h.size).filter { h.trackTitles[h.track[it]] == "More" }.map { h.albumTitles[h.album[it]] }.toSet()
        assertEquals(setOf("925"), more)
        val lies = (0 until h.size).filter { h.trackTitles[h.track[it]] == "Lies" }.map { h.albumTitles[h.album[it]] }.toSet()
        assertEquals(setOf("More"), lies)
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
