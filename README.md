# Record Shelf

A personal Android app for a vinyl collection catalogued on Discogs. It shows the collection as a
cover wall and scrobbles the records you play to Last.fm, side by side, with "now playing" while a
side spins.

It also scrobbles what plays on the phone (TIDAL and any other app you switch on), so Pano
Scrobbler isn't needed. A shadow mode lets you check that against Pano first.

And it imports your whole Last.fm history for stats: when you listen, streaks, new artists,
genre drift, obsessions, records you own but rarely play, albums worth buying on vinyl, and a
year in review for any year.

Version 0.3 covers milestones 1 (Discogs + vinyl), 2 (phone scrobbling) and 3 (history + stats).

## Setup on the phone (once)

You need two things from your own accounts: a **Discogs personal access token** and a free
**Last.fm API account** (API key and shared secret). You type them into the app yourself. They stay
on the phone in the app's private storage and are left out of Android backups.

### 1. Install the APK

Copy `app-debug.apk` to the phone and open it. Android asks once to allow installs from the
app you opened it with (Files, Chrome, ...).

### 2. Connect Discogs

1. On discogs.com (signed in), open **Settings → Developers**: <https://www.discogs.com/settings/developers>
2. Tap **Generate new token** and copy it.
3. In Record Shelf, tap the gear → **Discogs** → paste the token → **Connect**.

The collection syncs right away (100 records per request; Discogs allows 60 requests a minute, so
even a big collection takes well under a minute). Sync again from the shelf's sync button whenever
you add records.

A personal token works for everything your account can do through the Discogs API. Record Shelf
only reads your identity, your collection and release data. You can revoke the token on the same
Discogs page at any time.

### 3. Connect Last.fm

1. Signed in to Last.fm, open <https://www.last.fm/api/account/create>.
2. Fill in the form: your contact email, an application name such as "Record Shelf", any
   description. Leave **Callback URL** and **Application homepage** empty.
3. Submit. The next page shows an **API key** and a **Shared secret** (both 32 letters and digits).
   You can look them up again later at <https://www.last.fm/api/accounts>.
4. In Record Shelf, tap the gear → **Last.fm** → paste both → **Save and connect**.
5. The browser opens Last.fm's "connect application" page. Tap **Yes, allow access**, then switch
   back to Record Shelf. It finishes on its own when you return; if not, tap **I've allowed it**.

### 4. Notifications

Allow notifications when the app asks (on your first scrobble). The "Now spinning" notification
shows the current track with a countdown and has **Stop** and **Flip to side B** buttons.

### 5. Phone scrobbling (replacing Pano Scrobbler)

1. Gear → **Phone scrobbling** → **Shadow**.
2. Tap **Allow notification access** and switch Record Shelf on. Android calls it notification
   access because that is the only way an app may see other apps' media players; Record Shelf
   doesn't read your notifications.
   - If the switch is greyed out with **"Restricted setting"** (Android does that for apps
     installed from an APK file): open **App info** (button in the app), tap **⋮** at the top right,
     choose **Allow restricted settings**, then try again.
3. TIDAL is switched on by default. Other apps appear in the list once they play something.
4. Keep Pano running and listen as usual for a day or two. Record Shelf only *logs* what it would
   scrobble (marked "Shadow" on the Activity tab) and sends nothing.
5. On the Activity tab, tap **Compare with Pano**. It reads what reached Last.fm since shadow mode
   started and shows how much Record Shelf caught, what only Pano scrobbled, what only Record Shelf
   would have, and tracks the two named differently.
6. When you're happy, switch **Phone scrobbling** to **On** and turn off Pano's scrobbling (or
   uninstall it). Otherwise every track is scrobbled twice; the app warns while Pano is installed.

