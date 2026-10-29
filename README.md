# Heavy Rotation

**Your record shelf and everything you listen to, in one Android app.**

![Heavy Rotation: the shelf, a record, your month on a record, the musical atlas and now playing](docs/screenshots/banner.jpg)

Heavy Rotation shows your [Discogs](https://www.discogs.com) collection as a shelf of covers, scrobbles
the records you play to [Last.fm](https://www.last.fm) side by side, scrobbles what plays on your phone
too, and turns your whole Last.fm history into stats that know which records you own.

Free and open source (MIT). No account, no server, no ads, no tracking: the app talks to Discogs,
Last.fm, Deezer, MusicBrainz and TIDAL directly, and your data stays on your phone.

## What it does

- **Scrobble a record in one tap.** A side, the whole record or a few tracks, starting now, earlier
  ("last night") or just finished. Tracks are timed from their real lengths, "now playing" shows on
  Last.fm while a side spins, the next side is one tap, and it all works offline.
- **Scrobble the phone too.** TIDAL, Spotify or any music app, by Last.fm's rules (half the track or
  4 minutes). A shadow mode logs without sending, so you can compare it with Pano Scrobbler first.
- **Stats for a day, a week, a month, a year or all time**, each compared with the one before: your
  plays as dots on a record, when you listen, streaks, new artists, genre drift, obsessions, top 10.
- **Stats that know your shelf**: the records you own but rarely play, the albums you stream a lot
  but don't own yet, how much of your listening was vinyl.
- **A story for any period** to tap through, Wrapped-style, in the colors of your top album, with
  the photos you took then and the song that was playing (if you switch that on; they stay on the phone).
- **Explore**: lost favorites, albums to rediscover, a musical atlas of the artists you play back to
  back, you against everyone on Last.fm, and playlists to send to TIDAL.
- **Now playing** for the phone: position, controls, the app's queue and the album around the song.
- **Concert playlists** from setlist.fm: what an artist plays lately, or the setlist of one show.
- Material You colors, light and dark, two looks (tinted soft and soft glass), and covers that tilt
  with the phone, holo foil for the records you own.

| | | |
| :---: | :---: | :---: |
| <img src="docs/screenshots/shelf.jpg" width="240" alt="The shelf"> | <img src="docs/screenshots/record.jpg" width="240" alt="A record"> | <img src="docs/screenshots/stats-month.jpg" width="240" alt="A month of stats"> |
| Your shelf | A record, ready to scrobble | A month, compared with the last |
| <img src="docs/screenshots/day.jpg" width="240" alt="One day"> | <img src="docs/screenshots/story.jpg" width="240" alt="The story"> | <img src="docs/screenshots/shelf-dark.jpg" width="240" alt="The shelf in the dark"> |
| A day, for a handful of songs | The story | In the dark |

The screenshots show the app's demo data, with placeholder covers.

## Get it

Download the latest `HeavyRotation-x.y.apk` from the **Releases** page and open it on the phone
(Android 8 or newer). Android asks once to allow installs from the app you opened it with.

- **Play Protect** may warn about an unknown app, mostly because it isn't from the Play Store and
  can use notification access (for phone scrobbling). Tap **More details → Install anyway**.
- **Updates**: install a newer APK over the old one; your data stays. [Obtainium](https://obtainium.imranr.dev/)
  can watch the Releases page and update for you.

## Set up

1. **Discogs**: Settings → Discogs → your Discogs username → Connect. A private collection needs a
   personal token from [discogs.com/settings/developers](https://www.discogs.com/settings/developers) instead.
2. **Last.fm**: Settings → Last.fm → Connect, and allow access on Last.fm's page.
3. **Stats**: open the Stats tab and tap **Import history** (about a minute per 20,000 scrobbles).
4. **Phone scrobbling** (optional): Settings → Phone scrobbling → Shadow, then allow notification
   access. Switch to On once it matches what you expect.
5. **TIDAL** (optional): exact links, and playlists straight into your TIDAL.

Every step, and everything the app does, is in [the guide](docs/GUIDE.md).

## Privacy

- Your history, collection and settings live in the app's storage on your phone. Backups are files
  you save yourself; keys, tokens and logins never go into them.
- The app only talks to Discogs, Last.fm, Deezer, MusicBrainz, LRCLIB (lyrics), TIDAL and setlist.fm
  (once you connect them) and the servers the covers come from. There's no server of its own, no
  analytics and no crash reporting.
- Photos in your stories are off until you switch them on; then the app reads the photos you took in
  a story's days to show them there. They never leave the phone.
- Notification access is how Android lets an app see what music apps play. Heavy Rotation reads
  the media players of the apps you switch on, never your notifications.

## Credits

This application uses Discogs’ API but is not affiliated with, sponsored or endorsed by Discogs.
‘Discogs’ is a trademark of Zink Media, LLC.

Listening data powered by AudioScrobbler ([Last.fm](https://www.last.fm)). Covers, artist photos,
discographies and release years from [Deezer](https://www.deezer.com); track lengths also from
[MusicBrainz](https://musicbrainz.org); exact links and playlists with [TIDAL](https://tidal.com);
lyrics from [LRCLIB](https://lrclib.net); setlists from [setlist.fm](https://www.setlist.fm).
Heavy Rotation isn't affiliated with any of them.

The fonts Fraunces and DM Mono are under the SIL Open Font License (`app/src/main/assets/ui/fonts`).

## How it was made

Heavy Rotation was designed and tested by its author on their own shelf and phone, and written
with the help of Claude, Anthropic's AI coding assistant.

## Building it yourself

See [docs/DEVELOPING.md](docs/DEVELOPING.md): building, the app's own API keys, signing releases,
previewing the interface in a browser, how the code is laid out, and the terms of the services it uses.

## License

MIT, see [LICENSE](LICENSE).
