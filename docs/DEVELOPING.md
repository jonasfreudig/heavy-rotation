# Building Heavy Rotation

You need JDK 17 and the Android SDK (platform 34). This runs the unit tests and builds the debug APK:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK (`app/build/outputs/apk/debug/app-debug.apk`) installs as Heavy Rotation (debug),
package `app.recordshelf.debug`, so it can sit next to a release build.

## The app's own keys (for builds you share)

A build can carry its own Last.fm API account, TIDAL app and setlist.fm API key, so that people who
install it only have to sign in. To create the Last.fm and TIDAL ones, follow the steps for using
your own key or app in the Last.fm and TIDAL sections of [the guide](GUIDE.md). The setlist.fm key is
under API in your setlist.fm settings. Put all of them in `local.properties` in the project folder,
which is kept out of git:

```properties
recordshelf.lastfm.key=...
recordshelf.lastfm.secret=...
recordshelf.tidal.clientId=...
recordshelf.tidal.secret=...
recordshelf.setlistfm.key=...
```

Without them, everyone enters their own keys in Settings. Keys inside an APK can be extracted, as
with any app of this kind, so use accounts made only for this app.

A new TIDAL app runs in development mode, with small quotas, until TIDAL reviews it and moves it to
production (their [developer terms](https://developer.tidal.com/documentation/guidelines/guidelines-developer-terms),
"Usage, Production Mode and Approval"). Until yours is approved, build the APKs you hand out with
`./gradlew assembleRelease -PwithoutTidal`, which leaves your TIDAL app out. The TIDAL buttons then
open TIDAL's search, and anyone can still connect their own TIDAL app in Settings.

## Release builds

Create your signing key once and keep the file and its passwords safe. Every update has to be
signed with the same key, or it won't install over the previous version.

```bash
keytool -genkeypair -v -keystore record-shelf.jks -alias recordshelf -keyalg RSA -keysize 4096 -validity 10000
```

Then describe the key in `keystore.properties` next to `settings.gradle` (also kept out of git):

```properties
storeFile=record-shelf.jks
storePassword=...
keyAlias=recordshelf
keyPassword=...
```

Build the release APK with

```bash
./gradlew assembleRelease
```

It ends up in `app/build/outputs/apk/release/app-release.apk` (package `app.recordshelf`). Without
`keystore.properties` the release APK comes out unsigned.

## Preview the UI in a browser

The interface is a local web page. With its mock backend (`mock.js`) in place of the app, it runs
in any browser:

```bash
python -m http.server 8791 --directory app/src/main/assets/ui
```

Then open <http://localhost:8791/>. Add `?mock=new` for a fresh install or `?mock=spin` for a
record that is already playing.

## Code layout

| Path | What |
| --- | --- |
| `discogs/` | Discogs client (public collections by username, or a token; rate limits), collection and release parsing, tracklists and sides |
| `lastfm/LastfmClient.kt` | Last.fm API: web auth, signing, `track.scrobble` (batches of 50), now playing, album info, top albums, everyone's counts (`*.getInfo`, `artist.getTopTracks`) |
| `mb/MusicBrainzClient.kt` | MusicBrainz lookups for missing track lengths (1 request per second) |
| `scrobble/Planner.kt`, `Spin.kt` | Timing a record: start times, Last.fm's half-or-4-minutes rule, flip, stop, resync |
| `scrobble/ScrobbleQueue.kt` | Durable scrobble queue (survives offline and app kills) and the activity log |
| `scrobble/PlayTracker.kt` | When a phone listen counts: listened time, Last.fm's rule, repeats, pauses |
| `scrobble/Compare.kt` | Shadow mode compared with what reached Last.fm |
| `PhoneListener.kt`, `Phone.kt` | Media sessions of music apps (via notification access), app list, mode |
| `history/HistorySync.kt` | Resumable import of user.getRecentTracks with a time cursor |
| `history/History.kt`, `history/Stats.kt` | The history in memory; every figure on the Stats and Explore tabs, song and album pages, playlists |
| `history/Genres.kt` | Genres from Discogs styles and cleaned Last.fm tags |
| `history/Atlas.kt` | The atlas: links between artists, the spring layout, constellations, mountain shapes |
| `history/Globals.kt` | Last.fm's all-time counts for everyone (listeners, plays, top songs), kept on the phone for "you vs everyone" |
| `history/Hidden.kt` | Scrobbles hidden from the stats, and spotting videos and app sounds |
| `tidal/` | TIDAL API: app and user tokens (PKCE), catalog search and matching, playlists; the cache of found songs |
| `HistoryDb.kt` | SQLite store for the history, record plays, genre tags and cover URLs |
| `Backup.kt` | Backup and restore: history, files and settings in one zip, without keys and tokens |
| `Core.kt` | App state and work: sync, lookups, the spinning record, sending |
| `Background.kt` | Alarms at track changes, the flush job for offline scrobbles, the notification |
| `MainActivity.kt` | Hosts the web UI, Material You palette, theme, browser hand-off |
| `assets/ui/` | The interface (HTML/JS in a WebView; `mock.js` stands in for the app in a browser); `look.css` holds the type, grain, glow, sleeve back, turntable and story styles |
| `assets/ui/fonts/` | Fraunces and DM Mono (woff2) with their licenses |

Unit tests in `app/src/test` run the API clients against a local fake server. They use real
Discogs and MusicBrainz responses (`src/test/resources/fixtures`) and Last.fm responses written in
the documented format.

## Terms of the services it uses

- For the [Discogs API terms](https://support.discogs.com/hc/en-us/articles/360009334593-API-Terms-of-Use),
  the notice under Credits is in the README and in the app (Settings, at the bottom). "Data
  provided by Discogs", linked to discogs.com, appears under the shelf and on record pages, and the
  collection syncs again when it is more than 6 hours old.
- For the [Last.fm API terms](https://www.last.fm/api/tos), the app is non-commercial. A "Powered by
  AudioScrobbler" credit linked to Last.fm is on the Stats tab and in Settings, and the app shows no
  Last.fm artwork (covers come from your shelf or Deezer).
- For the [TIDAL developer terms](https://developer.tidal.com/documentation/guidelines/guidelines-developer-terms),
  the app is non-commercial, songs it finds are kept for 30 days, and shared builds need production
  approval (see above).
- Deezer and MusicBrainz are used for public catalog lookups, within their rate limits (Deezer 50
  requests in 5 seconds, MusicBrainz 1 a second).
- The [setlist.fm API](https://api.setlist.fm/docs/1.0/index.html) is free for non-commercial
  projects and needs a key. The app makes at most 2 requests a second and keeps answers for 12
  hours, and setlists link back to setlist.fm on artist pages and concert playlists.