How it decides, following Last.fm's rules: a track counts once it has played for half its length
or 4 minutes, whichever comes first, and only if it is longer than 30 seconds. Only time actually
playing counts (pauses, buffering and seeking forward don't). Each scrobble carries the time the
track started. Playing a track again from the start, including on repeat, is a new scrobble. Tracks
whose length the app doesn't report need 4 minutes. In On mode "now playing" goes to Last.fm too,
and scrobbles use the same offline queue as records.

### 6. Stats

Open the **Stats** tab and tap **Import history**. The app copies all your scrobbles from Last.fm to
the phone (200 per request). A long history takes a few minutes the first time, roughly one minute
per 20,000 scrobbles. You can use other apps meanwhile; a notification shows the progress. If the
import is interrupted it continues where it stopped the next time you open the app. Afterwards
only new scrobbles are fetched, at most every 30 minutes when you open the app.

What the tab shows, for the last 12 months, all time or any single year:

- **Totals**: scrobbles, artists, albums, days with music.
- **Your record**: every play as a dot on a vinyl disc. Each groove is a day, from the label (the
  first day of the range) to the rim (today); noon is at the top, midnight at the bottom. Tap a
  dot to read that play and light up all plays of its artist; tap the label to clear.
- **Phases**: weekly plays of your most played artists as ridges, ordered by the week they peaked.
- **When you listen**: plays per weekday and hour. Tap a cell for its numbers.
- **Streaks**: current and longest run of days with music, and artists you played many days in a row.
- **New artists**: artists heard for the first time, per month (per year for long ranges). The
  month your Last.fm history starts is grayed out, since everything is new there.
- **Genre drift**: how your genres shift over time. Records you own (and their artists) get their
  Discogs styles; everything else gets the artist's Last.fm tags, with non-genres like "seen live"
  removed. The six biggest genres keep their colors whatever range you pick.
- **Obsessions**: a track played at least 7 times in one week, and songs played back to back
  several times in a row.
- **On your shelf, rarely played**: your records, least played first.
- **Wishlist ideas**: albums you play a lot (several different tracks, not just one hit) that
  aren't on your shelf; tap one to search Discogs.
- **Top 10** artists, albums and tracks.
- **Year in review** (the card below the totals): top artists, albums and tracks, new
  discoveries, your busiest day, longest streak, obsession, how much of it was vinyl, and when you
  listened.

Tap any artist (in the lists, the phases, the record, or on a record page) for their own page:
plays, rank, first play, busiest month, longest streak, plays per month, time of day, their plays
lit up on your record, top tracks and albums, and the records of theirs on your shelf.

Features and collaborations count for the main artist, as long as that artist also appears on
their own: "Fontaines D.C., ROSALÍA" counts for Fontaines D.C., and anything "feat." goes to the
first name, but "Earth, Wind & Fire" stays one band.

Days, weeks and hours are in the phone's local time.

### Moving covers

On a record page (and on Now spinning) the cover is a sleeve with the record sliding out behind
it. It tilts and catches the light as you move the phone, like a holo trading card, and the record
spins while a side plays. The motion sensor only runs while such a page is open. Switch it off in
Settings → Look → Moving covers. The "plays on Last.fm" chip on a record page opens your Last.fm
library for that album (in the Last.fm app if it's installed).

## Scrobbling a record

Tap a record, then **Scrobble** (or **Scrobble** next to a side, or tap a single track):

- **What you play:** a side, the whole record, or chosen tracks.
- **When:**
  - **Starting now** – the tracks are timed from now. Last.fm shows "now playing", and each track
    is scrobbled once half of it (or 4 minutes) has played, which is Last.fm's own rule.
    When the side ends, a notification offers to start the next side.
  - **Earlier** – pick the start date and time ("I played this last night").
  - **Just finished** – everything is scrobbled right away, ending now.
- **Out of step with the record?** On the Now spinning page, tap the track that is actually
  playing; the times after it move.
- **Offline?** Scrobbles wait on the phone and go out automatically once it is online again
  (also after a reboot). The Activity tab shows what was sent, what waits, and what Last.fm
  ignored and why.
- **Two-week limit:** Last.fm silently ignores scrobbles older than about two weeks. The app
  blocks scrobbling a listen that old and warns when waiting scrobbles get close to the limit.

### Track lengths

Discogs often has no track lengths. The app fills the gaps in this order: the MusicBrainz release
linked to that exact Discogs release, Last.fm's album info, a MusicBrainz search by artist and title,
and finally a default length you choose in Settings (3:30 unless you change it). Guessed lengths show
as "≈3:30" on the record page. "Look up lengths again" retries the lookups.

### "Most played"

Play counts on the shelf are your Last.fm all-time album plays, so they include streaming (Tidal via
Pano Scrobbler) as well as vinyl. They refresh after a sync and at most every 12 hours.

## Build

Needs JDK 17 and the Android SDK (platform 34). From Git Bash:

```bash
export JAVA_HOME="C:/Users/Freud/AppData/Local/Programs/jdk-17"; ./gradlew testDebugUnitTest assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.

### Preview the UI in a browser

The interface is a local web page with a mock backend, so it runs in any browser:

```bash
python -m http.server 8791 --directory app/src/main/assets/ui
```

Then open <http://localhost:8791/> (add `?mock=new` for a fresh install, `?mock=spin` for a record
already playing).

## Layout

| Path | What |
| --- | --- |
| `discogs/` | Discogs client (token auth, rate limit), collection and release parsing, tracklists and sides |
| `lastfm/LastfmClient.kt` | Last.fm API: web auth, signing, `track.scrobble` (batches of 50), now playing, album info, top albums |
| `mb/MusicBrainzClient.kt` | MusicBrainz lookups for missing track lengths (1 request per second) |
| `scrobble/Planner.kt`, `Spin.kt` | Timing a record: start times, Last.fm's half-or-4-minutes rule, flip, stop, resync |
| `scrobble/ScrobbleQueue.kt` | Durable scrobble queue (survives offline and app kills) and the activity log |
| `scrobble/PlayTracker.kt` | When a phone listen counts: listened time, Last.fm's rule, repeats, pauses |
| `scrobble/Compare.kt` | Shadow mode vs. what reached Last.fm |
| `PhoneListener.kt`, `Phone.kt` | Media sessions of music apps (via notification access), app list, mode |
| `history/HistorySync.kt` | Resumable import of user.getRecentTracks with a time cursor |
| `history/History.kt`, `history/Stats.kt` | The history in memory and every figure on the Stats tab |
| `history/Genres.kt` | Genres from Discogs styles and cleaned Last.fm tags |
| `HistoryDb.kt` | SQLite store for the history, genre tags and cover URLs |
| `Core.kt` | App state and work: sync, lookups, the spinning record, sending |
| `Background.kt` | Alarms at track changes, the flush job for offline scrobbles, the notification |
| `MainActivity.kt` | Hosts the web UI, Material You palette, theme, browser hand-off |
| `assets/ui/` | The interface (HTML/JS in a WebView; `mock.js` stands in for the app in a browser) |

Unit tests in `app/src/test` run the API clients against a local fake server, using real
Discogs and MusicBrainz responses (`src/test/resources/fixtures`) and Last.fm answers in the
documented format.
