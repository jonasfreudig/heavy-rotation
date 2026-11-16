package app.recordshelf

import app.recordshelf.history.Fan
import app.recordshelf.history.Releases
import app.recordshelf.net.AudD
import app.recordshelf.net.DzRelease
import app.recordshelf.scrobble.Heard
import app.recordshelf.scrobble.HeardLog
import app.recordshelf.scrobble.HeardSong
import app.recordshelf.util.JsonFile
import app.recordshelf.util.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** Songs heard around you (Pixel's Now Playing, Identify by AudD), and new releases that come as one. */
class HeardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val formats = listOf("%1\$s by %2\$s") + Heard.fallbackFormats("en")

    @Test
    fun readsNowPlayingsWordsInAnyOrderAndLanguage() {
        assertEquals("Shame" to "Concrete", Heard.parse("Concrete by Shame", formats, { false }))
        assertEquals("Shame" to "Concrete", Heard.parse("Concrete von Shame", formats, { false }))
        // Japanese puts the artist in brackets after the title; Tamil puts the artist first.
        assertEquals(listOf("IDLES" to "Grace"), Heard.readings("Grace（IDLES）", "%1\$s（%2\$s）"))
        assertEquals(listOf("IDLES" to "Grace"), Heard.readings("IDLES இன் Grace", "%2\$s இன் %1\$s"))
        assertNull(Heard.parse("Now Playing is on", formats, { false }))
    }

    @Test
    fun aTitleWithByInItReadsRightAndYourArtistsWin() {
        // The shortest artist, unless one of the readings names an artist you know.
        assertEquals("Ben E. King" to "Stand by Me", Heard.parse("Stand by Me by Ben E. King", formats, { false }))
        assertEquals("Me by Ben" to "Stand", Heard.parse("Stand by Me by Ben", formats, { it == "Me by Ben" }))
    }

    @Test
    fun withoutNowPlayingsOwnWordsThePhonesLanguageGoesFirstAndASongYouKnowWins() {
        // On a German phone "von" splits, though the title has " by " in it.
        assertEquals("Motörhead" to "Killed by Death", Heard.parse("Killed by Death von Motörhead", Heard.fallbackFormats("de"), { false }))
        assertEquals("Death von Motörhead" to "Killed", Heard.parse("Killed by Death von Motörhead", Heard.fallbackFormats("en"), { false }))
        // "Tanz von Herbert von Karajan": the shortest artist would be Karajan; the song you've played says otherwise.
        val de = Heard.fallbackFormats("de")
        assertEquals("Karajan" to "Tanz von Herbert", Heard.parse("Tanz von Herbert von Karajan", de, { false }))
        assertEquals("Herbert von Karajan" to "Tanz", Heard.parse("Tanz von Herbert von Karajan", de, { false }) { a, t -> a == "Herbert von Karajan" && t == "Tanz" })
    }

    @Test
    fun aFullListLetsGoOfAnsweredSongsBeforeOnesStillWaiting() {
        val log = HeardLog(JsonFile(File(tmp.root, "full.json")), max = 3)
        val t = 1_800_000_000_000L
        log.add("A", "One", "", t, "nowplaying")
        val b = log.add("B", "Two", "", t + 1, "nowplaying").first
        log.add("C", "Three", "", t + 2, "nowplaying")
        b.status = HeardSong.SCROBBLED
        log.add("D", "Four", "", t + 3, "nowplaying")
        // The answered one went, the oldest still waiting stayed.
        assertEquals(listOf("One", "Three", "Four"), log.all().map { it.title })
        log.add("E", "Five", "", t + 4, "nowplaying")
        assertEquals(listOf("Three", "Four", "Five"), log.all().map { it.title })
    }

    @Test
    fun theSameArtistUnderAnotherCredit() {
        assertTrue(Heard.sameArtist("The Smiths", "Smiths"))
        assertTrue(Heard.sameArtist("D'Angelo & The Vanguard", "D'Angelo"))
        assertTrue(Heard.sameArtist("Fontaines D.C. feat. Someone", "Fontaines D.C."))
        assertFalse(Heard.sameArtist("Shame", "IDLES"))
        assertEquals(2, Heard.trackIndex(listOf("Alphabet", "Concrete", "Snow Day (Remastered 2024)"), "Snow Day"))
    }

    @Test
    fun nowPlayingTellingAgainIsTheSameListenAndANoStands() {
        val log = HeardLog(JsonFile(File(tmp.root, "heard.json")))
        val t = 1_800_000_000_000L
        val (a, new1) = log.add("Shame", "Concrete", "", t, "nowplaying")
        val (b, new2) = log.add("Shame", "Concrete", "", t + 60_000, "nowplaying")
        assertTrue(new1)
        assertFalse(new2)
        assertEquals(a.id, b.id)
        assertEquals(t + 60_000, a.lastAt)
        a.status = HeardSong.DROPPED
        log.add("Shame", "Concrete", "", t + 120_000, "nowplaying")
        assertTrue(log.waiting(t).isEmpty())
        // Much later it's another listen.
        val (c, new3) = log.add("Shame", "Concrete", "", t + 3_600_000, "nowplaying")
        assertTrue(new3)
        assertEquals(listOf(c.id), log.waiting(t + 3_600_000).map { it.id })
        log.save()
        // Kept on the phone, and gone after two weeks (Last.fm wouldn't take them).
        val again = HeardLog(JsonFile(File(tmp.root, "heard.json")))
        assertEquals(2, again.all().size)
        again.prune(t + Heard.KEEP_MS + 3_700_000)
        assertTrue(again.all().isEmpty())
    }

    @Test
    fun onlyNowPlayingsOwnNotesAreRead() {
        assertTrue(Heard.isNowPlaying("com.google.android.as", "com.google.intelligence.sense.ambientmusic.MusicNotificationChannel"))
        assertFalse(Heard.isNowPlaying("com.google.android.as", "smart_reply"))
        assertTrue(Heard.isNowPlaying("com.google.android.apps.pixel.nowplaying", "anything"))
        assertFalse(Heard.isNowPlaying("com.spotify.music", "playback"))
    }

    @Test
    fun auddAnswersASongNothingOrItsLimit() {
        val r = AudD.parse("""{"status":"success","result":{"artist":"Shame","title":"Concrete","album":"Songs of Praise","timecode":"01:23"}}""")
        assertEquals("Shame", r?.artist)
        assertEquals("Songs of Praise", r?.album)
        assertEquals(83, r?.offsetSec)
        assertNull(AudD.parse("""{"status":"success","result":null}"""))
        assertNull(AudD.parse("""{"status":"success","result":[]}"""))
        try {
            AudD.parse("""{"status":"error","error":{"error_code":901,"error_message":"No api_token passed, and the limit was reached"}}""")
            throw AssertionError("no limit")
        } catch (_: AudD.LimitReached) {
        }
        try {
            AudD.parse("""{"status":"error","error":{"error_code":300,"error_message":"Recognition failed"}}""")
            throw AssertionError("no error")
        } catch (e: IOException) {
            assertEquals("Recognition failed", e.message)
        }
    }

    private var nextId = 1L
    private fun r(title: String, type: String, date: String) = DzRelease(nextId++, title, type, date, "", "")
    private fun fan(name: String) = Text.norm(name) to Fan(name, 1.0, 100, 0, 0)

    @Test
    fun anEpJustBeforeAnAlbumAndItsSinglesLeadUpToTheAlbum() {
        // Queens of the Stone Age, November 2026 on Deezer.
        val found = Releases.find(
            mapOf(
                "Queens of the Stone Age" to listOf(
                    r("Perfecth", "album", "2026-10-30"), r("Run Away From the Mob", "ep", "2026-10-28"),
                    r("Where The Goth Girls At?", "single", "2026-09-23"), r("Insignificant Other", "single", "2026-08-25"),
                    r("Easy Street", "single", "2026-07-14"),
                ),
            ),
            mapOf(fan("Queens of the Stone Age")), LocalDate.of(2026, 11, 7),
        )
        assertEquals(listOf("Perfecth"), found.out.map { it.title })
        assertEquals(listOf("Run Away From the Mob"), found.out[0].eps)
        assertEquals(listOf("Easy Street", "Insignificant Other", "Where The Goth Girls At?"), found.out[0].singles)
    }

    @Test
    fun aReleaseNamedAfterASongOfTheAlbumAfterItLeadsUpToItWhateverItsCalled() {
        // On some phones Deezer lists "Run Away From the Mob" as an album; its title is a song of "Perfecth".
        val perfecth = r("Perfecth", "album", "2026-10-30")
        val mob = r("Run Away From the Mob", "album", "2026-10-28")
        val rels = listOf(perfecth, mob, r("Easy Street", "single", "2026-07-14"), r("Other Album", "album", "2026-06-01"))
        val today = LocalDate.of(2026, 11, 7)
        // The new album's songs are asked for, and those of what is filed as an album before it (is it EP-sized?).
        assertTrue(Releases.needSongs(rels, today).map { it.id }.containsAll(listOf(perfecth.id, mob.id)))
        val songs = listOf("It's Only Love", "Easy Street", "Run Away From the Mob")
        val songsOf = { x: DzRelease -> if (x.id == perfecth.id) songs else if (x.id == mob.id) listOf("Run Away From the Mob", "Easy Street") else null }
        val found = Releases.find(mapOf("Queens of the Stone Age" to rels), mapOf(fan("Queens of the Stone Age")), today, songs = songsOf)
        assertEquals(listOf("Perfecth"), found.out.map { it.title })
        assertEquals(listOf("Run Away From the Mob"), found.out[0].eps)
        assertEquals(listOf("Easy Street"), found.out[0].singles)
        // Without the songs both stay.
        val blind = Releases.find(mapOf("Queens of the Stone Age" to rels), mapOf(fan("Queens of the Stone Age")), today)
        assertEquals(setOf("Perfecth", "Run Away From the Mob"), blind.out.map { it.title }.toSet())
    }

    @Test
    fun aWholeAlbumWhoseTitleSongIsOnALaterRecordStaysItsOwn() {
        val today = LocalDate.of(2026, 11, 7)
        val night = r("Night", "album", "2026-09-20")
        val sessions = r("Night and Day Sessions", "album", "2026-10-20")
        val live = r("Night (Live at the Roundhouse)", "album", "2026-10-25")
        val ten = (1..9).map { "Song $it" } + "Night"
        val songsOf = { x: DzRelease -> if (x.id == night.id) ten else if (x.id == sessions.id) ten + listOf("Day", "Dusk") else if (x.id == live.id) ten else null }
        val found = Releases.find(mapOf("Band" to listOf(night, sessions, live)), mapOf(fan("Band")), today, songs = songsOf)
        // Ten songs is an album, whatever comes after it; and nothing leads up to a live record.
        assertTrue(found.out.any { it.title == "Night" })
        assertTrue(found.out.none { it.eps.isNotEmpty() })
    }

    @Test
    fun anEpBeforeAnAlbumStillToComeStaysOnItsOwn() {
        val found = Releases.find(
            mapOf("Band" to listOf(r("First Look", "ep", "2026-11-01"), r("The Album", "album", "2026-11-20"))),
            mapOf(fan("Band")), LocalDate.of(2026, 11, 7),
        )
        assertEquals(listOf("First Look"), found.out.map { it.title })
        assertEquals(listOf("The Album"), found.soon.map { it.title })
    }

    @Test
    fun aSeriesIsOneReleaseWithItsParts() {
        // Talking Heads put out one compilation a week, picked by each member.
        val found = Releases.find(
            mapOf(
                "Talking Heads" to listOf(
                    r("The Name of This Band Member is Chris", "album", "2026-10-28"), r("The Name of This Band Member is David", "album", "2026-10-21"),
                    r("The Name of This Band Member is Jerry", "album", "2026-10-14"), r("The Name of This Band Member is Tina", "album", "2026-10-07"),
                    r("Heaven (Live at Berklee Performance Center, Boston, MA, 8/24/1979)", "single", "2026-11-06"),
                    // Alike in their first word only: not a series.
                    r("Live Forever", "album", "2026-10-01"), r("Live Wire", "album", "2026-10-02"),
                ),
            ),
            mapOf(fan("Talking Heads")), LocalDate.of(2026, 11, 7),
        )
        val series = found.out.single { it.parts.isNotEmpty() }
        assertEquals("The Name of This Band Member is Chris", series.title)
        assertEquals(4, series.parts.size)
        assertEquals("The Name of This Band Member is Tina", series.parts.first())
        assertEquals(1, found.out.count { it.title.startsWith("The Name") })
        assertTrue(found.out.any { it.title == "Live Forever" } && found.out.any { it.title == "Live Wire" })
    }

    @Test
    fun aSeriesNeedsThreeWordsHalfOfEachTitleAndPartsCloseInTime() {
        val today = LocalDate.of(2026, 11, 7)
        fun parts(vararg rels: DzRelease) = Releases.find(mapOf("Band" to rels.toList()), mapOf(fan("Band")), today).let { f -> (f.out + f.soon).map { it.parts.size } }
        // Three words alike, and they are half of each title: a series.
        assertEquals(listOf(2), parts(r("Songs From Home Vol. 1", "album", "2026-10-01"), r("Songs From Home Vol. 2", "album", "2026-10-20")))
        // Two words alike: two records.
        assertEquals(listOf(0, 0), parts(r("Songs From Here", "album", "2026-10-01"), r("Songs From There", "album", "2026-10-20")))
        // Three alike, but less than half of a long title: two records.
        assertEquals(listOf(0, 0), parts(r("Songs From Home", "album", "2026-10-01"), r("Songs From Home and Other Places We Have Been To", "album", "2026-10-20")))
        // Parts due a month apart are a series; three months apart they are two records.
        assertEquals(listOf(2), parts(r("Songs From Home Vol. 1", "album", "2026-11-20"), r("Songs From Home Vol. 2", "album", "2026-12-20")))
        assertEquals(listOf(0, 0), parts(r("Songs From Home Vol. 1", "album", "2026-11-20"), r("Songs From Home Vol. 2", "album", "2027-02-20")))
    }
}
