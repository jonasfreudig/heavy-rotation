# Record Shelf

A personal Android app for a vinyl collection catalogued on Discogs. It shows the collection as a
cover wall and scrobbles the records you play to Last.fm, side by side, with "now playing" while a
side spins.

It also scrobbles what plays on the phone (TIDAL and any other app you switch on), so Pano
Scrobbler isn't needed. A shadow mode lets you check that against Pano first.

And it imports your whole Last.fm history for stats, for any week, month or year or all time:
highlights compared with the period before, your plays as dots on a record with your top artists
in color, when you listen, streaks, new artists, genre drift, obsessions, records you own but
rarely play, albums worth buying on vinyl, and a Wrapped-style story for any period.

Version 0.8 covers milestones 1 (Discogs + vinyl), 2 (phone scrobbling) and 3 (history + stats).

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

### 6. TIDAL (optional: exact links and playlists)

Without this, the TIDAL buttons open TIDAL's search. With your own (free) TIDAL developer app,
they open the exact song, album or artist, and playlists from the Explore tab go straight into
your TIDAL.

1. Open the TIDAL developer dashboard, <https://developer.tidal.com/dashboard>, and log in with
   your TIDAL account. The first time, accept TIDAL's developer guidelines.
2. Create an app. Any name will do, for example "Record Shelf".
3. In the app's settings:
   - add the **redirect URI** `recordshelf://tidal` (exactly like that; Settings → TIDAL has a
     Copy button), and
   - switch on the scope **playlists.write** (nothing else is needed).
4. Copy the app's **Client ID** and **Client Secret** into Record Shelf: gear → **TIDAL** →
   **Save**. The status turns to "Exact links are on".
5. Tap **Sign in to TIDAL**. TIDAL's login page opens in the browser; approve, and the browser
   hands you back to Record Shelf ("Signed in to TIDAL").

Then a playlist page has **Create in TIDAL**: Record Shelf finds each song in TIDAL's catalog,
creates an unlisted playlist in your library and adds them; songs TIDAL doesn't have are listed.
Found songs are remembered on the phone, so the next time is quicker.

The client secret and the TIDAL login stay in the app's private storage, left out of backups
like the Last.fm keys. **Disconnect** forgets them; playlists already made stay in TIDAL.

If the dashboard doesn't accept `recordshelf://tidal` as a redirect URI, the sign-in can't come
back to the app; exact links still work without signing in, and playlists still go through
**Copy list** and TuneMyMusic.

### 7. Stats

Open the **Stats** tab and tap **Import history**. The app copies all your scrobbles from Last.fm to
the phone (200 per request). A long history takes a few minutes the first time, roughly one minute
per 20,000 scrobbles. You can use other apps meanwhile; a notification shows the progress. If the
import is interrupted it continues where it stopped the next time you open the app. Afterwards
only new scrobbles are fetched, at most every 30 minutes when you open the app.

Pick **Week**, **Month**, **Year** or **All** at the top, and step back and forth with **‹ ›**.
The charts adapt: a week or a month is shown per day, a year per week or month, all time per
month, quarter or year. What the tab shows for the chosen period:

- **Your story** (the card at the top): a Wrapped-style story you tap through, like Instagram
  stories (right side forward, left side back, hold to pause). Total plays, your listening
  personality (night owl, early bird, loyalist, explorer, on repeat, weekender, crate digger,
  genre hopper or all-rounder), top artist and top 5, song and album of the period (the album as a
  moving cover), your golden hour, new artists, a song on repeat, your sound (genres), vinyl,
  your longest streak, and a summary card to finish.
- **Totals**: plays, hours (about 3.5 minutes a track), artists and new artists, each with the
  change against the period before. For a period still running (this week, this month) it
  compares with the same stretch of the previous one: Monday to Wednesday against last Monday to
  Wednesday.
- **Highlights**: the period in sentences: more or less than last time, whether your top artist
  held on, an artist you came back to after months, discoveries, your busiest day and hours,
  night listening, song of the period, streaks, your biggest day, how much was vinyl.
- **Your record**: every play as a dot on a vinyl disc. Each groove is a day, from the label (the
  first day of the period) to the rim (the last); noon is at the top, midnight at the bottom. Your
  eight most played artists have their own colors, with a legend below. Tap a name in the legend,
  type in **Find an artist on the record**, or tap near a dot to light up one artist; tap the
  label to show everyone again.
- **Listening over time**: plays per day, week or month, with the average marked. Tap a column.
- **The river**: the same over time, split into your top eight artists (same colors as the
  record) stacked on everyone else. Tap it for a day's numbers.
- **When you listen**: plays per weekday and hour. Tap a cell for its numbers.
- **Streaks**: your current run of days with music (counted back from today, also before the
  period started), the longest in the period, and artists you played many days in a row.
