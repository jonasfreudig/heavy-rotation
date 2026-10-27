# Building Heavy Rotation

Needs JDK 17 and the Android SDK (platform 34).

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK (`app/build/outputs/apk/debug/app-debug.apk`) installs as **Heavy Rotation (debug)**
(`app.recordshelf.debug`), next to a release build.

## The app's own keys (for builds you share)

So that people only sign in, a build can carry its own Last.fm API account and TIDAL app. Create
them (the Last.fm and TIDAL steps above, under "If the app asks…") and put them in
`local.properties` in the project folder, which stays out of git:

```properties
recordshelf.lastfm.key=...
recordshelf.lastfm.secret=...
recordshelf.tidal.clientId=...
recordshelf.tidal.secret=...
```

Leave them out and everyone enters their own in Settings. Keys inside an APK can be dug out, as
with every app of this kind, so use accounts made just for this app.

TIDAL: a new TIDAL app runs in development mode, with small quotas, until TIDAL reviews it and
moves it to production (their [developer terms](https://developer.tidal.com/documentation/guidelines/guidelines-developer-terms),
"Usage, Production Mode and Approval"). Until yours is approved, build the APKs you hand out with
`./gradlew assembleRelease -PwithoutTidal`, which leaves your TIDAL app out: the TIDAL buttons then
open TIDAL's search, and anyone can still connect their own TIDAL app in Settings.

## Release builds

Make your signing key once, and keep the file and its passwords safe: every update has to be signed
with the same key, or it won't install over the previous version.

```bash
keytool -genkeypair -v -keystore record-shelf.jks -alias recordshelf -keyalg RSA -keysize 4096 -validity 10000
```

Then describe it in `keystore.properties` next to `settings.gradle` (also kept out of git):

```properties
storeFile=record-shelf.jks
storePassword=...
keyAlias=recordshelf
keyPassword=...
```

```bash
./gradlew assembleRelease
```

gives `app/build/outputs/apk/release/app-release.apk` (`app.recordshelf`). Without
`keystore.properties` the release APK comes out unsigned.

## Preview the UI in a browser

The interface is a local web page with a mock backend, so it runs in any browser:

```bash
python -m http.server 8791 --directory app/src/main/assets/ui
```

Then open <http://localhost:8791/> (add `?mock=new` for a fresh install, `?mock=spin` for a record
already playing).

## Layout

| Path | What |
| --- | --- |
| `discogs/` | Discogs client (public collections by username, or a token; rate limits), collection and release parsing, tracklists and sides |
| `lastfm/LastfmClient.kt` | Last.fm API: web auth, signing, `track.scrobble` (batches of 50), now playing, album info, top albums, everyone's counts (`*.getInfo`, `artist.getTopTracks`) |
| `mb/MusicBrainzClient.kt` | MusicBrainz lookups for missing track lengths (1 request per second) |
| `scrobble/Planner.kt`, `Spin.kt` | Timing a record: start times, Last.fm's half-or-4-minutes rule, flip, stop, resync |
| `scrobble/ScrobbleQueue.kt` | Durable scrobble queue (survives offline and app kills) and the activity log |
| `scrobble/PlayTracker.kt` | When a phone listen counts: listened time, Last.fm's rule, repeats, pauses |
| `scrobble/Compare.kt` | Shadow mode vs. what reached Last.fm |
| `PhoneListener.kt`, `Phone.kt` | Media sessions of music apps (via notification access), app list, mode |
| `history/HistorySync.kt` | Resumable import of user.getRecentTracks with a time cursor |
| `history/History.kt`, `history/Stats.kt` | The history in memory; every figure on the Stats and Explore tabs, song and album pages, playlists |
| `history/Genres.kt` | Genres from Discogs styles and cleaned Last.fm tags |
| `history/Atlas.kt` | The atlas: links between artists, the spring layout, constellations, mountain shapes |
| `history/Globals.kt` | Last.fm's all-time counts for everyone (listeners, plays, top songs), kept on the phone for "you vs everyone" |
| `tidal/` | TIDAL API: app and user tokens (PKCE), catalog search and matching, playlists; the cache of found songs |
| `history/Hidden.kt` | Scrobbles hidden from the stats, and spotting videos and app sounds |
| `HistoryDb.kt` | SQLite store for the history, record plays, genre tags and cover URLs |
| `Backup.kt` | Backup and restore: history, files and settings in one zip, without keys and tokens |
| `Core.kt` | App state and work: sync, lookups, the spinning record, sending |
| `Background.kt` | Alarms at track changes, the flush job for offline scrobbles, the notification |
| `MainActivity.kt` | Hosts the web UI, Material You palette, theme, browser hand-off |
| `assets/ui/` | The interface (HTML/JS in a WebView; `mock.js` stands in for the app in a browser); `look.css` holds the type, grain, glow, sleeve back, turntable and story styles |
| `assets/ui/fonts/` | Fraunces and DM Mono (woff2) with their licenses |

Unit tests in `app/src/test` run the API clients against a local fake server, using real
Discogs and MusicBrainz responses (`src/test/resources/fixtures`) and Last.fm answers in the
documented format.

## Terms of the services it uses

- **Discogs** ([API terms](https://support.discogs.com/hc/en-us/articles/360009334593-API-Terms-of-Use)):
  the notice under Credits in the README and in the app (Settings, at the bottom), "Data provided by
  Discogs" linked to discogs.com under the shelf and on record pages, and the collection syncs again
  when it is more than 6 hours old.
- **Last.fm** ([API terms](https://www.last.fm/api/tos)): non-commercial; a "Powered by AudioScrobbler"
  credit linked to Last.fm on the Stats tab and in Settings; no Last.fm artwork is shown (covers
  come from your shelf or Deezer).
- **TIDAL** ([developer terms](https://developer.tidal.com/documentation/guidelines/guidelines-developer-terms)):
  non-commercial; songs found are kept 30 days; production approval for shared builds (above).
- **Deezer** and **MusicBrainz**: public catalog lookups, kept within their rate limits (Deezer 50
  requests in 5 seconds, MusicBrainz 1 a second).