- **Phases**: plays of your most played artists as ridges, ordered by when they peaked.
- **New artists**: artists heard for the first time, per day, week or month. The stretch where
  your Last.fm history starts is grayed out, since everything is new there.
- **Genre drift**: how your genres shift over time. Records you own (and their artists) get their
  Discogs styles; everything else gets the artist's Last.fm tags, with non-genres like "seen live"
  removed. The six biggest genres keep their colors whatever range you pick.
- **Obsessions**: a track played at least 7 times in one week, and songs played back to back
  several times in a row.
- **On your shelf, rarely played**: your records, least played first.
- **Wishlist ideas**: albums you play a lot (several different tracks, not just one hit) that
  aren't on your shelf; tap one to search Discogs.
- **Top 10** artists, albums and tracks.

#### Songs, albums and artists

Tap a scrobble on the Activity tab, or a song in the stats lists, for the song's page: all-time
plays and rank, this period's rank, first and last play, its biggest day, how much of the artist
it makes up, plays per month, time of day, and what you tend to play right after it. Albums (Top
10, an artist's albums, a record's **Your stats** chip) get the same, plus which of their songs you
play.

Song, album, artist and record pages have **Listen on** buttons that search TIDAL and Spotify
for them. Pick the services under Settings → Listen on (YouTube Music, Apple Music and Deezer too).
Spotify opens in its app; TIDAL opens its search page, which lists the match first.

Phone scrobbles carry no cover, so the Activity tab takes one from your shelf (if you own the
album) or from Last.fm's album art, looked up once and kept on the phone.

#### Explore

The **Explore** tab digs into what never makes the top lists:

- **Lost favorites**: artists you played a lot that have gone quiet for half a year or more,
  with the month they were big for you.
- **Albums to rediscover**: albums you played through (several different songs), then left.
- **Quiet regulars**: artists outside your top ones that come back month after month.
- **One-day wonders**: played all day once, never again.
- **Deep cuts**: songs by your favorite artists you've played only once or twice.
- **On this day**: what you played on today's date in earlier years.

Each list makes a **playlist** (up to 40 songs), and so do **Crate dig** (songs you played a few
times and forgot, shuffled anew each time) and **Playlist of the top 40** under Top 10 on the
Stats tab. **Copy list** puts it on the clipboard as "Artist - Title" lines; paste that into
TuneMyMusic or Soundiiz (import from text) and send it to TIDAL. **Share** hands it to any app.

Tap any artist (in the lists, the phases, the record, or on a record page) for their own page:
plays, rank, first play, busiest month, longest streak, plays per month, time of day, their plays
lit up on your record, top tracks and albums, and the records of theirs on your shelf.

#### Tidy up: videos and app sounds

A scrobbler that listened to every app may have sent things that aren't music: "HBO Max", a game
trailer from YouTube, a short clip that played on loop. When the history has such scrobbles, a
banner on the Stats tab opens **Tidy up stats** (also under Settings → Stats). It lists:

- **Look like videos or apps**: tracks without an album that are named like an app, have a
  video-style title (trailer, episode, "Title | Channel"), have the artist's name as their title,
  or were scrobbled again every few seconds. **Hide** them one by one or all at once, or **Keep**
  the ones that are music after all.
- **Most played without an album**: probably music, but worth a look.
- **Hidden**: everything you hid, with **Show** to bring it back.

You can also hide a single track from an artist's top tracks, or a whole artist with the hide
button at the top of their page. Hidden plays leave all the stats, the story and the artist
pages. Nothing changes on Last.fm: apps can't delete scrobbles there. To delete them on Last.fm
too, open a hidden track (↗) and delete its scrobbles from your library on the website.

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
| `history/History.kt`, `history/Stats.kt` | The history in memory; every figure on the Stats and Explore tabs, song and album pages, playlists |
| `history/Genres.kt` | Genres from Discogs styles and cleaned Last.fm tags |
| `tidal/` | TIDAL API: app and user tokens (PKCE), catalog search and matching, playlists; the cache of found songs |
| `history/Hidden.kt` | Scrobbles hidden from the stats, and spotting videos and app sounds |
| `HistoryDb.kt` | SQLite store for the history, genre tags and cover URLs |
| `Core.kt` | App state and work: sync, lookups, the spinning record, sending |
| `Background.kt` | Alarms at track changes, the flush job for offline scrobbles, the notification |
| `MainActivity.kt` | Hosts the web UI, Material You palette, theme, browser hand-off |
| `assets/ui/` | The interface (HTML/JS in a WebView; `mock.js` stands in for the app in a browser) |

Unit tests in `app/src/test` run the API clients against a local fake server, using real
Discogs and MusicBrainz responses (`src/test/resources/fixtures`) and Last.fm answers in the
documented format.
